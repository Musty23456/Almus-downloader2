package com.almus.ytdownloader.model

import com.almus.ytdownloader.download.DurationFormat
import com.almus.ytdownloader.ytdlp.DownloadJob
import com.almus.ytdownloader.ytdlp.DownloadSpec
import com.almus.ytdownloader.ytdlp.JobState
import com.almus.ytdownloader.ytdlp.MediaKind
import com.almus.ytdownloader.ytdlp.YtJobIds

/** A yt-dlp download as the Downloads tab shows it. Its id comes from [YtJobIds], above the other two ranges. */
fun DownloadJob.toDownloadItem(): DownloadItem {
    val status = when (state) {
        JobState.QUEUED -> DownloadStatus.Queued
        JobState.RUNNING, JobState.SAVING -> DownloadStatus.Active
        JobState.DONE -> DownloadStatus.Completed
        JobState.FAILED -> DownloadStatus.Failed
        JobState.CANCELLED -> DownloadStatus.Cancelled
    }
    val extension = if (spec.kind == MediaKind.VIDEO) spec.container.ext else (spec.audio.ext ?: "m4a")
    val running = state == JobState.RUNNING
    val label = describe(spec)
    return DownloadItem(
        id = YtJobIds.toItemId(number),
        fileName = fileName ?: spec.title,
        url = spec.url,
        status = status,
        progress = if (state == JobState.DONE) 1f else (percent / 100f).coerceIn(0f, 1f),
        progressKnown = running || state == JobState.SAVING || state == JobState.DONE,
        sizeLabel = if (running) "${percent.toInt()}% · $label" else label,
        speedLabel = null,
        etaLabel = if (running && etaSeconds > 0) DurationFormat.remaining(etaSeconds) else null,
        errorMessage = error,
        category = FileCategory.from(fileName ?: "${spec.title}.$extension", null),
        insecure = false,
        canPause = false,
    )
}

private fun describe(spec: DownloadSpec): String = when (spec.kind) {
    MediaKind.VIDEO -> "${spec.container.ext.uppercase()} · ${spec.maxHeight?.let { "${it}p" } ?: "Best"}" +
        if (spec.subtitleLangs != null) " · CC" else ""
    MediaKind.AUDIO -> spec.audio.label
}
