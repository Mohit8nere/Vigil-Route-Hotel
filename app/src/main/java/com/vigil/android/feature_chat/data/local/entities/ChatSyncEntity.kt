package com.vigil.android.feature_chat.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sync")
data class ChatSyncEntity(
    @PrimaryKey
    val loggedInUserId: Long,
    val lastEventId: Long = 0,
    val lastSuccessfulSyncAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
