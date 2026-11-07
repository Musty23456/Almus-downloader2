package com.almus.ytdownloader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A download owned by the new download engine. It lives in its own table so the older table (used by the
 * DownloadManager-based engine) is never touched. Added in database version 2.
 */
@Entity(tableName = "engine_downloads", indices = [Index(value = ["urlKey"])])
data class EngineDownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val urlKey: String,
    val fileName: String,
    val mimeType: String?,
    val subfolder: String?,
    val state: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String?,
    val lastModified: String?,
    val acceptsRanges: Boolean?,
    val partName: String,
    val finalUri: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val nextRetryAtMs: Long,
    val insecure: Boolean,
    val notified: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
