package com.almus.ytdownloader.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real Room migrations: history is never wiped. The SQL must match what Room generates for the entities
 * (see the schema file in app/schemas, written by the first build).
 */
object Migrations {
    /** Version 1 had only `downloads`. Version 2 adds the new engine's table; `downloads` is untouched. */
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `engine_downloads` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`urlKey` TEXT NOT NULL, " +
                    "`fileName` TEXT NOT NULL, " +
                    "`mimeType` TEXT, " +
                    "`subfolder` TEXT, " +
                    "`state` TEXT NOT NULL, " +
                    "`downloadedBytes` INTEGER NOT NULL, " +
                    "`totalBytes` INTEGER NOT NULL, " +
                    "`etag` TEXT, " +
                    "`lastModified` TEXT, " +
                    "`acceptsRanges` INTEGER, " +
                    "`partName` TEXT NOT NULL, " +
                    "`finalUri` TEXT, " +
                    "`errorMessage` TEXT, " +
                    "`retryCount` INTEGER NOT NULL, " +
                    "`nextRetryAtMs` INTEGER NOT NULL, " +
                    "`insecure` INTEGER NOT NULL, " +
                    "`notified` INTEGER NOT NULL, " +
                    "`createdAtMs` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_engine_downloads_urlKey` ON `engine_downloads` (`urlKey`)")
        }
    }
}
