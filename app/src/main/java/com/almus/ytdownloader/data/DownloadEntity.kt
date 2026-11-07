package com.almus.ytdownloader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per download. [state] holds a [com.almus.ytdownloader.download.DownloadState] name.
 * [systemId] is the id given by Android's DownloadManager (null until it was enqueued).
 * [totalBytes] is -1 when the server did not say how big the file is.
 */
@Entity(tableName = "downloads", indices = [Index("systemId")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val systemId: Long?,
    val state: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val localUri: String?,
    val insecure: Boolean,
    val notified: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
