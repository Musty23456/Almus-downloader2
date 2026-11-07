package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.LinkInspector
import com.almus.ytdownloader.download.SpeedEstimator
import com.almus.ytdownloader.download.SpeedInfo
import com.almus.ytdownloader.download.StatusMapper
import com.almus.ytdownloader.download.UrlKey
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The download engine: it streams files itself over HTTP(S), with real pause and resume.
 *
 * - Queue with a configurable number of simultaneous downloads ([EngineSettings.maxConcurrent]).
 * - Resume uses HTTP Range with If-Range (the saved ETag or date). The server's answer is checked:
 *   206 with a matching Content-Range continues; 200 (Range or validator not accepted) starts again from zero;
 *   416 is handled; servers that say "Accept-Ranges: none" are not asked to resume.
 * - The size is validated at the end (received bytes must equal the announced size when one is known).
 * - Connection problems are retried with growing delays; "no usable network" waits without using up retries.
 * - Everything is saved through [EngineStore], so a restart continues where it stopped ([start]).
 * - The partial file only ever grows at its end and is moved to its final place when complete ([FinalStorage]).
 * - Segmented downloads: when the server proves it supports byte ranges (see [SegmentationPolicy] and [RangeValidator]),
 *   a big file is fetched as segments over several connections ([SegmentedDownloader]); the number of connections
 *   adapts to measured speed and to the network ([AdaptiveConnections], [ConnectionPolicy]). Segments are checked,
 *   repaired one by one when damaged, merged safely ([SegmentMerger]), and every temporary file is cleaned up
 *   ([TempFiles]). Everything else, and every server that does not qualify, uses the single-connection path unchanged.
 *
 * All state changes happen under one lock; transfers run in their own coroutines. Nothing here depends on Android.
 */
