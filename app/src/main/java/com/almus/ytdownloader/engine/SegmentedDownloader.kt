package com.almus.ytdownloader.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Every response a download has open, so a pause or cancel can abort all of their sockets at once (which wakes blocked reads). */
class ResponseRegistry {
    private val open = ConcurrentHashMap.newKeySet<TransportResponse>()

    fun add(response: TransportResponse) {
        open.add(response)
    }

    fun remove(response: TransportResponse) {
        open.remove(response)
    }

    fun cancelAll() {
        open.toList().forEach { runCatching { it.cancel() } }
    }
}

/** How a segmented transfer ended. */
sealed interface SegmentedResult {
    /** Everything is downloaded, verified and merged into [TempFiles.part]. */
    data object Complete : SegmentedResult

    /** Temporary trouble; the transfer may be tried again later and continues from its checkpoints. */
    data class Retry(val message: String, val retryAfterMs: Long? = null) : SegmentedResult

    data class Fail(val message: String, val keepFiles: Boolean) : SegmentedResult

    /** The file on the server changed: start over. */
    data class Changed(val reason: String) : SegmentedResult

    /** The server does not really support ranges: start over with one connection. */
    data class Unsupported(val reason: String) : SegmentedResult
}

internal fun connectionMessage(e: IOException?): String = when (e) {
    is java.net.UnknownHostException -> "Could not find this website."
    is java.net.SocketTimeoutException -> "The server took too long to answer."
    is javax.net.ssl.SSLException -> "The secure connection (HTTPS) failed."
    else -> "The connection broke while downloading."
}

/**
 * Downloads one file as many ranged requests over several connections, writing each segment to its own file.
 *
 * Safety rules (each is enforced, and each has a test):
 * - A segment's bytes are only written after [RangeValidator] accepted the response headers: a 206 for exactly the
 *   requested range, the same file version, no content coding.
 * - Every request carries If-Range, so a file that changed on the server cannot be mixed with the old bytes.
 * - Each segment's CRC-32 is computed while it is written, saved with the checkpoint and verified again when the
 *   segment is merged. A damaged or missing segment is thrown away and fetched again on its own (recovery).
 * - Nothing is trusted after a restart beyond the last flushed checkpoint recorded in the manifest.
 *
 * The number of connections follows [tuner] and the [ceiling] (network and settings), and may change at any moment:
 * a connection above the target finishes its segment and retires. Nothing here depends on Android.
 */
