package com.almus.ytdownloader.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1: `downloads` (DownloadManager-based engine).
 * Version 2: adds `engine_downloads` (new engine). See [Migrations.V1_TO_V2].
 * The schema is exported to app/schemas so migrations can be checked.
 */
@Database(entities = [DownloadEntity::class, EngineDownloadEntity::class], version = 2, exportSchema = true)
abstract class AlmusDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun engineDownloadDao(): EngineDownloadDao
}