class DownloadEngine(
    private val store: EngineStore,
    transport: HttpTransport,
    private val storage: FinalStorage,
    private val network: NetworkMonitor,
    private val settings: EngineSettings,
    private val scope: CoroutineScope,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val userAgent: String = LinkInspector.USER_AGENT,
    private val progressIntervalMs: Long = 250,
    private val persistIntervalMs: Long = 1_000,
    /** Called once when a download completes or fails (not when cancelled), for the finished notification. */
    private val onFinished: (EngineDownload) -> Unit = {},
    private val segmentation: SegmentationConfig = SegmentationConfig(),
) {
    private enum class StopIntent { Pause, Cancel, NetworkLoss, Requeue }

    private sealed interface Outcome {
        data object Gone : Outcome
        data object Done : Outcome
        data object RestartFresh : Outcome
        class Retry(val message: String, val retryAfterMs: Long? = null) : Outcome
        class Fail(val message: String, val keepPart: Boolean = false) : Outcome

        /** The server does not really support byte ranges: start again over one connection. */
        data object FallbackSingle : Outcome
    }

    private class NetworkReadException(cause: IOException) : Exception(cause)
    private class StorageWriteException(cause: IOException) : Exception(cause)

    private val session = HttpSession(transport)
    private val lock = Mutex()
    private val records = LinkedHashMap<Long, EngineDownload>()
    private val jobs = HashMap<Long, Job>()
    private val timers = HashMap<Long, Job>()
    private val intents = HashMap<Long, StopIntent>()
    private val estimators = HashMap<Long, SpeedEstimator>()
    private val lastPersistAt = HashMap<Long, Long>()
    /** The furthest point each download ever reached. Retries start counting again only beyond it. */
    private val highWater = HashMap<Long, Long>()
    private val responses = ConcurrentHashMap<Long, ResponseRegistry>()
    private val connections = ConcurrentHashMap<Long, Int>()

    /** Downloads whose server turned out not to support ranges properly: they stay on one connection. */
    private val segmentationOff: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile private var activeCount = 1
    private var started = false
    private var schedulingEnabled = true

    private val _items = MutableStateFlow<List<EngineItem>>(emptyList())

    /** Every download, newest first, with live speed. */
    val items: StateFlow<List<EngineItem>> = _items.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    /** Loads saved downloads, continues interrupted ones and starts following the network. Safe to call again. */
    suspend fun start() {
        recover()
        scope.launch {
            network.status.collect { networkChanged() }
        }
    }

    private suspend fun recover() = lock.withLock {
        records.clear()
        store.all().forEach { records[it.id] = it }
        for (rec in records.values.toList()) {
            when (rec.state) {
                // The process died while this one was working: continue from the partial file.
                EngineState.Connecting, EngineState.Running, EngineState.Verifying ->
                    putLocked(rec.copy(state = EngineState.Queued, downloadedBytes = partLength(rec), errorMessage = null))
                EngineState.WaitingToRetry, EngineState.WaitingForNetwork ->
                    putLocked(rec.copy(state = EngineState.Queued, errorMessage = null))
                else -> Unit
            }
        }
        removeOrphanPartsLocked()
        started = true
        publishLocked()
        scheduleLocked()
    }

    /** Call when a setting changed (Wi-Fi only, maximum downloads). */
    suspend fun settingsChanged() = networkChanged()

    /**
     * Stops (true) or allows (false) starting new transfers. While stopped, running transfers are put back
     * in the queue. Used when the system ends the background service for a while.
     */
    suspend fun suspendWork(suspended: Boolean) = lock.withLock {
        schedulingEnabled = !suspended
        if (suspended) {
            for (rec in records.values.toList()) {
                if (rec.state == EngineState.Connecting || rec.state == EngineState.Running) {
                    intents[rec.id] = StopIntent.Requeue
                    stopJobLocked(rec.id)
                }
            }
        } else {
            scheduleLocked()
        }
        Unit
    }

    /** Suspends until nothing is working or waiting to start by itself. Used by the resume worker. */
    suspend fun awaitIdle() {
        items.first { list -> list.none { it.download.state.isBusy } }
    }

    // ------------------------------------------------------------------ commands

    suspend fun enqueue(request: EngineRequest): EnqueueResult = lock.withLock {
        val valid = UrlValidator.validate(request.url) as? UrlValidation.Valid
            ?: return@withLock EnqueueResult.Rejected("This is not a valid web address.")
        val key = UrlKey.of(valid.url)
        records.values.firstOrNull { !it.state.isTerminal && it.urlKey == key }?.let {
            return@withLock EnqueueResult.Duplicate(it.id)
        }
        val now = nowMs()
        val draft = EngineDownload(
            id = 0, url = valid.url, urlKey = key, fileName = request.fileName, mimeType = request.mimeType,
            subfolder = request.subfolder, state = EngineState.Queued, downloadedBytes = 0,
            totalBytes = request.expectedSize?.takeIf { it > 0 } ?: -1, etag = null, lastModified = null,
            acceptsRanges = null, partName = "", finalUri = null, errorMessage = null, retryCount = 0,
            nextRetryAtMs = 0, insecure = request.insecure, notified = false, createdAtMs = now, updatedAtMs = now,
        )
        val id = store.insert(draft)
        val saved = draft.copy(id = id, partName = "dl-$id.part")
        store.update(saved)
        records[id] = saved
        publishLocked()
        scheduleLocked()
        EnqueueResult.Added(id)
    }

    /** Real pause: the transfer stops, the partial file stays, and the slot is freed. */
    suspend fun pause(id: Long) = lock.withLock {
        val rec = records[id] ?: return@withLock
        when {
            rec.state == EngineState.Connecting || rec.state == EngineState.Running -> {
                intents[id] = StopIntent.Pause
                stopJobLocked(id)
            }
            rec.state == EngineState.Queued || rec.state == EngineState.WaitingToRetry || rec.state == EngineState.WaitingForNetwork -> {
                cancelTimerLocked(id)
                putLocked(rec.copy(state = EngineState.Paused, errorMessage = null))
            }
            else -> Unit
        }
    }

    suspend fun resume(id: Long) = lock.withLock {
        val rec = records[id] ?: return@withLock
        if (rec.state == EngineState.Paused) {
            putLocked(rec.copy(state = EngineState.Queued, retryCount = 0, errorMessage = null))
            scheduleLocked()
        }
    }

    suspend fun cancel(id: Long) = lock.withLock {
        val rec = records[id] ?: return@withLock
        when {
            rec.state.isTerminal || rec.state == EngineState.Verifying -> Unit // too late: it is finishing
            rec.state == EngineState.Connecting || rec.state == EngineState.Running -> {
                intents[id] = StopIntent.Cancel
                stopJobLocked(id)
            }
            else -> {
                cancelTimerLocked(id)
                tempFiles(rec).deleteAll()
                putLocked(rec.copy(state = EngineState.Cancelled, downloadedBytes = 0, errorMessage = null))
                scheduleLocked()
            }
        }
    }

    /** Failed or cancelled: queue it again. A kept partial file is continued; a deleted one starts from zero. */
    suspend fun retry(id: Long) = lock.withLock {
        val rec = records[id] ?: return@withLock
        if (rec.state == EngineState.Failed || rec.state == EngineState.Cancelled) {
            putLocked(rec.copy(state = EngineState.Queued, retryCount = 0, errorMessage = null, notified = false, downloadedBytes = partLength(rec)))
            scheduleLocked()
        }
    }

    /** Removes a finished entry from the list. The file itself is not touched. */
    suspend fun remove(id: Long) = lock.withLock {
        val rec = records[id] ?: return@withLock
        if (!rec.state.isTerminal) return@withLock
        records.remove(id)
        store.delete(id)
        publishLocked()
    }

    /** Deletes the finished file and the entry. Returns false when the file could not be removed. */
    suspend fun deleteFile(id: Long): Boolean = lock.withLock {
        val rec = records[id] ?: return@withLock true
        if (rec.state != EngineState.Completed) return@withLock false
        val ok = rec.finalUri?.let { storage.deletePublished(it) } ?: true
        records.remove(id)
        store.delete(id)
        publishLocked()
        ok
    }

    // ------------------------------------------------------------------ scheduling

    private suspend fun networkChanged() = lock.withLock {
        if (!started) return@withLock
        if (usable()) {
            for (rec in records.values.toList()) {
                if (rec.state == EngineState.WaitingForNetwork) putLocked(rec.copy(state = EngineState.Queued, errorMessage = null))
            }
        } else {
            for (rec in records.values.toList()) {
                when {
                    rec.state == EngineState.Connecting || rec.state == EngineState.Running -> {
                        intents[rec.id] = StopIntent.NetworkLoss
                        stopJobLocked(rec.id)
                    }
                    rec.state == EngineState.WaitingToRetry -> {
                        cancelTimerLocked(rec.id)
                        putLocked(rec.copy(state = EngineState.WaitingForNetwork, errorMessage = waitMessage()))
                    }
                }
            }
        }
        scheduleLocked()
    }

    private fun usable(): Boolean {
        val s = network.status.value
        return s.online && !(settings.wifiOnly && s.metered)
    }

    private fun waitMessage(): String =
        if (network.status.value.online) "Waiting for Wi-Fi." else "Waiting for a network connection."

    private suspend fun scheduleLocked() {
        if (!started || !schedulingEnabled) return
        val usable = usable()
        var free = settings.maxConcurrent.coerceAtLeast(1) - records.values.count { it.state.isActive }
        val queued = records.values.filter { it.state == EngineState.Queued }.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        for (rec in queued) {
            if (!usable) {
                putLocked(rec.copy(state = EngineState.WaitingForNetwork, errorMessage = waitMessage()))
                continue
            }
            if (free <= 0) break
            free--
            putLocked(rec.copy(state = EngineState.Connecting, errorMessage = null))
            launchWorkerLocked(rec.id)
        }
    }

    private fun launchWorkerLocked(id: Long) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var outcome: Outcome = Outcome.Gone
            try {
                outcome = transfer(id)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stopped(id) }
                throw e
            } catch (e: DownloadRefused) {
                outcome = Outcome.Fail(e.message ?: "The download was refused.")
            } catch (e: Exception) {
                outcome = Outcome.Fail("The download failed: ${e.message ?: e.javaClass.simpleName}.", keepPart = true)
            }
            withContext(NonCancellable) { finished(id, outcome) }
        }
        jobs[id] = job
        job.start()
    }

    private fun stopJobLocked(id: Long) {
        jobs[id]?.cancel()
        responses[id]?.cancelAll()
    }

    private fun startTimerLocked(id: Long, delayMs: Long) {
        cancelTimerLocked(id)
        timers[id] = scope.launch {
            delay(delayMs)
            lock.withLock {
                timers.remove(id)
                val cur = records[id]
                if (cur != null && cur.state == EngineState.WaitingToRetry) {
                    putLocked(cur.copy(state = EngineState.Queued, errorMessage = null))
                    scheduleLocked()
                }
            }
        }
    }

    private fun cancelTimerLocked(id: Long) {
        timers.remove(id)?.cancel()
    }

    // ------------------------------------------------------------------ one transfer

    private suspend fun transfer(id: Long): Outcome {
        var rec = snapshot(id) ?: return Outcome.Gone
        val tmp = tempFiles(rec)
        val part = tmp.part
        part.parentFile?.mkdirs()
        var restarts = 0
        var allowResume = true
        val registry = ResponseRegistry().also { responses[id] = it }

        while (true) {
            // A segmented download that was interrupted continues from its manifest, with no extra request.
            if (allowResume && tmp.hasSegmentState()) {
                val finishedPart = rec.totalBytes > 0 && part.exists() && part.length() == rec.totalBytes
                val manifest = if (finishedPart) null else SegmentManifestFile.read(tmp.manifest)
                if (manifest != null && manifest.compatibleWith(rec.totalBytes, rec.etag, rec.lastModified)) {
                    if (part.exists()) part.delete()
                    val outcome = runSegmented(id, rec, manifest, tmp, startUrl = rec.url, registry = registry)
                    when (outcome) {
                        Outcome.RestartFresh -> {
                            if (restarts >= 1) return Outcome.Fail("The file kept changing on the server. Try again later.")
                            restarts++
                            allowResume = false
                            rec = snapshot(id) ?: return Outcome.Gone
                            continue
                        }
                        Outcome.FallbackSingle -> {
                            allowResume = false
                            rec = snapshot(id) ?: return Outcome.Gone
                            continue
                        }
                        else -> return outcome
                    }
                }
                // Nothing usable (damaged or outdated manifest): whatever it left behind is removed. A finished merged file stays.
                tmp.deleteSegmentArtifacts()
            }

            val plan = if (allowResume) ResumePlanner.plan(part.length(), rec) else ResumePlan.Fresh(null)
            if (plan is ResumePlan.Fresh) {
                tmp.deleteAll()
                rec = update(id) { it.copy(downloadedBytes = 0) } ?: return Outcome.Gone
            }
            if (plan is ResumePlan.Complete) return complete(id, part, expected = rec.totalBytes)
            val offset = (plan as? ResumePlan.Resume)?.offset ?: 0L

            val opened = try {
                session.open(rec.url) { url ->
                    val b = TransportRequest.Builder(url)
                        .addRequestHeader("User-Agent", userAgent)
                        .addRequestHeader("Accept-Encoding", "identity")
                        .addRequestHeader("Accept", ACCEPT_ANY)
                    if (plan is ResumePlan.Resume) {
                        b.addRequestHeader("Range", "bytes=$offset-")
                        plan.validator?.let { b.addRequestHeader("If-Range", it) }
                    }
                    b.build()
                }
            } catch (e: DownloadRefused) {
                return Outcome.Fail(e.message ?: "The download was refused.")
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                return Outcome.Retry(connectionMessage(e))
            }

            val response = opened.response
            registry.add(response)
            val outcome = try {
                coroutineContext.ensureActive()
                handleResponse(id, rec, response, part, plan, offset, opened.finalUrl, tmp, registry)
            } finally {
                registry.remove(response)
                response.close()
            }

            when (outcome) {
                Outcome.RestartFresh -> {
                    if (restarts >= 1) return Outcome.Fail("The file kept changing on the server. Try again later.")
                    restarts++
                    allowResume = false
                    rec = snapshot(id) ?: return Outcome.Gone
                    continue
                }
                Outcome.FallbackSingle -> {
                    allowResume = false
                    rec = snapshot(id) ?: return Outcome.Gone
                    continue
                }
                else -> return outcome
            }
        }
    }

    /**
     * The segmented path. [manifest] is the plan, fresh or loaded from disk; the transfer continues from its checkpoints,
     * the number of connections follows the measured speed and the network, and when every segment is in, they are merged
     * into the partial file and published like any other download.
     */
    private suspend fun runSegmented(
        id: Long,
        rec: EngineDownload,
        manifest: SegmentManifest,
        tmp: TempFiles,
        startUrl: String,
        registry: ResponseRegistry,
    ): Outcome {
        val free = storage.freeBytes()
        // Room for what is still missing, plus one segment: the merge never needs more than that on top.
        if (free >= 0 && manifest.total - manifest.downloadedBytes + manifest.segmentSize > free) {
            return Outcome.Fail("Not enough free storage space.", keepPart = true)
        }
        val ceiling = connectionCeiling()
        update(id) {
            it.copy(
                state = EngineState.Running, totalBytes = manifest.total, etag = manifest.validator.etag,
                lastModified = manifest.validator.lastModified, acceptsRanges = true,
                downloadedBytes = manifest.downloadedBytes, errorMessage = null,
            )
        } ?: return Outcome.Gone

        val downloader = SegmentedDownloader(
            session = session, retryPolicy = retryPolicy, config = segmentation, userAgent = userAgent, nowMs = nowMs,
            progressIntervalMs = progressIntervalMs,
            tuner = AdaptiveConnections(initial = ConnectionPolicy.initial(ceiling), ceiling = ceiling),
            ceiling = ::connectionCeiling, registry = registry,
            onProgress = { bytes -> progress(id, bytes) },
            onConnections = { n -> connections[id] = n },
            onMerging = {
                connections.remove(id)
                update(id) { it.copy(state = EngineState.Verifying, downloadedBytes = manifest.total) }
            },
        )
        return when (val result = downloader.run(tmp, manifest, rec.url, startUrl)) {
            SegmentedResult.Complete -> complete(id, tmp.part, expected = manifest.total)
            is SegmentedResult.Retry -> Outcome.Retry(result.message, result.retryAfterMs)
            is SegmentedResult.Fail -> Outcome.Fail(result.message, result.keepFiles)
            is SegmentedResult.Changed -> {
                tmp.deleteAll()
                Outcome.RestartFresh
            }
            is SegmentedResult.Unsupported -> {
                segmentationOff.add(id)
                tmp.deleteAll()
                Outcome.FallbackSingle
            }
        }
    }

    private suspend fun handleResponse(
        id: Long,
        rec: EngineDownload,
        response: TransportResponse,
        part: File,
        plan: ResumePlan,
        offset: Long,
        finalUrl: String,
        tmp: TempFiles,
        registry: ResponseRegistry,
    ): Outcome {
        val code = response.code
        val acceptsRanges = HttpHeaders.acceptsRanges(response.header("Accept-Ranges"))
        val etag = response.header("ETag")?.takeIf { it.isNotBlank() }
        val lastModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() }

        return when {
            code == 206 && plan is ResumePlan.Resume -> {
                val range = HttpHeaders.parseContentRange(response.header("Content-Range"))
                    ?: return Outcome.RestartFresh
                if (range.start != offset) return Outcome.RestartFresh
                if (rec.totalBytes > 0 && range.total != null && range.total != rec.totalBytes) return Outcome.RestartFresh
                val total = range.total ?: rec.totalBytes
                stream(id, response, part, startOffset = offset, total = total, etag = etag ?: rec.etag, lastModified = lastModified ?: rec.lastModified, acceptsRanges = true)
            }
            code == 200 -> {
                // A fresh download, or the server ignored Range / the file changed (If-Range): start from zero.
                val length = HttpHeaders.contentLength(response.header("Content-Length")) ?: -1L
                val total = if (length > 0) length else -1L
                val validator = Validator(etag, lastModified)
                val decision = SegmentationPolicy.evaluate(
                    enabled = settings.segmentedDownloads && id !in segmentationOff, code = code, total = total,
                    acceptRanges = response.header("Accept-Ranges"), contentEncoding = response.header("Content-Encoding"),
                    validator = validator, connections = connectionCeiling(), config = segmentation,
                )
                if (decision == SegmentationPolicy.Decision.Use) {
                    // The server announced everything needed. Drop this plain answer and fetch ranges instead;
                    // every ranged answer is checked before its bytes are used, and any doubt falls back to this path.
                    registry.remove(response)
                    response.cancel()
                    val size = SegmentPlanner.segmentSize(total, connectionCeiling(), segmentation)
                    val manifest = SegmentManifest.fresh(total, size, validator)
                    runSegmented(id, rec, manifest, tmp, startUrl = finalUrl, registry = registry)
                } else {
                    stream(id, response, part, startOffset = 0, total = total, etag = etag, lastModified = lastModified, acceptsRanges = acceptsRanges)
                }
            }
            code == 416 && plan is ResumePlan.Resume -> {
                val total = HttpHeaders.parseUnsatisfiedTotal(response.header("Content-Range"))
                if (total != null && total == offset) complete(id, part, expected = total) else Outcome.RestartFresh
            }
            retryPolicy.isRetryableStatus(code) -> Outcome.Retry(
                StatusMapper.httpMessage(code),
                HttpHeaders.retryAfterMs(response.header("Retry-After")),
            )
            else -> Outcome.Fail(StatusMapper.httpMessage(code))
        }
    }

    private suspend fun stream(
        id: Long,
        response: TransportResponse,
        part: File,
        startOffset: Long,
        total: Long,
        etag: String?,
        lastModified: String?,
        acceptsRanges: Boolean?,
    ): Outcome {
        val free = storage.freeBytes()
        if (total > 0 && free >= 0 && total - startOffset > free) {
            return Outcome.Fail("Not enough free storage space.", keepPart = true)
        }
        update(id) {
            it.copy(
                state = EngineState.Running, totalBytes = if (total > 0) total else it.totalBytes,
                etag = etag, lastModified = lastModified, acceptsRanges = acceptsRanges ?: it.acceptsRanges,
                downloadedBytes = startOffset, errorMessage = null,
            )
        } ?: return Outcome.Gone

        val written = try {
            copyToPart(response, part, startOffset) { bytes -> progress(id, bytes) }
        } catch (e: NetworkReadException) {
            coroutineContext.ensureActive() // a pause or cancel also surfaces as a read error
            return Outcome.Retry(connectionMessage(e.cause as? IOException))
        } catch (e: StorageWriteException) {
            val full = e.cause?.message?.contains("space", ignoreCase = true) == true
            return Outcome.Fail(
                if (full) "Not enough free storage space." else "The file could not be saved on this device.",
                keepPart = true,
            )
        }

        if (total > 0 && written < total) return Outcome.Retry("The connection ended before the whole file arrived.")
        if (total > 0 && written > total) {
            part.delete()
            return Outcome.RestartFresh
        }
        return complete(id, part, expected = total)
    }

    private suspend fun copyToPart(
        response: TransportResponse,
        part: File,
        startOffset: Long,
        onBytes: suspend (Long) -> Unit,
    ): Long {
        val input = response.body
        return RandomAccessFile(part, "rw").use { raf ->
            try {
                raf.setLength(startOffset)
                raf.seek(startOffset)
            } catch (e: IOException) {
                throw StorageWriteException(e)
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var total = startOffset
            var lastTick = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    throw NetworkReadException(e)
                }
                if (n < 0) break
                try {
                    raf.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw StorageWriteException(e)
                }
                total += n
                val now = nowMs()
                if (now - lastTick >= progressIntervalMs) {
                    lastTick = now
                    onBytes(total)
                }
            }
            onBytes(total)
            total
        }
    }

    /** All bytes are in [part]: validate the size, publish, mark completed. */
    private suspend fun complete(id: Long, part: File, expected: Long): Outcome {
        val size = part.length()
        if (expected > 0 && size != expected) {
            return if (size < expected) {
                Outcome.Retry("The file is incomplete.")
            } else {
                part.delete()
                Outcome.RestartFresh
            }
        }
        val rec = update(id) { it.copy(state = EngineState.Verifying, downloadedBytes = size, totalBytes = if (expected > 0) expected else size) }
            ?: return Outcome.Gone
        val published = try {
            storage.publish(part, rec.fileName, rec.mimeType, rec.subfolder)
        } catch (e: IOException) {
            return Outcome.Fail("The finished file could not be saved to your Downloads folder.", keepPart = true)
        }
        tempFiles(rec).deleteSegmentArtifacts()
        update(id) { it.copy(state = EngineState.Completed, finalUri = published.uri, errorMessage = null, retryCount = 0) }
        return Outcome.Done
    }

    private suspend fun progress(id: Long, bytes: Long) {
        lock.withLock {
            val cur = records[id] ?: return@withLock
            val now = nowMs()
            val next = cur.copy(state = EngineState.Running, downloadedBytes = bytes, updatedAtMs = now)
            records[id] = next
            estimators.getOrPut(id) { SpeedEstimator() }.record(bytes, now)
            val last = lastPersistAt[id] ?: 0L
            if (now - last >= persistIntervalMs) {
                lastPersistAt[id] = now
                store.update(next)
            }
            publishLocked()
        }
    }

    // ------------------------------------------------------------------ ending a transfer

    /** The transfer coroutine was cancelled: apply what the caller wanted (pause, cancel ...). */
    private suspend fun stopped(id: Long) = lock.withLock {
        jobs.remove(id)
        responses.remove(id)
        connections.remove(id)
        estimators.remove(id)
        val intent = intents.remove(id)
        val rec = records[id] ?: return@withLock
        val length = partLength(rec)
        when (intent) {
            StopIntent.Pause -> putLocked(rec.copy(state = EngineState.Paused, downloadedBytes = length, errorMessage = null))
            StopIntent.Cancel -> {
                tempFiles(rec).deleteAll()
                putLocked(rec.copy(state = EngineState.Cancelled, downloadedBytes = 0, errorMessage = null))
            }
            StopIntent.NetworkLoss -> {
                val ok = usable()
                putLocked(
                    rec.copy(
                        state = if (ok) EngineState.Queued else EngineState.WaitingForNetwork,
                        downloadedBytes = length, errorMessage = if (ok) null else waitMessage(),
                    ),
                )
            }
            StopIntent.Requeue, null -> putLocked(rec.copy(state = EngineState.Queued, downloadedBytes = length))
        }
        scheduleLocked()
    }

    private suspend fun finished(id: Long, outcome: Outcome) = lock.withLock {
        jobs.remove(id)
        responses.remove(id)
        connections.remove(id)
        segmentationOff.remove(id)
        estimators.remove(id)
        intents.remove(id)
        if (outcome == Outcome.Done || outcome is Outcome.Fail || outcome == Outcome.RestartFresh || outcome == Outcome.FallbackSingle) highWater.remove(id)
        val rec = records[id]
        if (rec != null) {
            when (outcome) {
                Outcome.Gone, Outcome.Done -> Unit
                Outcome.FallbackSingle -> failLocked(rec, "The server does not support partial downloads properly. Try again.", keepPart = false)
                is Outcome.Retry -> retryLocked(rec, outcome)
                is Outcome.Fail -> failLocked(rec, outcome.message, outcome.keepPart)
                Outcome.RestartFresh -> failLocked(rec, "The file changed on the server. Try again.", keepPart = false)
            }
            if (outcome == Outcome.Done) notifyLocked(records[id])
        }
        scheduleLocked()
    }

    private suspend fun retryLocked(rec: EngineDownload, retry: Outcome.Retry) {
        val length = partLength(rec)
        if (!usable()) {
            putLocked(rec.copy(state = EngineState.WaitingForNetwork, downloadedBytes = length, errorMessage = waitMessage()))
            return
        }
        // Reaching new ground means the connection works: start counting again. Reaching the same point as
        // before (for example a server that cuts every transfer at the same place and cannot resume) does not.
        val progressed = length > (highWater[rec.id] ?: 0L)
        if (progressed) highWater[rec.id] = length
        val attempt = if (progressed) 1 else rec.retryCount + 1
        if (attempt > retryPolicy.maxRetries) {
            failLocked(rec.copy(downloadedBytes = length), "${retry.message} Gave up after ${retryPolicy.maxRetries} tries.", keepPart = true)
            return
        }
        val delayMs = retryPolicy.delayMs(attempt, retry.retryAfterMs)
        putLocked(
            rec.copy(
                state = EngineState.WaitingToRetry, retryCount = attempt, downloadedBytes = length,
                nextRetryAtMs = nowMs() + delayMs, errorMessage = "Connection problem. Trying again automatically.",
            ),
        )
        startTimerLocked(rec.id, delayMs)
    }

    private suspend fun failLocked(rec: EngineDownload, message: String, keepPart: Boolean) {
        if (!keepPart) tempFiles(rec).deleteAll()
        val failed = rec.copy(
            state = EngineState.Failed, errorMessage = message,
            downloadedBytes = if (keepPart) partLength(rec) else 0,
        )
        putLocked(failed)
        notifyLocked(failed)
    }

    private suspend fun notifyLocked(rec: EngineDownload?) {
        if (rec == null || rec.notified) return
        val marked = rec.copy(notified = true)
        putLocked(marked)
        try {
            onFinished(marked)
        } catch (_: Exception) {
            // A notification problem must never affect a download.
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun snapshot(id: Long): EngineDownload? = lock.withLock { records[id] }

    private suspend fun update(id: Long, change: (EngineDownload) -> EngineDownload): EngineDownload? = lock.withLock {
        val cur = records[id] ?: return@withLock null
        val next = change(cur).copy(updatedAtMs = nowMs())
        putLocked(next)
        next
    }

    private suspend fun putLocked(rec: EngineDownload) {
        records[rec.id] = rec
        store.update(rec)
        publishLocked()
    }

    private fun publishLocked() {
        val now = nowMs()
        activeCount = records.values.count { it.state.isActive }.coerceAtLeast(1)
        _items.value = records.values
            .sortedWith(compareByDescending<EngineDownload> { it.createdAtMs }.thenByDescending { it.id })
            .map { rec ->
                val speed = if (rec.state == EngineState.Running) {
                    val est = estimators[rec.id]
                    val remaining = if (rec.totalBytes > 0) rec.totalBytes - rec.downloadedBytes else -1L
                    SpeedInfo(est?.bytesPerSecond(now), if (remaining > 0) est?.etaSeconds(remaining, now) else null)
                } else {
                    null
                }
                EngineItem(rec, speed, if (rec.state == EngineState.Running) connections[rec.id] ?: 0 else 0)
            }
    }

    private fun partFile(rec: EngineDownload): File = File(storage.partDir(), rec.partName)

    private fun tempFiles(rec: EngineDownload): TempFiles = TempFiles(storage.partDir(), rec.id, rec.partName)

    /** Bytes that are safely on disk: the checkpoints of a segmented download, otherwise the partial file's length. */
    private fun partLength(rec: EngineDownload): Long {
        val tmp = tempFiles(rec)
        val partLength = if (tmp.part.exists()) tmp.part.length() else 0L
        if (partLength == 0L && tmp.manifest.exists()) SegmentManifestFile.read(tmp.manifest)?.let { return it.downloadedBytes }
        return partLength
    }

    /** The most connections one download may use right now, from the network, the settings and how many downloads share the link. */
    private fun connectionCeiling(): Int =
        if (!settings.segmentedDownloads) 1 else ConnectionPolicy.ceiling(network.status.value, settings.maxConnectionsPerDownload, activeCount)

    /** Removes every temporary file (partial, segment, manifest, merge) that belongs to no unfinished download. */
    private fun removeOrphanPartsLocked() {
        val keep = records.values.filter { !it.state.isTerminal }
        TempFiles.sweep(storage.partDir(), keep.map { it.id }.toSet(), keep.map { it.partName }.toSet())
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** Last in the file on purpose: the source guard tests strip comments with a simple pattern that this text confuses. */
        internal const val ACCEPT_ANY = "*/*"
    }
}
