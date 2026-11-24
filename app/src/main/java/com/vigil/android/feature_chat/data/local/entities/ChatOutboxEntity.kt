package com.vigil.android.feature_chat.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_outbox",
    indices = [Index(value = ["clientId"], unique = true), Index(value = ["loggedInUserId"])]
)
data class ChatOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val clientId: String,
    val conversationId: Long,
    val loggedInUserId: Long,
    val messageType: String, // "TEXT" or "IMAGE"
    val payload: String,     // body text
    val mediaPath: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long = 0,
    val status: String = "PENDING" // "PENDING", "PROCESSING", "FAILED"
)
