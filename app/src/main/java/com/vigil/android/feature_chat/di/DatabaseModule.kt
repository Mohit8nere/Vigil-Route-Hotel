package com.vigil.android.feature_chat.di

import android.content.Context
import androidx.room.Room
import com.vigil.android.feature_chat.data.local.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideChatDatabase(
        @ApplicationContext context: Context
    ): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            "vigil_chat_db"
        ).addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Version 1 mixed patrol-profile and account IDs. Preserve every record on the
                // device, but isolate that ambiguous namespace from current authenticated accounts.
                db.execSQL("UPDATE conversations SET id = -id, loggedInUserId = -loggedInUserId")
                db.execSQL("UPDATE messages SET loggedInUserId = -loggedInUserId, clientId = 'legacy:' || clientId")
                db.execSQL("UPDATE chat_outbox SET loggedInUserId = -loggedInUserId, status = 'LEGACY_HELD'")
                db.execSQL("UPDATE chat_sync SET loggedInUserId = -loggedInUserId")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("UPDATE messages SET mediaType = 'IMAGE' WHERE imageUrl IS NOT NULL OR localMediaPath IS NOT NULL")
            }
        }).build()
    }

    @Provides
    fun provideConversationDao(db: ChatDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: ChatDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChatOutboxDao(db: ChatDatabase): ChatOutboxDao = db.chatOutboxDao()

    @Provides
    fun provideChatSyncDao(db: ChatDatabase): ChatSyncDao = db.chatSyncDao()
}
