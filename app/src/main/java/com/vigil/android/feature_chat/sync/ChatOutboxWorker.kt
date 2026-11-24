package com.vigil.android.feature_chat.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ChatApiService
import com.vigil.android.feature_chat.data.local.ChatDatabase
import com.vigil.android.presentation.feature_messaging.data.model.SendTextMessageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

@HiltWorker
class ChatOutboxWorker @AssistedInject constructor(
    @Assisted private val context: Context, @Assisted params: WorkerParameters,
    private val api: ChatApiService, private val session: SessionManager, private val db: ChatDatabase
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val owner = inputData.getLong("owner", -1)
        val outbox = db.chatOutboxDao()
        val messages = db.messageDao()
        if (owner <= 0 || session.getUserId()?.toLongOrNull() != owner) return@withContext Result.success()
        var retry = false
        for (entry in outbox.getPendingOutboxList(owner)) {
            if (session.getUserId()?.toLongOrNull() != owner) break
            val (field, mime) = when (entry.messageType) {
                "IMAGE" -> "image" to "image/jpeg"
                "AUDIO" -> "audio" to "audio/mp4"
                "DOCUMENT" -> "document" to "application/pdf"
                else -> "" to ""
            }
            try {
                messages.updateMessageSending(entry.clientId)
                val response = if (field.isEmpty()) {
                    api.sendTextMessage(entry.conversationId, SendTextMessageRequest(entry.payload, entry.clientId), owner.toString())
                } else {
                    val file = File(Uri.parse(requireNotNull(entry.mediaPath)).path.orEmpty())
                    require(file.isFile && file.length() > 0)
                    val part = MultipartBody.Part.createFormData(field, file.name, file.asRequestBody(mime.toMediaType()))
                    val text = "text/plain".toMediaType()
                    api.sendImageMessage(entry.conversationId, part, entry.payload.toRequestBody(text), entry.clientId.toRequestBody(text), owner.toString())
                }
                val msg = response.body()
                if (response.isSuccessful && msg != null) {
                    db.withTransaction {
                        messages.updateMessageSyncStatus(entry.clientId, "SENT", "SYNCED", msg.id, msg.createdAt)
                        (msg.attachmentUrl ?: msg.imageUrl)?.let { messages.confirmAttachment(entry.clientId, it) }
                        outbox.deleteByClientId(entry.clientId)
                    }
                    if (entry.mediaPath != null && (msg.attachmentUrl ?: msg.imageUrl) != null) {
                        val local = File(Uri.parse(entry.mediaPath).path.orEmpty()).canonicalFile
                        if (local.parentFile == File(context.filesDir, "chat-outbox/$owner").canonicalFile) local.delete()
                    }
                } else if (response.code() in listOf(408, 429) || response.code() >= 500) {
                    retry = true
                    messages.updateDelivery(entry.clientId, "PENDING")
                } else {
                    messages.updateMessageFailed(entry.clientId)
                    outbox.updateAttempt(entry.clientId, "FAILED", System.currentTimeMillis())
                }
            } catch (_: IOException) {
                messages.updateDelivery(entry.clientId, "PENDING")
                retry = true
            } catch (_: IllegalArgumentException) {
                messages.updateMessageFailed(entry.clientId)
                outbox.updateAttempt(entry.clientId, "FAILED", System.currentTimeMillis())
            }
        }
        if (retry) Result.retry() else Result.success()
    }
}

@HiltWorker
class ChatInboxWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters,
    private val session: SessionManager, private val sync: ChatSyncManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!session.isLoggedIn() || session.getUserId()?.toLongOrNull() != inputData.getLong("owner", -1)) return Result.success()
        return try { sync.synchronize(); Result.success() } catch (_: IOException) { Result.retry() }
    }
}
