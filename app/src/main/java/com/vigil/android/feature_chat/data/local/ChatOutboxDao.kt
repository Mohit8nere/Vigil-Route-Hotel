package com.vigil.android.feature_chat.data.local

import androidx.room.*
import com.vigil.android.feature_chat.data.local.entities.ChatOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatOutboxDao {
    @Query("SELECT * FROM chat_outbox WHERE loggedInUserId = :userId ORDER BY localId ASC")
    fun getPendingOutboxFlow(userId: Long): Flow<List<ChatOutboxEntity>>

    @Query("SELECT * FROM chat_outbox WHERE loggedInUserId = :userId AND status IN ('PENDING', 'PROCESSING') ORDER BY localId ASC")
    fun getPendingOutboxList(userId: Long): List<ChatOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOutbox(entry: ChatOutboxEntity): Long

    @Query("DELETE FROM chat_outbox WHERE clientId = :clientId")
    fun deleteByClientId(clientId: String)

    @Query("UPDATE chat_outbox SET status = :status, retryCount = retryCount + 1, lastAttemptAt = :attemptAt WHERE clientId = :clientId")
    fun updateAttempt(clientId: String, status: String, attemptAt: Long)
}
