package com.vigil.android.feature_chat.sync

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import com.vigil.android.MainActivity
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ChatApiService
import com.vigil.android.feature_chat.data.local.ChatDatabase
import com.vigil.android.feature_chat.data.local.entities.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSyncManager @Inject constructor(
    @ApplicationContext private val context: Context, private val api: ChatApiService,
    private val session: SessionManager, private val db: ChatDatabase
) {
    private val lock = Mutex()
    @Volatile var openThread: Long? = null
    suspend fun synchronize() = withContext(Dispatchers.IO) {
        lock.withLock {
            val owner = session.getUserId()?.toLongOrNull() ?: return@withLock
            val list = mutableListOf<com.vigil.android.presentation.feature_messaging.data.model.ChatConversationResponse>()
            var before: Long? = null
            do {
                val response = api.getConversations(before = before)
                list += response.body() ?: throw IOException("Chat HTTP ${response.code()}")
                val next = response.headers()["X-Vigil-Next-Conversation"]?.toLongOrNull() ?: break
                check(before == null || next < before)
                before = next
            } while (true)
            if (session.getUserId()?.toLongOrNull() != owner) return@withLock
            val dao = db.conversationDao()
            val prefs = context.getSharedPreferences("chat_alerts_$owner", Context.MODE_PRIVATE)
            val manager = context.getSystemService(NotificationManager::class.java)
            val valid = list.map { it.id }.toSet()
            db.withTransaction {
                for (id in dao.ids(owner) - valid) {
                    dao.remove(owner, id)
                    db.messageDao().removeThread(id, owner)
                }
                dao.insertOrUpdateConversations(list.map {
                    ConversationEntity(it.id, owner, it.kind, it.name, it.description, it.lastMessage,
                        it.updatedAt, it.unreadCount, null, it.updatedAt)
                })
            }
            for (c in list) {
                val key = c.id.toString()
                val last = c.lastMessageId ?: continue
                if (c.unreadCount > 0 && c.id != openThread && last > prefs.getLong(key, 0) &&
                    (android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                    manager.createNotificationChannel(NotificationChannel("vigil_chat", "Chat messages", NotificationManager.IMPORTANCE_DEFAULT))
                    val intent = Intent(context, MainActivity::class.java).putExtra("start_destination", "traffic_chat")
                    val click = PendingIntent.getActivity(context, 307, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify("chat_$owner", c.id.toInt(), NotificationCompat.Builder(context, "vigil_chat")
                        .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Vigil chat")
                        .setContentText("${c.unreadCount} unread in ${c.name}").setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(click).build())
                } else if (c.unreadCount == 0 || c.id == openThread) manager.cancel("chat_$owner", c.id.toInt())
                prefs.edit().putLong(key, last).apply()
            }
            // Events contain IDs only. Fetch authorized data for the open conversation and apply hide events.
            var cursor = db.chatSyncDao().getSyncState(owner)?.lastEventId ?: 0
            do {
                val data = api.getSyncEvents(cursor).body() ?: break
                if (session.getUserId()?.toLongOrNull() != owner) return@withLock
                val next = data.latestEventId ?: cursor
                db.withTransaction {
                    for (event in data.events) if (event.type == "message.hidden")
                        event.messageId?.let { db.messageDao().hideRemote(it, owner) }
                    db.chatSyncDao().saveSyncState(ChatSyncEntity(owner, next))
                }
                if (!data.hasMore || next <= cursor) break
                cursor = next
            } while (true)
            openThread?.takeIf { it in valid }?.let { fetchMessages(it) }
        }
    }

    suspend fun fetchMessages(thread: Long, before: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val owner = session.getUserId()?.toLongOrNull() ?: return@withContext false
        val response = api.getMessages(thread, 50, before)
        if (session.getUserId()?.toLongOrNull() != owner) return@withContext false
        if (response.code() in listOf(403,404)) {
            db.messageDao().removeThread(thread, owner)
            return@withContext false
        }
        val data = response.body() ?: throw IOException("Chat HTTP ${response.code()}")
        db.withTransaction {
            val dao = db.messageDao()
            for (msg in data.results) {
                val outgoing = msg.sender.id == owner
                val key = if (outgoing && msg.clientId != null) msg.clientId else "remote-$owner-${msg.id}"
                val old = dao.getMessageByServerId(msg.id, owner) ?: dao.getMessageByClientId(key, owner)
                dao.insertMessage(MessageEntity(localId = old?.localId ?: 0, serverMessageId = msg.id,
                    conversationId = thread, loggedInUserId = owner, senderId = msg.sender.id, senderName = msg.sender.name,
                    mediaType = msg.attachmentType?.uppercase() ?: if (msg.imageUrl != null) "IMAGE" else "TEXT",
                    body = msg.body, imageUrl = msg.attachmentUrl ?: msg.imageUrl,
                    localMediaPath = null, clientId = key, createdAt = msg.createdAt,
                    editedAt = msg.editedAt, deletedAt = msg.deletedAt, deletedBy = msg.deletedBy,
                    isOutgoing = outgoing, isDeletedForMe = old?.isDeletedForMe == true))
            }
        }
        data.hasMore
    }
}
