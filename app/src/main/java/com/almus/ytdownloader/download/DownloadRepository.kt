package com.almus.ytdownloader.download

import com.almus.ytdownloader.data.DownloadDao
import com.almus.ytdownloader.data.DownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StartResult {
    /** [queued] is true when the download waits in the app queue because every slot is busy. */
    data class Started(val id: Long, val queued: Boolean = false) : StartResult

    data class Rejected(val message: String) : StartResult
}

/** Outcome of an operation on several downloads. [skipped] items were not eligible or failed. */
data class BatchResult(val done: Int, val skipped: Int, val messages: List<String> = emptyList())

/**
 * Owns the download table, the app-side queue and the link to Android's DownloadManager.
 *
 * Design:
 * - The system DownloadManager does the actual transfer, so it survives the app being closed,
 *   handles network changes and resumes by itself after a reboot.
 * - This class keeps the queue: at most [QueueSettings.maxConcurrent] downloads are handed to the
 *   system at a time, the rest wait as [DownloadState.Waiting] records in the database (FIFO).
 * - DownloadManager has no public pause/resume, so a running transfer cannot be paused. Only
 *   items still waiting in the queue can be put on hold.
 * - Every state change happens under one lock, so the queue, the table and the system cannot
 *   disagree because of two operations racing.
 */