class SegmentedDownloader(
    private val session: HttpSession,
    private val retryPolicy: RetryPolicy,
    private val config: SegmentationConfig,
    private val userAgent: String,
    private val nowMs: () -> Long,
    private val progressIntervalMs: Long,
    private val tuner: AdaptiveConnections,
    private val ceiling: () -> Int,
    private val registry: ResponseRegistry,
    private val onProgress: suspend (Long) -> Unit,
    private val onConnections: (Int) -> Unit,
    private val onMerging: suspend () -> Unit,
) {
    private class NetworkReadException(cause: IOException) : Exception(cause)
    private class StorageWriteException(cause: IOException) : Exception(cause)

    /** What is on disk for one segment right now: [got] bytes, whose CRC-32 is in [crc]; [checkpointed] of them are flushed and recorded. Updated in place, so a broken connection loses nothing. */
    private class Progress(var got: Long, var checkpointed: Long, val crc: CRC32)

    private sealed interface Step {
        data object Done : Step
        class Stop(val result: SegmentedResult) : Step
    }

    /** State of one pass of downloading (everything between "start workers" and "all segments complete or failed"). */
    private class Pass(val files: TempFiles, var manifest: SegmentManifest, val url: String, startUrl: String) {
        val lock = Mutex()
        val pending = ArrayDeque<Int>()
        val inFlight = HashSet<Int>()
        val downloaded = AtomicLong()
        val active = AtomicInteger()

        @Volatile var failure: SegmentedResult? = null

        @Volatile var resolvedUrl: String = startUrl

        /** No new request before this time (the server said "slow down"). */
        @Volatile var quietUntilMs = 0L
    }

    /**
     * Runs the transfer to the end: download, check, merge. [manifest] is the plan (fresh or loaded from disk);
     * [startUrl] is where requests go first (the already resolved address after redirects, or [url] itself).
     */
    suspend fun run(files: TempFiles, manifest: SegmentManifest, url: String, startUrl: String = url): SegmentedResult {
        var current = manifest
        val recoveries = IntArray(manifest.segments.size)
        while (true) {
            val pass = Pass(files, current, url, startUrl)
            val failed = download(pass, recoveries)
            if (failed != null) return failed
            onMerging()
            val job = coroutineContext[Job]
            val merged = try {
                withContext(Dispatchers.IO) {
                    SegmentMerger.merge(files, pass.manifest, { m -> pass.manifest = m; SegmentManifestFile.write(files.manifest, m) }, { job?.isActive != false })
                }
            } catch (e: SegmentMerger.MergeInterrupted) {
                throw CancellationException("merge interrupted")
            } catch (e: IOException) {
                return SegmentedResult.Fail(storageMessage(e), keepFiles = true)
            }
            when (merged) {
                MergeResult.Merged -> return SegmentedResult.Complete
                is MergeResult.Unrecoverable -> return SegmentedResult.Fail("The downloaded data could not be put together. Try again.", keepFiles = false)
                is MergeResult.Corrupt -> {
                    if (++recoveries[merged.index] > config.maxSegmentRecoveries) {
                        return SegmentedResult.Fail("Part of the file kept arriving damaged. Try again later.", keepFiles = false)
                    }
                    current = resetSegment(files, pass.manifest, merged.index)
                }
            }
        }
    }

    private fun resetSegment(files: TempFiles, manifest: SegmentManifest, index: Int): SegmentManifest {
        files.segment(index).delete()
        val next = manifest.withSegment(SegmentState(manifest.segments[index].segment, 0, 0))
        SegmentManifestFile.write(files.manifest, next)
        return next
    }

    // ------------------------------------------------------------------ one pass: workers and the controller

    /** Returns null when every segment is complete, otherwise why the pass stopped. */
    private suspend fun download(pass: Pass, recoveries: IntArray): SegmentedResult? = coroutineScope {
        val scope = this
        val signal = Channel<Unit>(Channel.CONFLATED)
        prepare(pass, recoveries)?.let { return@coroutineScope it }
        var lastReport = 0L

        while (true) {
            pass.failure?.let {
                registry.cancelAll()
                coroutineContext.cancelChildren()
                return@coroutineScope it
            }
            val remaining = pass.lock.withLock { pass.pending.size + pass.inFlight.size }
            if (remaining == 0 && pass.active.get() == 0) break

            tuner.setCeiling(ceiling())
            val want = minOf(tuner.target, remaining).coerceAtLeast(1)
            while (pass.active.get() < want && pass.lock.withLock { pass.pending.isNotEmpty() }) {
                pass.active.incrementAndGet()
                scope.launch(Dispatchers.IO) { worker(pass, recoveries, signal) }
            }

            val now = nowMs()
            if (now - lastReport >= progressIntervalMs) {
                lastReport = now
                val bytes = pass.downloaded.get()
                if (remaining >= tuner.target) tuner.onProgress(bytes, now)
                onConnections(pass.active.get())
                onProgress(bytes)
            }
            withTimeoutOrNull(POLL_MS) { signal.receive() }
        }
        onProgress(pass.downloaded.get())
        onConnections(0)
        null
    }

    /** Looks at what is on disk and queues what is still missing. Anything that does not match the manifest is dropped here. */
    private suspend fun prepare(pass: Pass, recoveries: IntArray): SegmentedResult? = withContext(Dispatchers.IO) {
        val files = pass.files
        var manifest = pass.manifest
        var done = 0L
        for ((i, state) in manifest.segments.withIndex()) {
            if (i < manifest.mergedCount) {
                done += state.segment.length
                continue
            }
            val file = files.segment(i)
            val length = if (file.isFile) file.length() else -1L
            var kept = state
            if (state.complete) {
                if (length != state.segment.length) kept = reset(files, state, recoveries, i)
            } else if (state.doneBytes > 0 && length < state.doneBytes) {
                kept = reset(files, state, recoveries, i)
            }
            if (kept !== state) {
                if (recoveries[i] > config.maxSegmentRecoveries) {
                    return@withContext SegmentedResult.Fail("Part of the file kept arriving damaged. Try again later.", keepFiles = false)
                }
                manifest = manifest.withSegment(kept)
            }
            done += kept.doneBytes
            if (!kept.complete) pass.pending.addLast(i)
        }
        pass.manifest = manifest
        SegmentManifestFile.write(files.manifest, manifest)
        pass.downloaded.set(done)
        null
    }

    private fun reset(files: TempFiles, state: SegmentState, recoveries: IntArray, index: Int): SegmentState {
        files.segment(index).delete()
        recoveries[index]++
        return SegmentState(state.segment, 0, 0)
    }

    private suspend fun worker(pass: Pass, recoveries: IntArray, signal: Channel<Unit>) {
        var retired = false
        try {
            while (true) {
                val a = pass.active.get()
                if (a > tuner.target && a > 1 && pass.active.compareAndSet(a, a - 1)) {
                    retired = true
                    break
                }
                val index = pass.lock.withLock { pass.pending.removeFirstOrNull()?.also { pass.inFlight.add(it) } } ?: break
                val step = try {
                    fetch(pass, index, recoveries)
                } finally {
                    pass.lock.withLock { pass.inFlight.remove(index) }
                }
                signal.trySend(Unit)
                if (step is Step.Stop) {
                    if (pass.failure == null) pass.failure = step.result
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (pass.failure == null) pass.failure = SegmentedResult.Fail("The download failed: ${e.message ?: e.javaClass.simpleName}.", keepFiles = true)
        } finally {
            if (!retired) pass.active.decrementAndGet()
            signal.trySend(Unit)
        }
    }

    // ------------------------------------------------------------------ one segment

    private suspend fun fetch(pass: Pass, index: Int, recoveries: IntArray): Step {
        val files = pass.files
        val seg = pass.manifest.segments[index].segment
        val file = files.segment(index)
        val validator = pass.manifest.validator
        val total = pass.manifest.total

        // Re-establish exactly what was checkpointed: cut the file back to it and check its CRC-32.
        val progress = Progress(0, 0, CRC32())
        val crc = progress.crc
        val state = pass.manifest.segments[index]
        if (state.doneBytes > 0) {
            val ok = withContext(Dispatchers.IO) { verifyPrefix(file, state.doneBytes, state.crc, crc) }
            if (ok) {
                progress.got = state.doneBytes
                progress.checkpointed = state.doneBytes
            } else {
                // Damaged since it was checkpointed: automatic recovery, only this segment starts again.
                file.delete()
                crc.reset()
                pass.downloaded.addAndGet(-state.doneBytes)
                if (++recoveries[index] > config.maxSegmentRecoveries) {
                    return Step.Stop(SegmentedResult.Fail("Part of the file kept arriving damaged. Try again later.", keepFiles = false))
                }
                saveCheckpoint(pass, index, 0, 0)
            }
        }

        var attempts = 0
        while (progress.got < seg.length) {
            coroutineContext.ensureActive()
            waitQuiet(pass)
            val before = progress.got
            var retryAfter: Long? = null
            var message = "The connection broke while downloading."
            var stop: Step.Stop? = null
            var opened: HttpSession.Opened? = null
            try {
                opened = open(pass, seg.start + progress.got, seg.endInclusive, validator)
                val response = opened.response
                registry.add(response)
                when (val check = RangeValidator.check(response.code, response::header, seg.start + progress.got, seg.endInclusive, total, validator, retryPolicy)) {
                    RangeCheck.Ok -> {
                        if (response.code == 206) pass.resolvedUrl = opened.finalUrl
                        streamSegment(pass, index, response, file, seg.length, progress)
                        if (progress.got < seg.length) message = "The connection ended before the whole part arrived."
                    }
                    is RangeCheck.Retry -> {
                        message = check.message
                        retryAfter = check.retryAfterMs
                        if (check.throttled) {
                            tuner.onFailure(AdaptiveConnections.Failure.Throttled, nowMs())
                            pass.quietUntilMs = maxOf(pass.quietUntilMs, nowMs() + (check.retryAfterMs ?: retryPolicy.baseDelayMs))
                        }
                    }
                    is RangeCheck.Changed -> stop = Step.Stop(SegmentedResult.Changed(check.reason))
                    is RangeCheck.Unsupported -> stop = Step.Stop(SegmentedResult.Unsupported(check.reason))
                    is RangeCheck.Fatal -> stop = Step.Stop(SegmentedResult.Fail(check.message, keepFiles = false))
                }
            } catch (e: DownloadRefused) {
                stop = Step.Stop(SegmentedResult.Fail(e.message ?: "The download was refused.", keepFiles = false))
            } catch (e: NetworkReadException) {
                coroutineContext.ensureActive()
                message = connectionMessage(e.cause as? IOException)
                tuner.onFailure(AdaptiveConnections.Failure.Connection, nowMs())
            } catch (e: StorageWriteException) {
                val full = e.cause?.message?.contains("space", ignoreCase = true) == true
                stop = Step.Stop(SegmentedResult.Fail(if (full) "Not enough free storage space." else "The file could not be saved on this device.", keepFiles = true))
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                message = connectionMessage(e)
                tuner.onFailure(AdaptiveConnections.Failure.Connection, nowMs())
            } finally {
                opened?.response?.let {
                    registry.remove(it)
                    it.close()
                }
                if (progress.got > progress.checkpointed) {
                    // Also on pause and cancel: keep what arrived. Flushed first, so the record never promises more than the disk has.
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            RandomAccessFile(file, "rw").use { it.fd.sync() }
                            saveCheckpoint(pass, index, progress.got, crc.value)
                            progress.checkpointed = progress.got
                        }
                    }
                }
            }
            stop?.let { return it }
            if (progress.got >= seg.length) break

            attempts = if (progress.got > before) 1 else attempts + 1
            if (attempts > config.maxSegmentAttempts) return Step.Stop(SegmentedResult.Retry(message, retryAfter))
            delay(retryPolicy.delayMs(attempts, retryAfter))
        }

        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "rw").use { it.fd.sync() }
            saveCheckpoint(pass, index, seg.length, crc.value)
        }
        return Step.Done
    }

    private suspend fun waitQuiet(pass: Pass) {
        val wait = pass.quietUntilMs - nowMs()
        if (wait > 0) delay(wait)
    }

    private suspend fun open(pass: Pass, start: Long, end: Long, validator: Validator): HttpSession.Opened {
        fun request(url: String) = TransportRequest.Builder(url)
            .addRequestHeader("User-Agent", userAgent)
            .addRequestHeader("Accept-Encoding", "identity")
            .addRequestHeader("Accept", DownloadEngine.ACCEPT_ANY)
            .addRequestHeader("Range", "bytes=$start-$end")
            .also { b -> validator.ifRange?.let { b.addRequestHeader("If-Range", it) } }
            .ownConnection()
            .build()

        val primary = pass.resolvedUrl
        var opened = session.open(primary) { request(it) }
        // A cached final address (for example a signed link) may have expired: ask the original address once.
        if (primary != pass.url && opened.response.code in 400..499 && opened.response.code != 416 && opened.response.code != 408 && opened.response.code != 429) {
            opened.response.close()
            pass.resolvedUrl = pass.url
            opened = session.open(pass.url) { request(it) }
        }
        return opened
    }

    /** Copies at most the rest of the segment from [response] into [file], keeping [progress] exact at every step. */
    private suspend fun streamSegment(
        pass: Pass,
        index: Int,
        response: TransportResponse,
        file: File,
        length: Long,
        progress: Progress,
    ) {
        val input = response.body
        RandomAccessFile(file, "rw").use { raf ->
            try {
                raf.setLength(progress.got)
                raf.seek(progress.got)
            } catch (e: IOException) {
                throw StorageWriteException(e)
            }
            val buffer = ByteArray(BUFFER_SIZE)
            while (progress.got < length) {
                coroutineContext.ensureActive()
                val n = try {
                    input.read(buffer, 0, minOf(buffer.size.toLong(), length - progress.got).toInt())
                } catch (e: IOException) {
                    throw NetworkReadException(e)
                }
                if (n < 0) break
                try {
                    raf.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw StorageWriteException(e)
                }
                progress.crc.update(buffer, 0, n)
                progress.got += n
                pass.downloaded.addAndGet(n.toLong())
                if (progress.got - progress.checkpointed >= config.checkpointBytes && progress.got < length) {
                    try {
                        raf.fd.sync()
                        withContext(NonCancellable) { saveCheckpoint(pass, index, progress.got, progress.crc.value) }
                    } catch (e: IOException) {
                        throw StorageWriteException(e)
                    }
                    progress.checkpointed = progress.got
                }
            }
        }
    }

    private suspend fun saveCheckpoint(pass: Pass, index: Int, done: Long, crc: Long) {
        pass.lock.withLock {
            val state = SegmentState(pass.manifest.segments[index].segment, done, crc)
            pass.manifest = pass.manifest.withSegment(state)
            SegmentManifestFile.write(pass.files.manifest, pass.manifest)
        }
    }

    private fun verifyPrefix(file: File, length: Long, expectedCrc: Long, crc: CRC32): Boolean {
        if (!file.isFile || file.length() < length) return false
        crc.reset()
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(length) // drop whatever was written after the checkpoint
                raf.seek(0)
                val buffer = ByteArray(BUFFER_SIZE)
                var left = length
                while (left > 0) {
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) return false
                    crc.update(buffer, 0, n)
                    left -= n
                }
            }
        } catch (_: IOException) {
            return false
        }
        return crc.value == expectedCrc
    }

    private fun storageMessage(e: IOException): String =
        if (e.message?.contains("space", ignoreCase = true) == true) "Not enough free storage space." else "The file could not be saved on this device."

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val POLL_MS = 50L
    }
}
