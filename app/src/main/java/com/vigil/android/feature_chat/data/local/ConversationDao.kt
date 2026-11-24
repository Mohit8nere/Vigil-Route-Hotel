package com.vigil.android.feature_chat.data.local

import androidx.room.*
import com.vigil.android.feature_chat.data.local.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE loggedInUserId = :userId AND loggedInUserId > 0 ORDER BY updatedAt DESC")
    fun getConversationsFlow(userId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT SUM(unreadCount) FROM conversations WHERE loggedInUserId = :userId")
    fun getTotalUnreadCountFlow(userId: Long): Flow<Int?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND loggedInUserId = :userId LIMIT 1")
    fun getConversationById(conversationId: Long, userId: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0, lastReadMessageId = :messageId WHERE id = :conversationId AND loggedInUserId = :userId")
    fun markAsRead(conversationId: Long, userId: Long, messageId: Long)

    @Query("SELECT id FROM conversations WHERE loggedInUserId = :userId")
    fun ids(userId: Long): List<Long>

    @Query("DELETE FROM conversations WHERE loggedInUserId = :userId AND id = :id")
    fun remove(userId: Long, id: Long)

    @Query("DELETE FROM conversations WHERE loggedInUserId = :userId")
    fun clearUserConversations(userId: Long)
}
