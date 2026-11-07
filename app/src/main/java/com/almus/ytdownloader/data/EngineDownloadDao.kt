package com.almus.ytdownloader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface EngineDownloadDao {
    @Insert
    suspend fun insert(entity: EngineDownloadEntity): Long

    @Update
    suspend fun update(entity: EngineDownloadEntity)

    @Query("SELECT * FROM engine_downloads ORDER BY id ASC")
    suspend fun getAll(): List<EngineDownloadEntity>

    @Query("DELETE FROM engine_downloads WHERE id = :id")
    suspend fun deleteById(id: Long)
}
