package com.vigil.android.feature_chat.data.repository

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.room.withTransaction
import com.vigil.android.feature_chat.sync.ChatInboxWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import android.net.Uri
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ChatApiService
import com.vigil.android.feature_chat.data.local.*
import com.vigil.android.feature_chat.data.local.entities.*
import com.vigil.android.feature_chat.data.remote.ChatWebSocketManager
import com.vigil.android.feature_chat.sync.ChatOutboxWorker
import com.vigil.android.feature_chat.sync.ChatSyncManager
import com.vigil.android.presentation.feature_messaging.data.model.ReadStateRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val chatApiService: ChatApiService,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val chatOutboxDao: ChatOutboxDao,
    private val db: ChatDatabase,
    private val http: okhttp3.OkHttpClient,
    private val chatSyncManager: ChatSyncManager,
    private val webSocketManager: ChatWebSocketManager
) {
    fun getCurrentUserId(): Long {
        return sessionManager.getUserId()?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    fun getCurrentUserName(): String {
        return sessionManager.getUserName() ?: "Officer"
    }

    fun getConversationsFlow(): Flow<List<ConversationEntity>> {
        val userId = getCurrentUserId()
        return conversationDao.getConversationsFlow(userId)
    }

    fun getTotalUnreadCountFlow(): Flow<Int?> {
        val userId = getCurrentUserId()
        return conversationDao.getTotalUnreadCountFlow(userId)
    }

    fun getMessagesFlow(conversationId: Long): Flow<List<MessageEntity>> {
        val userId = getCurrentUserId()
        return messageDao.getMessagesFlow(conversationId, userId)
    }

    suspend fun refreshConversations() = chatSyncManager.synchronize()

    suspend fun refreshMessages(conversationId: Long, before: Long? = null) =
        chatSyncManager.fetchMessages(conversationId, before)

    suspend fun sendMessageLocallyAndEnqueue(conversationId: Long, bodyText: String,
        messageType: String = "TEXT", mediaPath: String? = null) = withContext(Dispatchers.IO) {
        val owner = getCurrentUserId()
        require(owner > 0 && conversationId > 0)
        require(bodyText.length <= 4000 && bodyText.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' }) { "Use letters, numbers and spaces in chat text." }
        require(bodyText.isNotBlank() || mediaPath != null)
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "chat-outbox/$owner").apply { mkdirs() }
        val file = mediaPath?.let {
            val uri = Uri.parse(it)
            if (messageType == "IMAGE") com.vigil.android.features.fieldops.preparePhoto(context, uri, dir)
            else {
                val target = File(dir, id + if (messageType == "AUDIO") ".m4a" else ".pdf")
                try {
                    val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open attachment." }
                    input.use { source -> target.outputStream().use { out ->
                        val buffer = ByteArray(16384)
                        var size = 0
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= 2 * 1024 * 1024) { "Voice notes and PDFs must be under 2 MiB." }
                            out.write(buffer, 0, n)
                        }
                        require(size > 0) { "Attachment is empty." }
                    } }
                    target
                } catch (e: Exception) { target.delete(); throw e }
            }
        }
        val path = file?.let { Uri.fromFile(it).toString() }
        try {
            db.withTransaction {
                require(owner == getCurrentUserId())
                messageDao.insertMessage(MessageEntity(conversationId = conversationId, loggedInUserId = owner,
                    senderId = owner, senderName = getCurrentUserName(), mediaType = messageType, body = bodyText,
                    localMediaPath = path, clientId = id, createdAt = Instant.now().toString(),
                    deliveryStatus = "PENDING", syncStatus = "PENDING", isOutgoing = true))
                chatOutboxDao.insertOutbox(ChatOutboxEntity(clientId = id, conversationId = conversationId,
                    loggedInUserId = owner, messageType = messageType, payload = bodyText, mediaPath = path))
            }
        } catch (e: Exception) { file?.delete(); throw e }
        enqueue(owner)
    }

    private fun enqueue(owner: Long) {
        val request = OneTimeWorkRequestBuilder<ChatOutboxWorker>().setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("chat_send_$owner", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun deleteMessage(localId: Long, serverId: Long?, deleteForEveryone: Boolean) = withContext(Dispatchers.IO) {
        require(serverId != null)
        val response = chatApiService.deleteMessage(serverId, if (deleteForEveryone) "everyone" else "me")
        check(response.isSuccessful) { "Delete was declined (HTTP ${response.code()})." }
        if (deleteForEveryone) messageDao.markDeletedForEveryone(serverId, Instant.now().toString(), getCurrentUserId()) else messageDao.markDeletedForMe(localId)
    }

    suspend fun markAsRead(conversationId: Long, lastMessageId: Long): Boolean = withContext(Dispatchers.IO) {
        if (chatSyncManager.openThread != conversationId) return@withContext false
        val owner = getCurrentUserId()
        val success = chatApiService.markAsRead(conversationId, ReadStateRequest(lastMessageId)).isSuccessful
        if (success) conversationDao.markAsRead(conversationId, owner, lastMessageId)
        success
    }

    suspend fun mediaFile(path: String, type: String): File = withContext(Dispatchers.IO) {
        val owner = getCurrentUserId()
        require(owner > 0)
        if (path.startsWith("file:")) {
            val local = File(requireNotNull(Uri.parse(path).path)).canonicalFile
            require(local.parentFile == File(context.filesDir, "chat-outbox/$owner").canonicalFile)
            return@withContext local
        }
        require(Regex("/api/v1/chat/messages/[0-9]+/image/").matches(path))
        val ext = when (type) { "AUDIO" -> "m4a"; "DOCUMENT" -> "pdf"; else -> "webp" }
        val url = com.vigil.android.core.di.api.ApiNames.BASE_URL.trimEnd('/') + path
        val request = okhttp3.Request.Builder().url(url).header("X-Vigil-Local-Owner", owner.toString()).build()
        val tmp = File.createTempFile("chat_", ".$ext", context.cacheDir)
        try {
            http.newCall(request).execute().use { res ->
                check(res.isSuccessful) { "Attachment unavailable (HTTP ${res.code})." }
                requireNotNull(res.body).byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            tmp
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    suspend fun startDirectConversation(otherUserId: Long): Long? = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()
        try {
            val response = chatApiService.createConversation(
                com.vigil.android.presentation.feature_messaging.data.model.CreateConversationRequest(
                    kind = "direct",
                    userId = otherUserId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val conv = response.body()!!
                val entity = ConversationEntity(
                    id = conv.id,
                    loggedInUserId = currentUserId,
                    kind = conv.kind,
                    name = conv.name,
                    description = conv.description,
                    lastMessage = conv.lastMessage,
                    lastMessageAt = conv.updatedAt,
                    unreadCount = conv.unreadCount,
                    lastReadMessageId = null,
                    updatedAt = conv.updatedAt
                )
                conversationDao.insertOrUpdateConversation(entity)
                return@withContext conv.id
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error starting direct conversation", e)
        }
        null
    }

    suspend fun createGroupConversation(name: String, description: String, memberIds: List<Long>): Long? = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()
        try {
            val response = chatApiService.createConversation(
                com.vigil.android.presentation.feature_messaging.data.model.CreateConversationRequest(
                    kind = "group",
                    name = name,
                    description = description,
                    memberIds = memberIds
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val conv = response.body()!!
                val entity = ConversationEntity(
                    id = conv.id,
                    loggedInUserId = currentUserId,
                    kind = conv.kind,
                    name = conv.name,
                    description = conv.description,
                    lastMessage = conv.lastMessage,
                    lastMessageAt = conv.updatedAt,
                    unreadCount = conv.unreadCount,
                    lastReadMessageId = null,
                    updatedAt = conv.updatedAt
                )
                conversationDao.insertOrUpdateConversation(entity)
                return@withContext conv.id
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error creating group conversation", e)
        }
        null
    }

    suspend fun getRoster(query: String? = null): List<com.vigil.android.presentation.feature_messaging.data.model.ChatUserResponse> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getRoster(query)
            if (response.isSuccessful && response.body() != null) {
                return@withContext response.body()!!
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error getting roster", e)
        }
        emptyList()
    }

    suspend fun getChatMe(): com.vigil.android.presentation.feature_messaging.data.model.ChatMeResponse? = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getChatMe()
            if (response.isSuccessful) return@withContext response.body()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error getChatMe", e)
        }
        null
    }

    suspend fun getConversationDetails(conversationId: Long): com.vigil.android.presentation.feature_messaging.data.model.ChatConversationResponse? = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()
        try {
            val response = chatApiService.getConversationDetails(conversationId)
            if (response.isSuccessful && response.body() != null) {
                val conv = response.body()!!
                val entity = ConversationEntity(
                    id = conv.id,
                    loggedInUserId = currentUserId,
                    kind = conv.kind,
                    name = conv.name,
                    description = conv.description,
                    lastMessage = conv.lastMessage,
                    lastMessageAt = conv.updatedAt,
                    unreadCount = conv.unreadCount,
                    lastReadMessageId = null,
                    updatedAt = conv.updatedAt
                )
                conversationDao.insertOrUpdateConversation(entity)
                return@withContext conv
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error getting conversation details", e)
        }
        null
    }

    suspend fun retryFailedMessage(clientId: String) = withContext(Dispatchers.IO) {
        chatOutboxDao.updateAttempt(clientId, "PENDING", System.currentTimeMillis())
        messageDao.updateDelivery(clientId, "PENDING")
        enqueue(getCurrentUserId())
    }

    suspend fun editMessage(serverId: Long, newBody: String) = withContext(Dispatchers.IO) {
        val response = chatApiService.editMessage(serverId, com.vigil.android.presentation.feature_messaging.data.model.UpdateMessageRequest(newBody))
        val message = response.body()
        check(response.isSuccessful && message != null) { "Edit was declined (HTTP ${response.code()})." }
        messageDao.getMessageByServerId(serverId, getCurrentUserId())?.let { existing ->
            messageDao.insertMessage(existing.copy(body = message.body, editedAt = message.editedAt))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var ownerId = -1L

    @Synchronized fun startWebSocketAndSync() {
        val owner = getCurrentUserId()
        if (owner <= 0 || sessionManager.getRole()?.uppercase() !in setOf("PATROL OFFICER","TRAFFIC POLICE","ROUTE CREATOR","TI","SP","SUPER ADMIN","SI")) return
        if (owner == ownerId && loop?.isActive == true) return
        stopWebSocket()
        ownerId = owner
        webSocketManager.connect { signals.trySend(Unit) }
        enqueue(owner)
        val inbox = PeriodicWorkRequestBuilder<ChatInboxWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(workDataOf("owner" to owner)).setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("chat_inbox_$owner", ExistingPeriodicWorkPolicy.KEEP, inbox)
        loop = scope.launch {
            while (isActive && owner == getCurrentUserId()) {
                try { chatSyncManager.synchronize() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                withTimeoutOrNull(15_000) { signals.receive() }
                delay(300)
            }
        }
    }
    fun showThread(id: Long?) { chatSyncManager.openThread = id; signals.trySend(Unit) }
    @Synchronized fun stopWebSocket() {
        loop?.cancel(); loop = null
        chatSyncManager.openThread = null
        webSocketManager.disconnect()
    }
}
