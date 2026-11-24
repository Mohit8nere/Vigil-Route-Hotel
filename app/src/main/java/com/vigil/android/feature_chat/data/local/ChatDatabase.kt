package com.vigil.android.feature_chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vigil.android.feature_chat.data.local.entities.ChatOutboxEntity
import com.vigil.android.feature_chat.data.local.entities.ChatSyncEntity
import com.vigil.android.feature_chat.data.local.entities.ConversationEntity
import com.vigil.android.feature_chat.data.local.entities.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ChatOutboxEntity::class,
        ChatSyncEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun chatOutboxDao(): ChatOutboxDao
    abstract fun chatSyncDao(): ChatSyncDao
}
