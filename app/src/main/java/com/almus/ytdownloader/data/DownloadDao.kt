package com.almus.ytdownloader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE systemId = :systemId LIMIT 1")
    suspend fun getBySystemId(systemId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN (:states)")
    suspend fun getByStates(states: List<String>): List<DownloadEntity>

    @Query("SELECT fileName FROM downloads WHERE state NOT IN (:excludedStates)")
    suspend fun getFileNamesExcludingStates(excludedStates: List<String>): List<String>

    @Query("SELECT systemId FROM downloads WHERE systemId IS NOT NULL")
    suspend fun getAllSystemIds(): List<Long>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    /** Returns 1 only for the first caller, so a finished download is announced once. */
    @Query("UPDATE downloads SET notified = 1 WHERE id = :id AND notified = 0")
    suspend fun claimNotification(id: Long): Int
}
