package com.almus.ytdownloader.download

import com.almus.ytdownloader.data.DownloadEntity

/** Applies what the system downloader reports to a stored record. Pure and unit tested. */
object SnapshotReducer {
    const val REMOVED_MESSAGE = "The download is no longer known to the system Downloads app."

    /**
     * @param snapshot null when the system has no record for this download any more
     * (for example the user removed it in the system Downloads app).
     * Finished records never change again.
     */
    fun reduce(entity: DownloadEntity, snapshot: SystemSnapshot?, now: Long): DownloadEntity {
        val current = DownloadState.fromName(entity.state)
        if (current.isTerminal) return entity

        if (snapshot == null) {
            return entity.copy(
                state = DownloadState.Cancelled.name,
                errorMessage = REMOVED_MESSAGE,
                updatedAt = now,
            )
        }

        val state = StatusMapper.toState(snapshot.status)
        val message = when (state) {
            DownloadState.Failed -> StatusMapper.failureMessage(snapshot.reason)
            DownloadState.Paused -> StatusMapper.pausedMessage(snapshot.reason)
            else -> null
        }
        val total = if (snapshot.totalBytes > 0) snapshot.totalBytes else entity.totalBytes
        val downloaded = snapshot.downloadedBytes.coerceAtLeast(0)
        return entity.copy(
            state = state.name,
            downloadedBytes = if (state == DownloadState.Completed && total > 0) total else downloaded,
            totalBytes = total,
            errorMessage = message,
            localUri = snapshot.localUri ?: entity.localUri,
            mimeType = MimeTypes.normalize(snapshot.mimeType)?.takeUnless { MimeTypes.isGeneric(it) } ?: entity.mimeType,
            updatedAt = now,
        )
    }
}
