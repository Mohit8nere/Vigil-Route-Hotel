package com.vigil.android.feature_chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ChatApiService
import com.vigil.android.feature_chat.data.local.ChatDatabase
import com.vigil.android.feature_chat.sync.ChatSyncManager
import com.vigil.android.presentation.feature_messaging.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class ChatStoreTest {
    @Test fun repeatedRefreshKeepsAttachmentsUniqueAndScopedToAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        val session = SessionManager(context)
        val owner = 7001L
        val sender = ChatUserResponse(7002, "Test Officer", "test-officer", "Patrol Officer")
        val note = ChatMessageResponse(id = 29, conversationId = 16, sender = sender, clientId = null,
            createdAt = "2026-11-23T12:00:00Z", attachmentType = "audio", attachmentUrl = "/api/v1/chat/messages/29/image/")
        val fake = Proxy.newProxyInstance(ChatApiService::class.java.classLoader, arrayOf(ChatApiService::class.java)) { _, method, _ ->
            check(method.name == "getMessages")
            Response.success(ChatMessageListResponse(listOf(note, note.copy(id = 30, attachmentType = "document"))))
        } as ChatApiService
        try {
            session.saveLogin("instrumented-fixture", null, owner.toString(), "Test", "Patrol Officer")
            val sync = ChatSyncManager(context, fake, session, db)
            repeat(2) { sync.fetchMessages(16) }
            val stored = db.messageDao().getMessagesFlow(16, owner).first()
            assertEquals(listOf("AUDIO", "DOCUMENT"), stored.map { it.mediaType })
            assertFalse(stored.any { it.isOutgoing })
            assertTrue(db.messageDao().getMessagesFlow(16, sender.id).first().isEmpty())
        } finally {
            session.clearSession()
            db.close()
        }
    }
}
