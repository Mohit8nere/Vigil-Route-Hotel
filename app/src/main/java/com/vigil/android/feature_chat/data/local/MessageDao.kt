package com.vigil.android.feature_chat.data.local

import androidx.room.*
import com.vigil.android.feature_chat.data.local.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND loggedInUserId = :userId AND loggedInUserId > 0 AND isDeletedForMe = 0 ORDER BY createdAt ASC, localId ASC")
    fun getMessagesFlow(conversationId: Long, userId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE clientId = :clientId AND loggedInUserId = :owner LIMIT 1")
    fun getMessageByClientId(clientId: String, owner: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE serverMessageId = :serverId AND loggedInUserId = :owner LIMIT 1")
    fun getMessageByServerId(serverId: Long, owner: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET deliveryStatus = :status, syncStatus = :syncStatus, serverMessageId = :serverId, createdAt = :createdAt WHERE clientId = :clientId")
    fun updateMessageSyncStatus(clientId: String, status: String, syncStatus: String, serverId: Long?, createdAt: String)

    @Query("UPDATE messages SET imageUrl = :url, localMediaPath = NULL WHERE clientId = :clientId")
    fun confirmAttachment(clientId: String, url: String)

    @Query("UPDATE messages SET deliveryStatus = :state WHERE clientId = :clientId")
    fun updateDelivery(clientId: String, state: String)

    @Query("UPDATE messages SET deliveryStatus = 'FAILED', syncStatus = 'PENDING' WHERE clientId = :clientId")
    fun updateMessageFailed(clientId: String)

    @Query("UPDATE messages SET deliveryStatus = 'SENDING', syncStatus = 'PENDING' WHERE clientId = :clientId")
    fun updateMessageSending(clientId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND loggedInUserId = :userId AND loggedInUserId > 0 AND isDeletedForMe = 0 AND serverMessageId IS NOT NULL AND serverMessageId < :beforeServerId ORDER BY serverMessageId DESC LIMIT :limit")
    fun getOlderMessagesBeforeId(conversationId: Long, userId: Long, beforeServerId: Long, limit: Int = 40): List<MessageEntity>

    @Query("UPDATE messages SET isDeletedForMe = 1 WHERE localId = :localId")
    fun markDeletedForMe(localId: Long)

    @Query("UPDATE messages SET deletedAt = :deletedAt, body = '' WHERE serverMessageId = :serverId AND loggedInUserId = :owner")
    fun markDeletedForEveryone(serverId: Long, deletedAt: String, owner: Long)

    @Query("DELETE FROM messages WHERE conversationId = :id AND loggedInUserId = :owner")
    fun removeThread(id: Long, owner: Long)

    @Query("UPDATE messages SET isDeletedForMe = 1 WHERE serverMessageId = :id AND loggedInUserId = :owner")
    fun hideRemote(id: Long, owner: Long)

    @Query("DELETE FROM messages WHERE loggedInUserId = :userId")
    fun clearUserMessages(userId: Long)
}
