package com.almus.ytdownloader.download

import com.almus.ytdownloader.data.DownloadEntity

/** Where a file is written. */
enum class StorageTarget {
    /** Public Downloads/ALMUS folder (visible in the Files app). */
    PublicDownloads,

    /** The app's own folder. Used on Android 9 and older when storage permission was declined. */
    AppPrivate,
}

data class EnqueueRequest(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val target: StorageTarget,
    /** Folder below Download/ALMUS (see [SaveFolders]), or null for the main folder. */
    val subfolder: String? = null,
)

/** Thin wrapper around android.app.DownloadManager so the logic can be tested with a fake. */
interface SystemDownloader {
    /** Queues the download and returns the system id. Throws on failure. */
    fun enqueue(request: EnqueueRequest): Long

    /** Current state for each id the system still knows. Missing ids are absent from the map. */
    fun query(ids: List<Long>): Map<Long, SystemSnapshot>

    /**
     * Removes the download from the system. This also stops it and DELETES its file, whether
     * partial or complete. Returns how many system entries were removed (0 if it was unknown).
     */
    fun remove(id: Long): Int

    /** Ids of this app's system downloads that are pending, running or paused. */
    fun unfinishedIds(): Set<Long>
}

/** User setting: how many downloads may run at the same time. */
interface QueueSettings {
    /** Always within [MIN]..[MAX]. */
    var maxConcurrent: Int

    companion object {
        const val MIN = 1
        const val MAX = 5
        const val DEFAULT = 3
    }
}

/** Storage facts the repository needs. */
interface StorageInfo {
    fun target(): StorageTarget

    /** Free bytes on the target volume, or -1 when unknown. */
    fun freeBytes(target: StorageTarget): Long

    fun fileExists(target: StorageTarget, fileName: String, subfolder: String? = null): Boolean
}

/** Shows the "finished" or "failed" notification. */
interface DownloadNotifier {
    fun notifyFinished(entity: DownloadEntity)
}
