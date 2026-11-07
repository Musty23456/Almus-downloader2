package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.SpeedInfo

/** Lifecycle of one download inside the engine. */
enum class EngineState {
    /** Waiting for a free slot. */
    Queued,

    /** Slot taken, connecting to the server. */
    Connecting,
    Running,

    /** All bytes received; checking the size and moving the file to its final place. */
    Verifying,

    /** Paused by the user. The partial file is kept. */
    Paused,

    /** No usable network (offline, or Wi-Fi only on mobile data). Continues by itself. */
    WaitingForNetwork,

    /** A connection problem happened; the engine tries again after a delay. */
    WaitingToRetry,
    Completed,
    Failed,
    Cancelled,
    ;

    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled

    /** True while a transfer coroutine owns the download (holds one concurrency slot). */
    val isActive: Boolean get() = this == Connecting || this == Running || this == Verifying

    /** True when the download is working or will start by itself (the app should stay alive for it). */
    val isBusy: Boolean get() = isActive || this == Queued || this == WaitingToRetry

    companion object {
        /** Unknown values (for example from a future version) are treated as failed. */
        fun fromName(name: String?): EngineState = entries.firstOrNull { it.name == name } ?: Failed
    }
}

/**
 * One persisted download. [totalBytes] is -1 when the server did not say how big the file is.
 * [partName] is a name the engine made itself (never text from the network), inside the part folder.
 */
data class EngineDownload(
    val id: Long,
    val url: String,
    val urlKey: String,
    val fileName: String,
    val mimeType: String?,
    val subfolder: String?,
    val state: EngineState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String?,
    val lastModified: String?,
    /** True/false when the server said so, null when unknown. */
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

/** What the caller asks for. The name and type are decided by the caller (the link check). */
data class EngineRequest(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val subfolder: String? = null,
    val insecure: Boolean = false,
    /** Size announced by the link check, or null. Only used to show a size early. */
    val expectedSize: Long? = null,
)

sealed interface EnqueueResult {
    data class Added(val id: Long) : EnqueueResult

    /** The same address is already queued, running, paused or waiting. */
    data class Duplicate(val existingId: Long) : EnqueueResult
    data class Rejected(val message: String) : EnqueueResult
}

/** A download plus live speed data (null while not reliably known) and how many connections it uses right now (0 when idle). */
data class EngineItem(val download: EngineDownload, val speed: SpeedInfo?, val connections: Int = 0)
