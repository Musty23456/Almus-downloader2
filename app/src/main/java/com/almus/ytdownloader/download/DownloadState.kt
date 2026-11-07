package com.almus.ytdownloader.download

/**
 * Lifecycle of one download as stored in Room.
 *
 * Waiting and OnHold live only in this app's queue: they have no system download yet.
 * Queued, Running and Paused belong to a system download (they hold one of the concurrency slots).
 * "Paused" is reported by the system itself (waiting for network or Wi-Fi) and resumes on its own;
 * this app cannot pause a running transfer.
 */
enum class DownloadState {
    /** In the app queue, waiting for a free slot. Nothing has been handed to the system yet. */
    Waiting,

    /** Handed to the system downloader, which has not started transferring yet. */
    Queued,
    Running,

    /** Paused by the system, e.g. waiting for a network connection. Resumes automatically. */
    Paused,

    /** Held by the user while still in the app queue. Does not start until resumed. */
    OnHold,
    Completed,
    Failed,
    Cancelled,
    ;

    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled

    /** True for states that occupy one of the concurrent download slots. */
    val holdsSlot: Boolean get() = this == Queued || this == Running || this == Paused

    companion object {
        val unfinished: List<DownloadState> = listOf(Waiting, Queued, Running, Paused, OnHold)

        /** States that need regular status polling (everything except a user hold). */
        val polled: List<DownloadState> = listOf(Waiting, Queued, Running, Paused)

        /** Unknown values (for example from a future app version) are treated as failed. */
        fun fromName(name: String?): DownloadState =
            entries.firstOrNull { it.name == name } ?: Failed
    }
}

/** What the system DownloadManager reports for one download id. Plain data, no Android types. */
data class SystemSnapshot(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String?,
    val mimeType: String?,
)

/**
 * Converts DownloadManager status and reason codes into [DownloadState] and readable messages.
 * The numeric values are copied from android.app.DownloadManager so this object can be unit
 * tested on the JVM; a test compares them with the real constants at compile time.
 */
object StatusMapper {
    const val STATUS_PENDING = 1
    const val STATUS_RUNNING = 2
    const val STATUS_PAUSED = 4
    const val STATUS_SUCCESSFUL = 8
    const val STATUS_FAILED = 16

    const val PAUSED_WAITING_TO_RETRY = 1
    const val PAUSED_WAITING_FOR_NETWORK = 2
    const val PAUSED_QUEUED_FOR_WIFI = 3
    const val PAUSED_UNKNOWN = 4

    const val ERROR_UNKNOWN = 1000
    const val ERROR_FILE_ERROR = 1001
    const val ERROR_UNHANDLED_HTTP_CODE = 1002
    const val ERROR_HTTP_DATA_ERROR = 1004
    const val ERROR_TOO_MANY_REDIRECTS = 1005
    const val ERROR_INSUFFICIENT_SPACE = 1006
    const val ERROR_DEVICE_NOT_FOUND = 1007
    const val ERROR_CANNOT_RESUME = 1008
    const val ERROR_FILE_ALREADY_EXISTS = 1009

    fun toState(status: Int): DownloadState = when (status) {
        STATUS_PENDING -> DownloadState.Queued
        STATUS_RUNNING -> DownloadState.Running
        STATUS_PAUSED -> DownloadState.Paused
        STATUS_SUCCESSFUL -> DownloadState.Completed
        STATUS_FAILED -> DownloadState.Failed
        else -> DownloadState.Failed
    }

    /** Message for a paused download, or null when there is nothing useful to say. */
    fun pausedMessage(reason: Int): String? = when (reason) {
        PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection."
        PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi."
        PAUSED_WAITING_TO_RETRY -> "Connection problem. Retrying automatically."
        else -> null
    }

    /** Message for a failed download. HTTP status codes arrive as the reason (100..599). */
    fun failureMessage(reason: Int): String = when {
        reason in 400..599 -> httpMessage(reason)
        reason in 100..399 -> "The server answered with an unexpected response ($reason)."
        else -> when (reason) {
            ERROR_FILE_ERROR -> "The file could not be saved on this device."
            ERROR_UNHANDLED_HTTP_CODE -> "The server answered with an unexpected response."
            ERROR_HTTP_DATA_ERROR -> "The connection broke while downloading. Check your network and retry."
            ERROR_TOO_MANY_REDIRECTS -> "The link redirects too many times."
            ERROR_INSUFFICIENT_SPACE -> "Not enough free storage space."
            ERROR_DEVICE_NOT_FOUND -> "Storage is not available. Check your SD card or storage."
            ERROR_CANNOT_RESUME -> "The download could not be resumed. Retry to start again."
            ERROR_FILE_ALREADY_EXISTS -> "A file with this name already exists."
            else -> "The download failed for an unknown reason."
        }
    }

    fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "Access denied ($code). This file needs a login or permission, which this app does not bypass."
        404, 410 -> "File not found ($code). The link may be wrong or expired."
        408 -> "The server timed out ($code)."
        429 -> "Too many requests ($code). Try again later."
        451 -> "This file is not available for legal reasons ($code)."
        in 400..499 -> "The server refused the request ($code)."
        in 500..599 -> "Server error ($code). Try again later."
        else -> "Unexpected server response ($code)."
    }
}
