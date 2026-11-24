package com.vigil.android.feature_chat.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["loggedInUserId"]),
        Index(value = ["clientId"], unique = true),
        Index(value = ["serverMessageId"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val serverMessageId: Long? = null,
    val conversationId: Long,
    val loggedInUserId: Long,
    val senderId: Long,
    val senderName: String,
    @androidx.room.ColumnInfo(defaultValue = "'TEXT'") val mediaType: String = "TEXT",
    val body: String? = null,
    val imageUrl: String? = null,
    val localMediaPath: String? = null,
    val clientId: String,
    val createdAt: String,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val deletedBy: Long? = null,
    val deliveryStatus: String = "SENT", // "SENDING", "SENT", "FAILED"
    val syncStatus: String = "SYNCED",   // "PENDING", "SYNCED"
    val isOutgoing: Boolean = false,
    val isDeletedForMe: Boolean = false
)
