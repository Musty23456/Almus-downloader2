package com.almus.ytdownloader.engine.android

import com.almus.ytdownloader.data.EngineDownloadDao
import com.almus.ytdownloader.data.EngineDownloadEntity
import com.almus.ytdownloader.engine.EngineDownload
import com.almus.ytdownloader.engine.EngineState
import com.almus.ytdownloader.engine.EngineStore

/** Room-backed [EngineStore]. */
class RoomEngineStore(private val dao: EngineDownloadDao) : EngineStore {
    override suspend fun insert(download: EngineDownload): Long = dao.insert(download.toEntity())

    override suspend fun update(download: EngineDownload) = dao.update(download.toEntity())

    override suspend fun all(): List<EngineDownload> = dao.getAll().map { it.toModel() }

    override suspend fun delete(id: Long) = dao.deleteById(id)
}

internal fun EngineDownload.toEntity() = EngineDownloadEntity(
    id = id, url = url, urlKey = urlKey, fileName = fileName, mimeType = mimeType, subfolder = subfolder,
    state = state.name, downloadedBytes = downloadedBytes, totalBytes = totalBytes, etag = etag,
    lastModified = lastModified, acceptsRanges = acceptsRanges, partName = partName, finalUri = finalUri,
    errorMessage = errorMessage, retryCount = retryCount, nextRetryAtMs = nextRetryAtMs, insecure = insecure,
    notified = notified, createdAtMs = createdAtMs, updatedAtMs = updatedAtMs,
)

internal fun EngineDownloadEntity.toModel() = EngineDownload(
    id = id, url = url, urlKey = urlKey, fileName = fileName, mimeType = mimeType, subfolder = subfolder,
    state = EngineState.fromName(state), downloadedBytes = downloadedBytes, totalBytes = totalBytes, etag = etag,
    lastModified = lastModified, acceptsRanges = acceptsRanges, partName = partName, finalUri = finalUri,
    errorMessage = errorMessage, retryCount = retryCount, nextRetryAtMs = nextRetryAtMs, insecure = insecure,
    notified = notified, createdAtMs = createdAtMs, updatedAtMs = updatedAtMs,
)
