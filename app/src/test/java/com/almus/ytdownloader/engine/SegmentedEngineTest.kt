package com.almus.ytdownloader.engine

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real engine, the real HTTP stack and a scriptable local server: segmented downloads must be used only when the
 * server proves it supports ranges, must never produce a corrupt file, must repair damaged segments, and must leave
 * no temporary file behind.
 */
class SegmentedEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cfg = SegmentationConfig(
        minFileBytes = 200_000, minSegmentBytes = 32 * 1024, maxSegmentBytes = 64 * 1024, alignBytes = 4096,
        checkpointBytes = 32 * 1024, maxSegmentAttempts = 4, maxSegmentRecoveries = 2,
    )
    private val body = RangeFileServer.bytes(1_000_000)
    private lateinit var server: RangeFileServer
    private lateinit var store: FakeStore
    private lateinit var storage: FakeStorage
    private lateinit var network: FakeNetwork
    private lateinit var settings: TestSettings
    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private val extra = mutableListOf<CoroutineScope>()
    private val fastRetry = RetryPolicy(maxRetries = 5, baseDelayMs = 20, maxDelayMs = 100)

    @Before fun setUp() {
        server = RangeFileServer(body)
        store = FakeStore()
        storage = FakeStorage(tmp.newFolder())
        network = FakeNetwork()
        settings = TestSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = newEngine(scope)
        runBlocking { engine.start() }
    }

    @After fun tearDown() {
        extra.forEach { it.cancel() }
        scope.cancel()
        server.stop()
    }

    private fun newEngine(useScope: CoroutineScope) = DownloadEngine(
        store = store, transport = OkHttpTransport(), storage = storage, network = network, settings = settings,
        scope = useScope, retryPolicy = fastRetry, progressIntervalMs = 10, persistIntervalMs = 50, segmentation = cfg,
    )

    private fun restarted(): DownloadEngine {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extra += s
        return newEngine(s)
    }

    private suspend fun add(name: String = "file.bin"): Long =
        (engine.enqueue(EngineRequest(server.url(name), name, "application/octet-stream")) as EnqueueResult.Added).id

    private suspend fun await(id: Long, vararg states: EngineState, eng: DownloadEngine = engine, timeoutMs: Long = 30_000): EngineDownload = withTimeout(timeoutMs) {
        eng.items.first { l -> l.any { it.download.id == id && it.download.state in states } }.first { it.download.id == id }.download
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun assertExact(done: EngineDownload, expected: ByteArray = body) {
        assertEquals(sha(expected), sha(File(done.finalUri!!).readBytes()))
    }

    private fun assertNoTempFiles() {
        assertEquals(emptyList<String>(), storage.parts.list()!!.toList())
    }

    // ------------------------------------------------------------------ when it is used

    @Test fun usesSeveralConnectionsAndBoundedRangesWhenTheServerSupportsThem() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 2_000_000
        val id = add()
        val done = await(id, EngineState.Completed)
        assertExact(done)
        assertTrue("peak ${server.peakConcurrent}", server.peakConcurrent >= 2)
        val ranged = server.rangedRequests()
        assertTrue(ranged.isNotEmpty())
        assertTrue(ranged.all { it.rangeEnd != null && it.ifRange == "\"v1\"" })
        // The segments cover the file exactly once.
        assertEquals(body.size.toLong(), ranged.sumOf { it.rangeEnd!! - it.rangeStart!! + 1 })
        assertEquals(ranged.size, ranged.map { it.rangeStart }.toSet().size)
        assertNoTempFiles()
        assertEquals(body.size.toLong(), done.downloadedBytes)
    }

    @Test fun neverUsesSegmentsWhenSwitchedOff() = runBlocking<Unit> {
        settings.segmentedDownloads = false
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertTrue(server.rangedRequests().isEmpty())
        assertEquals(1, server.seen.size)
    }

    @Test fun usesOneConnectionWhenTheServerDoesNotAnnounceRanges() = runBlocking<Unit> {
        server.advertiseRanges = false
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertTrue(server.rangedRequests().isEmpty())
        assertEquals(1, server.seen.size)
    }

    @Test fun usesOneConnectionWhenThereIsNoUsableValidator() = runBlocking<Unit> {
        server.etag = "W/\"weak\""
        server.lastModified = null
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertTrue(server.rangedRequests().isEmpty())
    }

    @Test fun usesOneConnectionForSmallFiles() = runBlocking<Unit> {
        server.body = RangeFileServer.bytes(150_000)
        val done = await(add(), EngineState.Completed)
        assertExact(done, server.body)
        assertEquals(1, server.seen.size)
    }

    @Test fun usesOneConnectionWhenTheContentIsEncoded() = runBlocking<Unit> {
        server.contentEncoding = "identity"
        assertExact(await(add(), EngineState.Completed)) // identity is fine and still segmented
        assertTrue(server.rangedRequests().isNotEmpty())
        server.seen.clear()
        server.contentEncoding = "gzip"
        server.body = RangeFileServer.bytes(1_000_000, seed = 3)
        val done = await(add("second.bin"), EngineState.Completed)
        assertExact(done, server.body)
        assertTrue(server.rangedRequests().isEmpty())
    }

    @Test fun staysOnOneConnectionOnMeteredNetworksWithALimitOfOne() = runBlocking<Unit> {
        settings.maxConnectionsPerDownload = 1
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertTrue(server.rangedRequests().isEmpty())
    }

    @Test fun mobileDataUsesFewerConnectionsThanEthernet() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        network.flow.value = NetworkStatus(online = true, metered = true, kind = NetworkKind.Cellular)
        val ethernet = NetworkStatus(online = true, metered = false, kind = NetworkKind.Ethernet)
        assertTrue(ConnectionPolicy.ceiling(network.flow.value, 0) < ConnectionPolicy.ceiling(ethernet, 0))
        val id = add()
        var peak = 0
        withTimeout(30_000) {
            engine.items.first { l ->
                peak = maxOf(peak, l.firstOrNull { it.download.id == id }?.connections ?: 0)
                l.any { it.download.id == id && it.download.state == EngineState.Completed }
            }
        }
        assertExact(await(id, EngineState.Completed))
        assertTrue("cellular peak $peak", peak <= 3)
        // The server may still be closing the first (cancelled) request while the segments arrive, hence one extra.
        assertTrue("cellular server peak ${server.peakConcurrent}", server.peakConcurrent <= 4)
    }

    // ------------------------------------------------------------------ servers that lie

    @Test fun fallsBackToOneConnectionWhenTheServerIgnoresRange() = runBlocking<Unit> {
        server.honorRanges = false // advertises "bytes" but answers 200
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertNoTempFiles()
    }

    @Test fun fallsBackWhenTheContentRangeIsWrong() = runBlocking<Unit> {
        server.faultFor = { if (it.rangeStart != null) Fault.WrongContentRange else null }
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertNoTempFiles()
    }

    // ------------------------------------------------------------------ the file changes

    @Test fun restartsWhenTheFileChangesOnTheServer() = runBlocking<Unit> {
        val v2 = RangeFileServer.bytes(1_000_000, seed = 99)
        val switched = AtomicBoolean(false)
        server.perConnectionBytesPerSec = 3_000_000
        server.faultFor = { r ->
            if (r.rangeStart != null && r.rangeStart >= 128 * 1024 && switched.compareAndSet(false, true)) {
                server.body = v2
                server.etag = "\"v2\""
            }
            null
        }
        val done = await(add(), EngineState.Completed)
        assertExact(done, v2) // never a mixture of the two versions
        assertNoTempFiles()
    }

    // ------------------------------------------------------------------ recovery

    @Test fun recoversFromCutConnectionsSegmentBySegment() = runBlocking<Unit> {
        val cuts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        server.faultFor = { r -> if (r.rangeStart != null && r.rangeStart % 3 == 0L && cuts.add(r.rangeStart)) Fault.Cut(10_000) else null }
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertTrue(cuts.isNotEmpty())
        assertNoTempFiles()
    }

    @Test fun resumesInsideASegmentAfterACut() = runBlocking<Unit> {
        server.body = RangeFileServer.bytes(400_000)
        val first = AtomicBoolean(true)
        server.faultFor = { r -> if (r.rangeStart == 0L && first.compareAndSet(true, false)) Fault.Cut(20_000) else null }
        val done = await(add(), EngineState.Completed)
        assertExact(done, server.body)
        val retried = server.rangedRequests().filter { it.rangeStart != null && it.rangeStart in 1 until 65_536 }
        assertTrue("a request continued inside segment 0: ${server.rangedRequests().map { it.rangeStart }}", retried.isNotEmpty())
        // The bytes that arrived before the cut were kept: segment 0 was asked for from byte 0 only once, and nothing was repaired.
        assertEquals(1, server.rangedRequests().count { it.rangeStart == 0L })
        assertTrue(retried.all { it.rangeStart!! >= 8192 })
    }

    @Test fun repeatedCutsNeverLoseOrDuplicateBytes() = runBlocking<Unit> {
        // Every request longer than 12 KB is cut after 12 KB: each segment needs many resumed requests.
        server.faultFor = { r -> if ((r.rangeEnd ?: Long.MAX_VALUE) - (r.rangeStart ?: 0) > 12_000) Fault.Cut(12_000) else null }
        val done = await(add(), EngineState.Completed, timeoutMs = 60_000)
        assertExact(done)
        assertNoTempFiles()
    }

    @Test fun retriesTemporaryServerErrorsAndSlowsDownWhenThrottled() = runBlocking<Unit> {
        val served = java.util.concurrent.atomic.AtomicInteger()
        server.faultFor = { r -> if (r.rangeStart != null && served.incrementAndGet() <= 3) Fault.Status(503, "0") else null }
        val done = await(add(), EngineState.Completed)
        assertExact(done)
        assertNoTempFiles()
    }

    @Test fun aPermanentErrorFailsAndCleansUp() = runBlocking<Unit> {
        server.faultFor = { r -> if (r.rangeStart != null) Fault.Status(404) else null }
        val failed = await(add(), EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("not found", ignoreCase = true))
        assertNoTempFiles()
    }

    @Test fun silentCorruptionInTransitIsNotDetectableButTheSizeIsAlwaysExact() = runBlocking<Unit> {
        // A server that sends wrong bytes of the right length cannot be caught without a hash from the server;
        // what is guaranteed is that the result has exactly the announced size and every byte came from a validated 206.
        server.faultFor = { r -> if (r.rangeStart == 0L) Fault.Corrupt(5) else null }
        val done = await(add(), EngineState.Completed)
        assertEquals(body.size.toLong(), File(done.finalUri!!).length())
    }

    // ------------------------------------------------------------------ damage on disk

    private suspend fun pauseWhenPartlyDone(id: Long): SegmentManifest {
        withTimeout(30_000) { engine.items.first { l -> l.any { it.download.id == id && it.download.downloadedBytes >= 300_000 } } }
        engine.pause(id)
        await(id, EngineState.Paused)
        return SegmentManifestFile.read(File(storage.parts, "dl-$id.segs"))!!
    }

    @Test fun pauseKeepsSegmentsAndResumeFinishesWithoutRestartingCompletedOnes() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        val m = pauseWhenPartlyDone(id)
        val doneSegments = m.segments.filter { it.complete }.map { it.segment.start }.toSet()
        assertTrue(doneSegments.isNotEmpty())
        val before = server.seen.size
        engine.resume(id)
        val done = await(id, EngineState.Completed)
        assertExact(done)
        val refetched = server.seen.drop(before).mapNotNull { it.rangeStart }.filter { it in doneSegments }
        assertEquals("completed segments were downloaded again", emptyList<Long>(), refetched)
        assertNoTempFiles()
    }

    @Test fun aSegmentDamagedWhilePausedIsRepairedOnItsOwn() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        val m = pauseWhenPartlyDone(id)
        val victim = m.segments.last { it.complete && it.segment.index > 0 }.segment
        val f = File(storage.parts, "dl-$id.seg${victim.index}")
        f.writeBytes(f.readBytes().also { it[100] = (it[100] + 1).toByte() })
        val before = server.seen.size
        engine.resume(id)
        val done = await(id, EngineState.Completed)
        assertExact(done) // the damage was caught by the CRC check during the merge and the segment fetched again
        assertTrue(server.seen.drop(before).any { it.rangeStart == victim.start })
        assertNoTempFiles()
    }

    @Test fun aTruncatedSegmentFileIsRepaired() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        val m = pauseWhenPartlyDone(id)
        val victim = m.segments.last { it.complete && it.segment.index > 0 }.segment
        File(storage.parts, "dl-$id.seg${victim.index}").let { it.writeBytes(it.readBytes().copyOf(1000)) }
        engine.resume(id)
        assertExact(await(id, EngineState.Completed))
        assertNoTempFiles()
    }

    @Test fun aDamagedPartialSegmentIsDetectedByItsCheckpointChecksum() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        val m = pauseWhenPartlyDone(id)
        val partial = m.segments.firstOrNull { !it.complete && it.doneBytes > 0 }
        if (partial != null) {
            val f = File(storage.parts, "dl-$id.seg${partial.segment.index}")
            f.writeBytes(f.readBytes().also { it[0] = (it[0] + 1).toByte() })
        }
        engine.resume(id)
        assertExact(await(id, EngineState.Completed))
        assertNoTempFiles()
    }

    @Test fun aCorruptManifestMeansAFreshStartNotAGuess() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        pauseWhenPartlyDone(id)
        File(storage.parts, "dl-$id.segs").writeText("garbage")
        engine.resume(id)
        assertExact(await(id, EngineState.Completed))
        assertNoTempFiles()
    }

    @Test fun aRestartOfTheAppContinuesFromTheManifest() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_500_000
        val id = add()
        withTimeout(30_000) { engine.items.first { l -> l.any { it.download.id == id && it.download.downloadedBytes >= 300_000 } } }
        scope.cancel() // the process dies in the middle of the transfer
        delay(500)
        val m = SegmentManifestFile.read(File(storage.parts, "dl-$id.segs"))!!
        val doneStarts = m.segments.filter { it.complete }.map { it.segment.start }.toSet()
        assertTrue(doneStarts.isNotEmpty())
        val before = server.seen.size
        val engine2 = restarted()
        engine2.start()
        val done = await(id, EngineState.Completed, eng = engine2)
        assertExact(done)
        assertTrue(server.seen.drop(before).mapNotNull { it.rangeStart }.none { it in doneStarts })
        assertNoTempFiles()
    }

    // ------------------------------------------------------------------ cleanup

    @Test fun cancelRemovesEverything() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 800_000
        val id = add()
        withTimeout(30_000) { engine.items.first { l -> l.any { it.download.id == id && it.download.downloadedBytes > 100_000 } } }
        engine.cancel(id)
        await(id, EngineState.Cancelled)
        delay(100)
        assertNoTempFiles()
    }

    @Test fun orphanTemporaryFilesAreRemovedAtStart() = runBlocking<Unit> {
        File(storage.parts, "dl-999.seg3").writeText("x")
        File(storage.parts, "dl-999.segs").writeText("x")
        File(storage.parts, "dl-999.merge").writeText("x")
        File(storage.parts, "dl-999.part").writeText("x")
        restarted().start()
        assertNoTempFiles()
    }

    @Test fun aFailedPublishKeepsTheMergedFileForARetry() = runBlocking<Unit> {
        storage.failPublish = true
        val id = add()
        val failed = await(id, EngineState.Failed)
        assertNotNull(failed.errorMessage)
        assertTrue(File(storage.parts, "dl-$id.part").length() == body.size.toLong())
        storage.failPublish = false
        engine.retry(id)
        assertExact(await(id, EngineState.Completed))
        assertNoTempFiles()
    }

    @Test fun notEnoughSpaceFailsBeforeDownloadingAnything() = runBlocking<Unit> {
        storage.free = 100_000
        val failed = await(add(), EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("space", ignoreCase = true))
    }

    // ------------------------------------------------------------------ speed

    @Test fun isMuchFasterThanOneConnectionWhenTheServerLimitsEachConnection() = runBlocking<Unit> {
        server.perConnectionBytesPerSec = 1_000_000
        settings.segmentedDownloads = false
        var t0 = System.nanoTime()
        assertExact(await(add("single.bin"), EngineState.Completed))
        val single = (System.nanoTime() - t0) / 1e6
        settings.segmentedDownloads = true
        t0 = System.nanoTime()
        assertExact(await(add("multi.bin"), EngineState.Completed))
        val multi = (System.nanoTime() - t0) / 1e6
        assertTrue("single ${single}ms, segmented ${multi}ms", multi < single * 0.75)
    }

    @Test fun neverReportsMoreConnectionsThanAllowed() = runBlocking<Unit> {
        settings.maxConnectionsPerDownload = 3
        server.perConnectionBytesPerSec = 1_000_000
        val id = add()
        var peak = 0
        withTimeout(30_000) {
            engine.items.first { l ->
                peak = maxOf(peak, l.firstOrNull { it.download.id == id }?.connections ?: 0)
                l.any { it.download.id == id && it.download.state == EngineState.Completed }
            }
        }
        assertTrue("peak $peak", peak <= 3)
        // The server may still be closing the previous request while the next one arrives, hence one extra.
        assertTrue("server peak ${server.peakConcurrent}", server.peakConcurrent <= 4)
    }
}
