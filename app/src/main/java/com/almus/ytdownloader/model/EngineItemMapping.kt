package com.almus.ytdownloader.model

import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.download.DownloadState
import com.almus.ytdownloader.download.DurationFormat
import com.almus.ytdownloader.engine.EngineItem
import com.almus.ytdownloader.engine.EngineState

/**
 * Engine downloads share the list with the older engine's downloads. To keep the ids apart, an engine download
 * with id N is shown with id -N. Everything that receives an item id checks the sign.
 */
fun EngineItem.toDownloadItem(): DownloadItem {
    val d = download
    val state = d.state.toDownloadState()
    val known = d.totalBytes > 0
    val fraction = when {
        d.state == EngineState.Completed -> 1f
        known -> (d.downloadedBytes.toFloat() / d.totalBytes.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val label = if (d.state == EngineState.Completed) {
        ByteFormat.format(if (known) d.totalBytes else d.downloadedBytes)
    } else {
        ByteFormat.progressLabel(d.downloadedBytes, d.totalBytes)
    }
    val live = if (d.state == EngineState.Running) speed else null
    return DownloadItem(
        id = -d.id,
        fileName = d.fileName,
        url = d.url,
        status = DownloadStatus.of(state),
        progress = fraction,
        progressKnown = known || d.state == EngineState.Completed,
        sizeLabel = label,
        speedLabel = live?.bytesPerSecond?.let { ByteFormat.speed(it) },
        etaLabel = live?.etaSeconds?.let { DurationFormat.remaining(it) },
        errorMessage = d.errorMessage,
        category = FileCategory.from(d.fileName, d.mimeType),
        insecure = d.insecure,
        canPause = d.state == EngineState.Queued || d.state == EngineState.Connecting || d.state == EngineState.Running ||
            d.state == EngineState.WaitingForNetwork || d.state == EngineState.WaitingToRetry,
    )
}

/** A paused download is "on hold" (the user's choice); waiting for network or a retry is the automatic "paused". */
fun EngineState.toDownloadState(): DownloadState = when (this) {
    EngineState.Queued -> DownloadState.Queued
    EngineState.Connecting, EngineState.Running, EngineState.Verifying -> DownloadState.Running
    EngineState.Paused -> DownloadState.OnHold
    EngineState.WaitingForNetwork, EngineState.WaitingToRetry -> DownloadState.Paused
    EngineState.Completed -> DownloadState.Completed
    EngineState.Failed -> DownloadState.Failed
    EngineState.Cancelled -> DownloadState.Cancelled
}
