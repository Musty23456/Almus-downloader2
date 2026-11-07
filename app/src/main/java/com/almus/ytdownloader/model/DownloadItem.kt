package com.almus.ytdownloader.model

import androidx.annotation.StringRes
import com.almus.ytdownloader.R
import com.almus.ytdownloader.data.DownloadEntity
import com.almus.ytdownloader.download.BulkAction
import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.download.DownloadState
import com.almus.ytdownloader.download.DurationFormat
import com.almus.ytdownloader.download.SpeedInfo
import com.almus.ytdownloader.download.StateFilter

/** One entry per [DownloadState], with the text shown for it. */
enum class DownloadStatus(@StringRes val labelRes: Int, val state: DownloadState) {
    Waiting(R.string.status_waiting, DownloadState.Waiting),
    Queued(R.string.status_queued, DownloadState.Queued),
    Active(R.string.status_active, DownloadState.Running),
    Paused(R.string.status_paused, DownloadState.Paused),
    OnHold(R.string.status_on_hold, DownloadState.OnHold),
    Completed(R.string.status_completed, DownloadState.Completed),
    Failed(R.string.status_failed, DownloadState.Failed),
    Cancelled(R.string.status_cancelled, DownloadState.Cancelled),
    ;

    val isUnfinished: Boolean get() = !state.isTerminal

    companion object {
        fun of(state: DownloadState): DownloadStatus = entries.first { it.state == state }
    }
}

/** Tabs on the Downloads screen. Which states each tab shows is defined by [StateFilter]. */
enum class DownloadFilter(
    @StringRes val labelRes: Int,
    @StringRes val emptyTitleRes: Int,
    @StringRes val emptyMessageRes: Int,
    val group: StateFilter,
) {
    All(R.string.tab_all, R.string.downloads_empty_all_title, R.string.downloads_empty_all_message, StateFilter.All),
    Active(R.string.tab_active, R.string.downloads_empty_active_title, R.string.downloads_empty_active_message, StateFilter.Active),
    Paused(R.string.tab_paused, R.string.downloads_empty_paused_title, R.string.downloads_empty_paused_message, StateFilter.Paused),
    Completed(R.string.tab_completed, R.string.downloads_empty_completed_title, R.string.downloads_empty_completed_message, StateFilter.Completed),
    Failed(R.string.tab_failed, R.string.downloads_empty_failed_title, R.string.downloads_empty_failed_message, StateFilter.Failed),
    ;

    fun matches(status: DownloadStatus): Boolean = group.matches(status.state)

    val bulkActions: List<BulkAction> get() = group.bulkActions
}

/** A download as shown in the UI, built from a stored record plus live speed data. */
data class DownloadItem(
    val id: Long,
    val fileName: String,
    val url: String,
    val status: DownloadStatus,
    /** 0f..1f. Only meaningful when [progressKnown]. */
    val progress: Float,
    val progressKnown: Boolean,
    /** Downloaded and total size, e.g. "3.2 MB of 10.0 MB". */
    val sizeLabel: String,
    /** e.g. "1.2 MB/s", or null when not reliably known. */
    val speedLabel: String?,
    /** e.g. "about 25 s left", or null unless enough reliable data exists. */
    val etaLabel: String?,
    val errorMessage: String?,
    val category: FileCategory,
    val insecure: Boolean,
    /** True for downloads of the new engine that can really be paused (shows a Pause button). */
    val canPause: Boolean = false,
)

fun DownloadEntity.toItem(speed: SpeedInfo? = null): DownloadItem {
    val state = DownloadState.fromName(state)
    val known = totalBytes > 0
    val fraction = when {
        state == DownloadState.Completed -> 1f
        known -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val label = when (state) {
        DownloadState.Completed -> ByteFormat.format(if (known) totalBytes else downloadedBytes)
        DownloadState.Waiting, DownloadState.OnHold -> if (known) ByteFormat.format(totalBytes) else ""
        else -> ByteFormat.progressLabel(downloadedBytes, totalBytes)
    }
    // Speed and remaining time are only ever shown for a download that is really transferring.
    val live = if (state == DownloadState.Running) speed else null
    return DownloadItem(
        id = id,
        fileName = fileName,
        url = url,
        status = DownloadStatus.of(state),
        progress = fraction,
        progressKnown = known || state == DownloadState.Completed,
        sizeLabel = label,
        speedLabel = live?.bytesPerSecond?.let { ByteFormat.speed(it) },
        etaLabel = live?.etaSeconds?.let { DurationFormat.remaining(it) },
        errorMessage = errorMessage,
        category = FileCategory.from(fileName, mimeType),
        insecure = insecure,
    )
}
