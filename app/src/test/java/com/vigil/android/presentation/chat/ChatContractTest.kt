package com.vigil.android.presentation.chat

import com.google.gson.Gson
import com.vigil.android.presentation.feature_messaging.data.model.ChatMessageResponse
import com.vigil.android.presentation.feature_messaging.data.model.ChatSyncResponse
import org.junit.Assert.*
import org.junit.Test

class ChatContractTest {
    @Test fun offlineCursorUsesServerAcknowledgedNextAfter() {
        val page = Gson().fromJson("""{"events":[{"id":41,"kind":"message.created","conversation_id":9}],
            "next_after":41,"has_more":true}""", ChatSyncResponse::class.java)
        assertEquals(41L, page.latestEventId)
        assertTrue(page.hasMore)
        assertEquals("message.created", page.events.single().type)
        val empty = Gson().fromJson("""{"events":[],"next_after":41,"has_more":false}""", ChatSyncResponse::class.java)
        assertEquals(page.latestEventId, empty.latestEventId)
    }

    @Test fun oldMessagesAndPrivateAudioMapToCorrectFields() {
        val raw = """{"id":1,"conversation_id":4,"sender":{"id":7,"name":"A"},"created_at":"2026-11-23T10:21:00Z",
            "client_id":null,"image_url":null,"attachment_type":"audio","attachment_url":"/api/v1/chat/messages/1/image/"}"""
        val message = Gson().fromJson(raw, ChatMessageResponse::class.java)
        assertNull(message.clientId)
        assertNull(message.imageUrl)
        assertEquals("audio", message.attachmentType)
        assertEquals("/api/v1/chat/messages/1/image/", message.attachmentUrl)
    }
}
