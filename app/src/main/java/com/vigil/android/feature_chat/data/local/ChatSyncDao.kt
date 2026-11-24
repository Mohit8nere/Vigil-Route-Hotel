package com.vigil.android.feature_chat.data.local

import androidx.room.*
import com.vigil.android.feature_chat.data.local.entities.ChatSyncEntity

@Dao
interface ChatSyncDao {
    @Query("SELECT * FROM chat_sync WHERE loggedInUserId = :userId LIMIT 1")
    fun getSyncState(userId: Long): ChatSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSyncState(state: ChatSyncEntity)
}
