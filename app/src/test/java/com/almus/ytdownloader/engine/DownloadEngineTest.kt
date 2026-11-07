package com.almus.ytdownloader.engine

import java.io.File
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
 * Integration tests: the real engine and the real OkHttp client against a local HTTP server that can
 * support or refuse Range, cut connections, answer with errors and change its file. No network is used.
 */
class DownloadEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val body = FileServer.bytes(300_000)
    private lateinit var server: FileServer
    private lateinit var store: FakeStore
    private lateinit var storage: FakeStorage
    private lateinit var network: FakeNetwork
    private lateinit var settings: TestSettings
    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private val finished = java.util.concurrent.CopyOnWriteArrayList<EngineDownload>()

    private val fastRetry = RetryPolicy(maxRetries = 5, baseDelayMs = 20, maxDelayMs = 100)

    @Before fun setUp() {
        server = FileServer(body)
        store = FakeStore()
        storage = FakeStorage(tmp.newFolder())
        network = FakeNetwork()
        settings = TestSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = newEngine()
        runBlocking { engine.start() }
    }

    private val extraScopes = mutableListOf<CoroutineScope>()

    private fun restartedEngine(): DownloadEngine {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extraScopes += s
        return newEngine(useScope = s)
    }

    @After fun tearDown() {
        extraScopes.forEach { it.cancel() }
        scope.cancel()
        server.stop()
    }

    private fun newEngine(
        retry: RetryPolicy = fastRetry,
        useStore: FakeStore = store,
        useScope: CoroutineScope = scope,
    ) = DownloadEngine(
        store = useStore, transport = OkHttpTransport(), storage = storage, network = network, settings = settings,
        scope = useScope, retryPolicy = retry, progressIntervalMs = 10, persistIntervalMs = 50,
        onFinished = { finished.add(it) },
    )

    private fun request(name: String = "file.bin", fileName: String = "file.bin") =
        EngineRequest(url = server.url(name), fileName = fileName, mimeType = "application/octet-stream")

    private suspend fun add(name: String = "file.bin", fileName: String = name): Long =
        (engine.enqueue(request(name, fileName)) as EnqueueResult.Added).id

    private fun current(id: Long): EngineDownload = engine.items.value.first { it.download.id == id }.download

    private suspend fun await(id: Long, vararg states: EngineState, timeoutMs: Long = 20_000): EngineDownload = withTimeout(timeoutMs) {
        engine.items.first { list -> list.any { it.download.id == id && it.download.state in states } }
            .first { it.download.id == id }.download
    }

    private suspend fun awaitBytes(id: Long, atLeast: Long) = withTimeout(20_000) {
        engine.items.first { list -> list.any { it.download.id == id && it.download.downloadedBytes >= atLeast } }
    }

    /** The finished callback runs just after the state turns Completed, so tests wait for it instead of racing it. */
    private suspend fun awaitFinished(count: Int) = withTimeout(10_000) {
        while (finished.size < count) delay(5)
    }

    private fun partFile(id: Long) = File(storage.parts, "dl-$id.part")

    // ------------------------------------------------------------------ plain download

    @Test fun downloadsAFileAndValidatesIt() = runBlocking<Unit> {
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(body.size.toLong(), done.totalBytes)
        assertEquals(body.size.toLong(), done.downloadedBytes)
        assertFalse(partFile(id).exists())
        assertEquals(1, server.requests.size)
        val headers = server.requests[0]
        assertEquals("identity", headers["accept-encoding"])
        assertTrue(headers["user-agent"]!!.startsWith("ALMUS-Downloader"))
        assertFalse(headers.containsKey("range"))
        assertFalse(headers.containsKey("cookie"))
        awaitFinished(1)
        assertEquals(listOf(id), finished.map { it.id })
        assertEquals(done.finalUri, store.get(id)!!.finalUri)
    }

    @Test fun recordsWhatTheServerSaid() = runBlocking<Unit> {
        val id = add()
        val done = await(id, EngineState.Completed)
        assertEquals("\"v1\"", done.etag)
        assertEquals(true, done.acceptsRanges)
    }

    // ------------------------------------------------------------------ interruptions and Range

    @Test fun resumesWithRangeAfterAnInterruption() = runBlocking<Unit> {
        server.cutOnRequest += 1
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(2, server.requests.size)
        val range = server.requests[1]["range"]!!
        val from = Regex("bytes=(\\d+)-").matchEntire(range)!!.groupValues[1].toLong()
        assertTrue("resumed from $from", from in 1 until body.size)
        assertEquals("\"v1\"", server.requests[1]["if-range"])
        assertEquals(0, done.retryCount)
    }

    @Test fun anInterruptedFileIsNeverReportedAsComplete() = runBlocking<Unit> {
        // No resume support and every transfer is cut at the same place: the file can never be completed.
        server.rangeMode = RangeMode.Refuse
        for (i in 1..20) server.cutOnRequest += i
        val e = newEngine(RetryPolicy(maxRetries = 2, baseDelayMs = 10, maxDelayMs = 20))
        e.start()
        val id = (e.enqueue(request()) as EnqueueResult.Added).id
        val failed = withTimeout(20_000) { e.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Failed } } }
            .first { it.download.id == id }.download
        assertTrue(failed.errorMessage!!.contains("Gave up"))
        assertEquals(emptyList<String>(), storage.public.list()!!.toList())
        assertEquals(3, server.requests.size)
    }

    @Test fun restartsFromZeroWhenTheServerRefusesRanges() = runBlocking<Unit> {
        server.rangeMode = RangeMode.Refuse
        server.cutOnRequest += 1
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(2, server.requests.size)
        assertFalse(server.requests[1].containsKey("range"))
        assertEquals(false, done.acceptsRanges)
    }

    @Test fun restartsFromZeroWhenTheServerIgnoresRange() = runBlocking<Unit> {
        server.rangeMode = RangeMode.Ignore
        server.cutOnRequest += 1
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(2, server.requests.size)
        assertTrue(server.requests[1].containsKey("range")) // it was tried; the 200 answer made the engine start again
    }

    @Test fun restartsFromZeroWhenTheFileChangedOnTheServer() = runBlocking<Unit> {
        val newBody = FileServer.bytes(250_000).reversedArray()
        server.cutOnRequest += 1
        server.beforeRequest = { n ->
            if (n == 2) {
                server.body = newBody
                server.etag = "\"v2\""
            }
        }
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(newBody, File(done.finalUri!!).readBytes())
        assertEquals("\"v1\"", server.requests[1]["if-range"])
        assertEquals("\"v2\"", done.etag)
    }

    // ------------------------------------------------------------------ pause, resume, cancel

    @Test fun pauseKeepsThePartialFileAndResumeContinuesIt() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        awaitBytes(id, 50_000)
        engine.pause(id)
        val paused = await(id, EngineState.Paused)
        val length = partFile(id).length()
        assertTrue("partial $length", length > 0 && length < 600_000)
        assertEquals(length, paused.downloadedBytes)
        delay(200)
        assertEquals("nothing may be written while paused", length, partFile(id).length())

        server.chunkDelayMs = 0
        engine.resume(id)
        val done = await(id, EngineState.Completed)
        assertArrayEquals(server.body, File(done.finalUri!!).readBytes())
        assertEquals("bytes=$length-", server.requests.last()["range"])
    }

    @Test fun pausingAFreesTheSlotForTheNextDownload() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val first = add("a.bin")
        val second = add("b.bin")
        awaitBytes(first, 20_000)
        assertEquals(EngineState.Queued, current(second).state)
        server.chunkDelayMs = 0
        engine.pause(first)
        await(second, EngineState.Completed)
        assertEquals(EngineState.Paused, current(first).state)
    }

    @Test fun cancelDeletesThePartialFile() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        awaitBytes(id, 20_000)
        engine.cancel(id)
        val cancelled = await(id, EngineState.Cancelled)
        assertFalse(partFile(id).exists())
        assertEquals(0L, cancelled.downloadedBytes)
        assertEquals(emptyList<String>(), storage.public.list()!!.toList())
        assertTrue(finished.isEmpty())
    }

    @Test fun aQueuedDownloadCanBeCancelledOrPausedBeforeItStarts() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val first = add("a.bin")
        val second = add("b.bin")
        val third = add("c.bin")
        awaitBytes(first, 10_000)
        engine.cancel(second)
        engine.pause(third)
        assertEquals(EngineState.Cancelled, current(second).state)
        assertEquals(EngineState.Paused, current(third).state)
        server.chunkDelayMs = 0
        await(first, EngineState.Completed)
        assertEquals(EngineState.Paused, current(third).state)
        engine.resume(third)
        await(third, EngineState.Completed)
    }

    // ------------------------------------------------------------------ retry and errors

    @Test fun retriesAfterATemporaryServerError() = runBlocking<Unit> {
        server.retryAfter = "0"
        repeat(2) { server.statusQueue.add(503) }
        val id = add()
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(3, server.requests.size)
        assertEquals(0, done.retryCount)
    }

    @Test fun doesNotRetryAPermanentError() = runBlocking<Unit> {
        server.statusQueue.add(404)
        val id = add()
        val failed = await(id, EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("not found", ignoreCase = true))
        assertEquals(1, server.requests.size)
        assertFalse(partFile(id).exists())
        awaitFinished(1)
        assertEquals(listOf(id), finished.map { it.id })
    }

    @Test fun givesUpAfterTheMaximumNumberOfRetries() = runBlocking<Unit> {
        repeat(10) { server.statusQueue.add(503) }
        val e = newEngine(RetryPolicy(maxRetries = 2, baseDelayMs = 10, maxDelayMs = 20))
        e.start()
        val id = (e.enqueue(request()) as EnqueueResult.Added).id
        val failed = withTimeout(20_000) { e.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Failed } } }
            .first { it.download.id == id }.download
        assertTrue(failed.errorMessage!!.contains("Gave up"))
        assertEquals(3, server.requests.size)
    }

    @Test fun retryContinuesAFailedDownloadFromItsPartialFile() = runBlocking<Unit> {
        // The first transfer is cut after ~100 KB; after that the server keeps answering 503 until the engine gives up.
        server.cutOnRequest += 1
        server.beforeRequest = { n -> if (n == 2) repeat(5) { server.statusQueue.add(503) } }
        val e = newEngine(RetryPolicy(maxRetries = 2, baseDelayMs = 10, maxDelayMs = 20))
        e.start()
        val id = (e.enqueue(request()) as EnqueueResult.Added).id
        withTimeout(20_000) { e.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Failed } } }
        assertTrue(partFile(id).length() > 0)
        server.statusQueue.clear()
        server.beforeRequest = null
        e.retry(id)
        val done = withTimeout(20_000) { e.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertTrue(server.requests.last().containsKey("range"))
    }

    @Test fun aServerThatNeverSucceedsLeavesNoFileBehind() = runBlocking<Unit> {
        server.statusQueue.add(403)
        val id = add()
        await(id, EngineState.Failed)
        assertEquals(emptyList<String>(), storage.public.list()!!.toList())
    }

    @Test fun notEnoughSpaceFailsEarlyAndKeepsNothingPublished() = runBlocking<Unit> {
        storage.free = 1_000
        val id = add()
        val failed = await(id, EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("space"))
        assertEquals(emptyList<String>(), storage.public.list()!!.toList())
    }

    @Test fun aFailedPublishIsAFailureAndKeepsThePartialFile() = runBlocking<Unit> {
        storage.failPublish = true
        val id = add()
        val failed = await(id, EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("could not be saved"))
        assertTrue(partFile(id).exists())
        storage.failPublish = false
        engine.retry(id)
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
    }

    // ------------------------------------------------------------------ network

    @Test fun waitsWithoutNetworkAndContinuesWhenBackOnline() = runBlocking<Unit> {
        network.flow.value = NetworkStatus(online = false, metered = false)
        delay(50)
        val id = add()
        val waiting = await(id, EngineState.WaitingForNetwork)
        assertTrue(waiting.errorMessage!!.contains("network"))
        delay(200)
        assertEquals(0, server.requests.size)
        network.flow.value = NetworkStatus(online = true, metered = false)
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
    }

    @Test fun wifiOnlyBlocksMobileData() = runBlocking<Unit> {
        settings.wifiOnly = true
        network.flow.value = NetworkStatus(online = true, metered = true)
        delay(50)
        val id = add()
        val waiting = await(id, EngineState.WaitingForNetwork)
        assertTrue(waiting.errorMessage!!.contains("Wi-Fi"))
        delay(200)
        assertEquals(0, server.requests.size)
        network.flow.value = NetworkStatus(online = true, metered = false)
        await(id, EngineState.Completed)
    }

    @Test fun losingTheNetworkMidDownloadWaitsThenResumes() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        awaitBytes(id, 30_000)
        network.flow.value = NetworkStatus(online = false, metered = false)
        val waiting = await(id, EngineState.WaitingForNetwork)
        val length = partFile(id).length()
        assertTrue(length > 0)
        assertEquals(length, waiting.downloadedBytes)
        server.chunkDelayMs = 0
        network.flow.value = NetworkStatus(online = true, metered = false)
        val done = await(id, EngineState.Completed)
        assertArrayEquals(server.body, File(done.finalUri!!).readBytes())
        assertEquals("bytes=$length-", server.requests.last()["range"])
        assertEquals(0, done.retryCount)
    }

    // ------------------------------------------------------------------ queue and concurrency

    private suspend fun runAndMeasurePeak(count: Int): Int {
        server.body = FileServer.bytes(200_000)
        server.chunkDelayMs = 5
        val ids = (1..count).map { add("f$it.bin") }
        var peak = 0
        withTimeout(30_000) {
            while (true) {
                val list = engine.items.value
                peak = maxOf(peak, list.count { it.download.state.isActive })
                if (ids.all { id -> list.any { it.download.id == id && it.download.state == EngineState.Completed } }) break
                delay(5)
            }
        }
        return peak
    }

    @Test fun neverRunsMoreThanTheConfiguredNumberOfDownloads() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        assertEquals(1, runAndMeasurePeak(3))
    }

    @Test fun runsSeveralDownloadsAtTheSameTime() = runBlocking<Unit> {
        settings.maxConcurrent = 2
        assertEquals(2, runAndMeasurePeak(4))
    }

    @Test fun startsQueuedDownloadsInOrder() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        server.body = FileServer.bytes(100_000)
        server.chunkDelayMs = 2
        val ids = (1..3).map { add("o$it.bin") }
        withTimeout(30_000) { engine.items.first { l -> ids.all { id -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } } }
        awaitFinished(ids.size)
        assertEquals(ids, finished.map { it.id })
    }

    // ------------------------------------------------------------------ duplicates and input

    @Test fun detectsDuplicateAddresses() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        assertEquals(EnqueueResult.Duplicate(id), engine.enqueue(request()))
        assertEquals(EnqueueResult.Duplicate(id), engine.enqueue(EngineRequest(server.url() + "#section", "x.bin", null)))
        server.chunkDelayMs = 0
        await(id, EngineState.Completed)
        assertTrue(engine.enqueue(request()) is EnqueueResult.Added)
    }

    @Test fun rejectsAddressesThatAreNotWebLinks() = runBlocking<Unit> {
        listOf("javascript:alert(1)", "ftp://example.com/a", "", "not a link", "https://user:pw@example.com/a").forEach {
            assertTrue(it, engine.enqueue(EngineRequest(it, "a.bin", null)) is EnqueueResult.Rejected)
        }
        assertEquals(0, engine.items.value.size)
    }

    @Test fun followsASafeRedirect() = runBlocking<Unit> {
        val id = (engine.enqueue(EngineRequest("http://127.0.0.1:${server.port}/redir/x", "r.bin", null)) as EnqueueResult.Added).id
        val done = await(id, EngineState.Completed)
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals("/file/redirected.bin", server.paths.last())
    }

    @Test fun refusesARedirectLoop() = runBlocking<Unit> {
        val id = (engine.enqueue(EngineRequest("http://127.0.0.1:${server.port}/loop/x", "l.bin", null)) as EnqueueResult.Added).id
        val failed = await(id, EngineState.Failed)
        assertTrue(failed.errorMessage!!.contains("circle"))
    }

    // ------------------------------------------------------------------ list management

    @Test fun removeOnlyRemovesFinishedEntries() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        awaitBytes(id, 10_000)
        engine.remove(id)
        assertNotNull(engine.items.value.firstOrNull { it.download.id == id })
        server.chunkDelayMs = 0
        val done = await(id, EngineState.Completed)
        engine.remove(id)
        assertEquals(0, engine.items.value.size)
        assertTrue(File(done.finalUri!!).exists())
        assertEquals(null, store.get(id))
    }

    @Test fun deleteFileRemovesTheFileAndTheEntry() = runBlocking<Unit> {
        val id = add()
        val done = await(id, EngineState.Completed)
        assertTrue(engine.deleteFile(id))
        assertFalse(File(done.finalUri!!).exists())
        assertEquals(0, engine.items.value.size)
    }

    @Test fun twoDownloadsWithTheSameFileNameGetDifferentFiles() = runBlocking<Unit> {
        val a = add("x1.bin", "same.bin")
        val b = add("x2.bin", "same.bin")
        val fa = await(a, EngineState.Completed).finalUri
        val fb = await(b, EngineState.Completed).finalUri
        assertTrue(fa != fb)
        assertEquals(2, storage.public.list()!!.size)
    }

    // ------------------------------------------------------------------ recovery after a restart

    private suspend fun crashedRecord(partBytes: ByteArray, total: Long, state: EngineState = EngineState.Running): Long {
        val d = EngineDownload(
            id = 0, url = server.url(), urlKey = com.almus.ytdownloader.download.UrlKey.of(server.url()), fileName = "file.bin",
            mimeType = null, subfolder = null, state = state, downloadedBytes = partBytes.size.toLong(), totalBytes = total,
            etag = "\"v1\"", lastModified = null, acceptsRanges = true, partName = "", finalUri = null, errorMessage = null,
            retryCount = 0, nextRetryAtMs = 0, insecure = true, notified = false, createdAtMs = 1, updatedAtMs = 1,
        )
        val id = store.insert(d)
        store.update(d.copy(id = id, partName = "dl-$id.part"))
        partFile(id).writeBytes(partBytes)
        return id
    }

    @Test fun continuesADownloadThatWasInterruptedByAProcessDeath() = runBlocking<Unit> {
        val id = crashedRecord(body.copyOfRange(0, 100_000), body.size.toLong())
        val restarted = restartedEngine()
        restarted.start()
        val done = withTimeout(20_000) { restarted.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals("bytes=100000-", server.requests.single()["range"])
    }

    @Test fun aPartialFileThatAlreadyHasEveryByteIsPublishedWithoutAnyRequest() = runBlocking<Unit> {
        val id = crashedRecord(body, body.size.toLong())
        val restarted = restartedEngine()
        restarted.start()
        val done = withTimeout(20_000) { restarted.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(0, server.requests.size)
    }

    @Test fun aRangeNotSatisfiableAnswerForACompleteFileFinishesIt() = runBlocking<Unit> {
        val id = crashedRecord(body, total = -1)
        val restarted = restartedEngine()
        restarted.start()
        val done = withTimeout(20_000) { restarted.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(1, server.requests.size)
    }

    @Test fun aRangeNotSatisfiableAnswerForTheWrongFileStartsAgain() = runBlocking<Unit> {
        val id = crashedRecord(ByteArray(body.size + 5_000) { 9 }, total = -1)
        val restarted = restartedEngine()
        restarted.start()
        val done = withTimeout(20_000) { restarted.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
        assertEquals(2, server.requests.size)
        assertFalse(server.requests[1].containsKey("range"))
    }

    @Test fun waitingStatesAreQueuedAgainAfterARestart() = runBlocking<Unit> {
        val id = crashedRecord(body.copyOfRange(0, 50_000), body.size.toLong(), EngineState.WaitingToRetry)
        val restarted = restartedEngine()
        restarted.start()
        val done = withTimeout(20_000) { restarted.items.first { l -> l.any { it.download.id == id && it.download.state == EngineState.Completed } } }
            .first { it.download.id == id }.download
        assertArrayEquals(body, File(done.finalUri!!).readBytes())
    }

    @Test fun pausedAndFinishedDownloadsStayAsTheyAreAfterARestart() = runBlocking<Unit> {
        val paused = crashedRecord(body.copyOfRange(0, 50_000), body.size.toLong(), EngineState.Paused)
        val restarted = restartedEngine()
        restarted.start()
        delay(300)
        assertEquals(EngineState.Paused, restarted.items.value.first { it.download.id == paused }.download.state)
        assertEquals(0, server.requests.size)
    }

    @Test fun partialFilesOfUnknownDownloadsAreCleanedUp() = runBlocking<Unit> {
        val orphan = File(storage.parts, "dl-999.part").apply { writeBytes(ByteArray(10)) }
        val other = File(storage.parts, "keep.txt").apply { writeBytes(ByteArray(10)) }
        val restarted = restartedEngine()
        restarted.start()
        assertFalse(orphan.exists())
        assertTrue(other.exists())
    }

    // ------------------------------------------------------------------ scheduling control

    @Test fun suspendedWorkPutsRunningDownloadsBackInTheQueueAndLaterContinues() = runBlocking<Unit> {
        server.body = FileServer.bytes(600_000)
        server.chunkDelayMs = 10
        val id = add()
        awaitBytes(id, 30_000)
        engine.suspendWork(true)
        val queued = await(id, EngineState.Queued)
        assertTrue(queued.downloadedBytes > 0)
        delay(150)
        assertEquals(EngineState.Queued, current(id).state)
        server.chunkDelayMs = 0
        engine.suspendWork(false)
        await(id, EngineState.Completed)
    }

    @Test fun awaitIdleReturnsOnlyWhenNothingIsBusy() = runBlocking<Unit> {
        server.body = FileServer.bytes(300_000)
        server.chunkDelayMs = 5
        val id = add()
        withTimeout(20_000) { engine.awaitIdle() }
        assertEquals(EngineState.Completed, current(id).state)
    }

    @Test fun speedIsOnlyReportedForARunningDownload() = runBlocking<Unit> {
        val id = add()
        val done = await(id, EngineState.Completed)
        assertEquals(null, engine.items.value.first { it.download.id == done.id }.speed)
    }
}