class DownloadRepository(
    private val dao: DownloadDao,
    private val system: SystemDownloader,
    private val storage: StorageInfo,
    private val notifier: DownloadNotifier,
    private val settings: QueueSettings,
    private val saveSettings: SaveSettings = FixedSaveSettings(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()
    private val estimators = HashMap<Long, SpeedEstimator>()
    private val _speeds = MutableStateFlow<Map<Long, SpeedInfo>>(emptyMap())

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    /** Speed and remaining time of running downloads. Only contains entries with reliable data. */
    val speeds: StateFlow<Map<Long, SpeedInfo>> = _speeds.asStateFlow()

    suspend fun get(id: Long): DownloadEntity? = dao.getById(id)

    /** All completed downloads. The file manager checks the real files behind them. */
    suspend fun completed(): List<DownloadEntity> = dao.getByStates(listOf(DownloadState.Completed.name))

    // ------------------------------------------------------------------ adding

    /** True when the same link is already waiting, held, queued, running or paused. */
    suspend fun isDuplicate(url: String): Boolean = lock.withLock { findDuplicate(url, null) != null }

    /** Records a download the user approved and starts it when a slot is free. */
    suspend fun start(info: LinkInfo): StartResult = lock.withLock {
        findDuplicate(info.url, null)?.let {
            return@withLock StartResult.Rejected(duplicateMessage(it.fileName))
        }
        val target = storage.target()
        spaceProblem(info.contentLength, target)?.let { return@withLock StartResult.Rejected(it) }

        val name = uniqueName(info.fileName, info.mimeType, target, ignoreId = null)
        val now = clock()
        val id = dao.insert(
            DownloadEntity(
                url = info.url,
                fileName = name,
                mimeType = info.mimeType,
                systemId = null,
                state = DownloadState.Waiting.name,
                downloadedBytes = 0,
                totalBytes = info.contentLength,
                errorMessage = null,
                localUri = null,
                insecure = info.insecure,
                notified = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        pumpLocked(silentId = id)
        val saved = dao.getById(id) ?: return@withLock StartResult.Rejected("Could not save the download.")
        if (saved.state == DownloadState.Failed.name) {
            StartResult.Rejected(saved.errorMessage ?: "The download could not be started.")
        } else {
            StartResult.Started(id, queued = saved.state == DownloadState.Waiting.name)
        }
    }

    // ------------------------------------------------------------------ user operations

    /**
     * Stops downloads that are not finished and deletes their partial data.
     * Finished downloads are left alone.
     */
    suspend fun cancel(ids: Collection<Long>): Int = lock.withLock {
        var count = 0
        for (id in ids) {
            val entity = dao.getById(id) ?: continue
            if (!DownloadPolicy.canCancel(DownloadState.fromName(entity.state))) continue
            entity.systemId?.let { runCatching { system.remove(it) } }
            dao.update(
                entity.copy(
                    state = DownloadState.Cancelled.name,
                    errorMessage = "Cancelled.",
                    updatedAt = clock(),
                ),
            )
            forgetSpeed(id)
            count++
        }
        pumpLocked(silentId = null)
        count
    }

    suspend fun cancel(id: Long): Int = cancel(listOf(id))

    /** Puts downloads that are still waiting in the queue on hold. Running ones cannot be paused. */
    suspend fun hold(ids: Collection<Long>): Int = lock.withLock {
        var count = 0
        for (id in ids) {
            val entity = dao.getById(id) ?: continue
            if (!DownloadPolicy.canHold(DownloadState.fromName(entity.state))) continue
            dao.update(entity.copy(state = DownloadState.OnHold.name, updatedAt = clock()))
            count++
        }
        count
    }

    suspend fun hold(id: Long): Int = hold(listOf(id))

    /** Puts held downloads back in the queue (keeping their place) and starts them if a slot is free. */
    suspend fun resume(ids: Collection<Long>): Int = lock.withLock {
        var count = 0
        for (id in ids) {
            val entity = dao.getById(id) ?: continue
            if (!DownloadPolicy.canResume(DownloadState.fromName(entity.state))) continue
            dao.update(entity.copy(state = DownloadState.Waiting.name, updatedAt = clock()))
            count++
        }
        pumpLocked(silentId = null)
        count
    }

    suspend fun resume(id: Long): Int = resume(listOf(id))

    /**
     * Starts failed or cancelled downloads again from the beginning. They reuse their record and
     * go to the end of the queue. A download whose link is already queued is skipped.
     */
    suspend fun retry(ids: Collection<Long>): BatchResult = lock.withLock {
        var done = 0
        var skipped = 0
        val messages = mutableListOf<String>()
        for (id in ids) {
            val old = dao.getById(id)
            if (old == null || !DownloadPolicy.canRetry(DownloadState.fromName(old.state))) {
                skipped++
                continue
            }
            if (findDuplicate(old.url, ignoreId = old.id) != null) {
                skipped++
                messages += duplicateMessage(old.fileName)
                continue
            }
            val target = storage.target()
            val problem = spaceProblem(old.totalBytes, target)
            if (problem != null) {
                skipped++
                messages += problem
                continue
            }
            val now = clock()
            dao.update(
                old.copy(
                    fileName = uniqueName(old.fileName, old.mimeType, target, ignoreId = old.id),
                    systemId = null,
                    state = DownloadState.Waiting.name,
                    downloadedBytes = 0,
                    errorMessage = null,
                    localUri = null,
                    notified = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            done++
        }
        pumpLocked(silentId = ids.singleOrNull())
        BatchResult(done, skipped, messages)
    }

    suspend fun retry(id: Long): StartResult {
        val result = retry(listOf(id))
        if (result.done != 1) {
            return StartResult.Rejected(
                result.messages.firstOrNull() ?: "Only failed or cancelled downloads can be retried.",
            )
        }
        val entity = dao.getById(id) ?: return StartResult.Rejected("This download no longer exists.")
        return if (entity.state == DownloadState.Failed.name) {
            StartResult.Rejected(entity.errorMessage ?: "The download could not be started.")
        } else {
            StartResult.Started(id, queued = entity.state == DownloadState.Waiting.name)
        }
    }

    /**
     * Removes finished records from the list. This NEVER touches the downloaded file or the
     * system's download entry. Records that are not finished are ignored (cancel them instead).
     */
    suspend fun removeRecords(ids: Collection<Long>): Int = lock.withLock {
        var count = 0
        for (id in ids) {
            val entity = dao.getById(id) ?: continue
            if (!DownloadPolicy.canRemoveRecord(DownloadState.fromName(entity.state))) continue
            dao.delete(id)
            forgetSpeed(id)
            count++
        }
        count
    }

    suspend fun removeRecords(id: Long): Int = removeRecords(listOf(id))

    /**
     * Deletes the downloaded files of COMPLETED downloads from the device and removes their records.
     * Call only after the user confirmed. If Android no longer lists a file, the record is kept
     * and a message explains why nothing was deleted.
     */
    suspend fun deleteFiles(ids: Collection<Long>): BatchResult = lock.withLock {
        var done = 0
        var skipped = 0
        val messages = mutableListOf<String>()
        for (id in ids) {
            val entity = dao.getById(id) ?: continue
            if (!DownloadPolicy.canDeleteFile(DownloadState.fromName(entity.state))) {
                skipped++
                continue
            }
            val systemId = entity.systemId
            val removed = if (systemId != null) runCatching { system.remove(systemId) }.getOrDefault(0) else 0
            if (removed > 0) {
                dao.delete(id)
                done++
            } else {
                skipped++
                messages += "\"${entity.fileName}\": Android no longer lists this file, so it cannot be deleted " +
                    "from here. Delete it in your Files app if it is still there."
            }
        }
        BatchResult(done, skipped, messages)
    }

    // ------------------------------------------------------------------ synchronisation

    /** Applies a changed concurrency setting: starts waiting downloads if more slots are free now. */
    suspend fun onSettingsChanged() = lock.withLock { pumpLocked(silentId = null) }

    /**
     * Brings the table in line with the system downloader and fills free queue slots.
     * Safe to call at any time (app start, progress loop, completion receiver, boot receiver).
     * @return true while something still needs regular polling.
     */
    suspend fun refresh(): Boolean = lock.withLock { refreshLocked() }

    /** [refresh] plus cleanup of system downloads that have no record (e.g. after a crash). */
    suspend fun reconcile(): Boolean = lock.withLock {
        val active = refreshLocked()
        sweepOrphansLocked()
        active
    }

    /** Called when the system says a download finished. Ids that are not ours are ignored. */
    suspend fun onSystemDownloadComplete(systemId: Long) {
        if (dao.getBySystemId(systemId) == null) return
        refresh()
    }

    // ------------------------------------------------------------------ internals (lock held)

    private suspend fun refreshLocked(): Boolean {
        val unfinished = dao.getByStates(DownloadState.unfinished.map { it.name })
        if (unfinished.isEmpty()) {
            estimators.clear()
            _speeds.value = emptyMap()
            return false
        }
        val tracked = unfinished.filter { DownloadState.fromName(it.state).holdsSlot && it.systemId != null }
        val snapshots = if (tracked.isEmpty()) emptyMap() else system.query(tracked.mapNotNull { it.systemId })

        val now = clock()
        val speedMap = HashMap<Long, SpeedInfo>()
        val liveIds = HashSet<Long>()
        for (entity in unfinished) {
            val state = DownloadState.fromName(entity.state)
            if (state == DownloadState.Waiting || state == DownloadState.OnHold) continue

            val systemId = entity.systemId
            val updated = if (systemId == null) {
                // Handed off but never got a system id: the app died in between.
                entity.copy(
                    state = DownloadState.Failed.name,
                    errorMessage = "The download could not be started. Retry it.",
                    updatedAt = now,
                )
            } else {
                SnapshotReducer.reduce(entity, snapshots[systemId], now)
            }
            if (updated != entity) dao.update(updated)

            val newState = DownloadState.fromName(updated.state)
            if (newState == DownloadState.Running) {
                liveIds += updated.id
                val estimator = estimators.getOrPut(updated.id) { SpeedEstimator() }
                estimator.record(updated.downloadedBytes, now)
                val remaining = if (updated.totalBytes > 0) updated.totalBytes - updated.downloadedBytes else -1L
                val bps = estimator.bytesPerSecond(now)
                val eta = if (remaining > 0) estimator.etaSeconds(remaining, now) else null
                if (bps != null || eta != null) speedMap[updated.id] = SpeedInfo(bps, eta)
            }
            if (newState == DownloadState.Completed || newState == DownloadState.Failed) announce(updated)
        }
        estimators.keys.retainAll(liveIds)
        _speeds.value = speedMap

        pumpLocked(silentId = null) // finished downloads freed slots
        return dao.getByStates(DownloadState.polled.map { it.name }).isNotEmpty()
    }

    /**
     * Hands waiting downloads (oldest first) to the system while slots are free.
     * Before each hand-off the record is saved as Queued without a system id, so a crash in
     * between is detected later (and the orphaned system download is removed) instead of
     * silently starting the same download twice.
     */
    private suspend fun pumpLocked(silentId: Long?) {
        val max = settings.maxConcurrent.coerceIn(QueueSettings.MIN, QueueSettings.MAX)
        val live = dao.getByStates(DownloadState.polled.map { it.name })
        var used = live.count { DownloadState.fromName(it.state).holdsSlot && it.systemId != null }
        val waiting = live
            .filter { it.state == DownloadState.Waiting.name }
            .sortedWith(compareBy<DownloadEntity>({ it.createdAt }, { it.id }))

        for (entity in waiting) {
            if (used >= max) break
            val target = storage.target()
            val problem = spaceProblem(entity.totalBytes, target)
            if (problem != null) {
                failQueued(entity, problem, silentId)
                continue
            }
            val handoff = entity.copy(
                state = DownloadState.Queued.name,
                systemId = null,
                errorMessage = null,
                updatedAt = clock(),
            )
            dao.update(handoff)
            try {
                val systemId = system.enqueue(
                    EnqueueRequest(
                        url = entity.url,
                        fileName = entity.fileName,
                        mimeType = entity.mimeType,
                        target = target,
                        subfolder = SaveFolders.subfolderFor(saveSettings.layout, entity.fileName, entity.mimeType),
                    ),
                )
                dao.update(handoff.copy(systemId = systemId, updatedAt = clock()))
                used++
            } catch (e: Exception) {
                failQueued(
                    handoff,
                    "The download could not be started: ${e.message ?: e.javaClass.simpleName}",
                    silentId,
                )
            }
        }
    }

    private suspend fun failQueued(entity: DownloadEntity, message: String, silentId: Long?) {
        val failed = entity.copy(
            state = DownloadState.Failed.name,
            errorMessage = message,
            // The user is looking at the item they just started: no extra notification for it.
            notified = entity.id == silentId,
            updatedAt = clock(),
        )
        dao.update(failed)
        if (entity.id != silentId) announce(failed)
    }

    /** Removes unfinished system downloads that no record refers to. Finished ones are never touched. */
    private suspend fun sweepOrphansLocked() {
        val referenced = dao.getAllSystemIds().toSet()
        val unfinishedInSystem = runCatching { system.unfinishedIds() }.getOrDefault(emptySet())
        for (orphan in unfinishedInSystem - referenced) {
            runCatching { system.remove(orphan) }
        }
    }

    private suspend fun announce(entity: DownloadEntity) {
        if (dao.claimNotification(entity.id) == 1) notifier.notifyFinished(entity)
    }

    private fun forgetSpeed(id: Long) {
        estimators.remove(id)
        if (_speeds.value.containsKey(id)) _speeds.value = _speeds.value - id
    }

    private suspend fun findDuplicate(url: String, ignoreId: Long?): DownloadEntity? {
        val key = UrlKey.of(url)
        return dao.getByStates(DownloadState.unfinished.map { it.name })
            .firstOrNull { it.id != ignoreId && UrlKey.of(it.url) == key }
    }

    private fun duplicateMessage(fileName: String) =
        "This link is already in your queue or downloading (\"$fileName\")."

    private fun spaceProblem(contentLength: Long, target: StorageTarget): String? {
        if (contentLength <= 0) return null
        val free = storage.freeBytes(target)
        if (free in 0 until contentLength) {
            return "Not enough free storage: the file needs ${ByteFormat.format(contentLength)} " +
                "but only ${ByteFormat.format(free)} is free."
        }
        return null
    }

    private suspend fun uniqueName(base: String, mimeType: String?, target: StorageTarget, ignoreId: Long?): String {
        val subfolder = SaveFolders.subfolderFor(saveSettings.layout, base, mimeType)
        val used = dao.getFileNamesExcludingStates(
            listOf(DownloadState.Failed.name, DownloadState.Cancelled.name),
        ).toMutableSet()
        if (ignoreId != null) dao.getById(ignoreId)?.let { used.remove(it.fileName) }
        var candidate = FileNameResolver.makeUnique(base, used)
        // Also avoid files that already exist on disk.
        var guard = 0
        while (storage.fileExists(target, candidate, subfolder) && guard < 100) {
            used.add(candidate)
            candidate = FileNameResolver.makeUnique(base, used)
            guard++
        }
        return candidate
    }
}
