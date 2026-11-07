package com.almus.ytdownloader.download

import com.almus.ytdownloader.data.DownloadDao
import com.almus.ytdownloader.data.DownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-in for the Room DAO. */
class FakeDao : DownloadDao {
    val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(entity: DownloadEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }

    override suspend fun update(entity: DownloadEntity) {
        rows.value = rows.value.map { if (it.id == entity.id) entity else it }
    }

    override fun observeAll(): Flow<List<DownloadEntity>> =
        rows.map { list -> list.sortedWith(compareByDescending<DownloadEntity> { it.createdAt }.thenByDescending { it.id }) }

    override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }

    override suspend fun getBySystemId(systemId: Long) = rows.value.firstOrNull { it.systemId == systemId }

    override suspend fun getByStates(states: List<String>) = rows.value.filter { it.state in states }

    override suspend fun getFileNamesExcludingStates(excludedStates: List<String>) =
        rows.value.filter { it.state !in excludedStates }.map { it.fileName }

    override suspend fun getAllSystemIds() = rows.value.mapNotNull { it.systemId }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun claimNotification(id: Long): Int {
        val row = rows.value.firstOrNull { it.id == id } ?: return 0
        if (row.notified) return 0
        update(row.copy(notified = true))
        return 1
    }
}

/** Plays the role of Android's DownloadManager. Tests change [snapshots] to simulate progress. */
class FakeSystem : SystemDownloader {
    var nextId = 100L
    val snapshots = mutableMapOf<Long, SystemSnapshot>()
    val enqueued = mutableListOf<EnqueueRequest>()
    val removed = mutableListOf<Long>()
    var enqueueError: Exception? = null

    /** Makes the next N enqueue calls fail, then works again. */
    var failNextEnqueues = 0

    override fun enqueue(request: EnqueueRequest): Long {
        enqueueError?.let { throw it }
        if (failNextEnqueues > 0) {
            failNextEnqueues--
            throw IllegalStateException("storage unavailable")
        }
        enqueued += request
        val id = nextId++
        snapshots[id] = SystemSnapshot(StatusMapper.STATUS_PENDING, 0, 0, -1, null, null)
        return id
    }

    override fun query(ids: List<Long>): Map<Long, SystemSnapshot> =
        ids.mapNotNull { id -> snapshots[id]?.let { id to it } }.toMap()

    /** Like DownloadManager.remove: returns 1 if the system knew the id (and forgets it), else 0. */
    override fun remove(id: Long): Int {
        removed += id
        return if (snapshots.remove(id) != null) 1 else 0
    }

    override fun unfinishedIds(): Set<Long> = snapshots
        .filterValues {
            it.status == StatusMapper.STATUS_PENDING ||
                it.status == StatusMapper.STATUS_RUNNING ||
                it.status == StatusMapper.STATUS_PAUSED
        }
        .keys
}

class FakeStorage(
    var target: StorageTarget = StorageTarget.PublicDownloads,
    var free: Long = -1,
    var existing: Set<String> = emptySet(),
) : StorageInfo {
    override fun target() = target

    override fun freeBytes(target: StorageTarget) = free

    /** Names are remembered as "Video/clip.mp4" style relative paths, so sub-folders can be told apart. */
    override fun fileExists(target: StorageTarget, fileName: String, subfolder: String?) =
        SaveFolders.relativePath(subfolder, fileName) in existing
}

class FakeSettings(override var maxConcurrent: Int = 3) : QueueSettings

class FakeNotifier : DownloadNotifier {
    val sent = mutableListOf<DownloadEntity>()

    override fun notifyFinished(entity: DownloadEntity) {
        sent += entity
    }
}

fun linkInfo(
    name: String = "file.zip",
    length: Long = 1000,
    mime: String? = "application/zip",
    url: String = "https://example.com/$name",
) = LinkInfo(
    url = url,
    finalUrl = url,
    fileName = name,
    mimeType = mime,
    contentLength = length,
    redirectCount = 0,
    insecure = false,
    looksLikeWebPage = false,
)

/** Helper for tests: a record in the given state. */
fun entityOf(
    id: Long = 0,
    url: String = "https://example.com/a.zip",
    name: String = "a.zip",
    state: DownloadState = DownloadState.Waiting,
    systemId: Long? = null,
    createdAt: Long = 1,
) = DownloadEntity(
    id = id, url = url, fileName = name, mimeType = null, systemId = systemId, state = state.name,
    downloadedBytes = 0, totalBytes = -1, errorMessage = null, localUri = null, insecure = false,
    notified = false, createdAt = createdAt, updatedAt = createdAt,
)
