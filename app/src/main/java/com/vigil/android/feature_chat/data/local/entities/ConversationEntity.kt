package com.vigil.android.feature_chat.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["loggedInUserId"]), Index(value = ["loggedInUserId", "id"], unique = true)]
)
data class ConversationEntity(
    @PrimaryKey
    val id: Long,
    val loggedInUserId: Long,
    val kind: String,
    val name: String,
    val description: String? = null,
    val lastMessage: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val lastReadMessageId: Long? = null,
    val updatedAt: String
)
