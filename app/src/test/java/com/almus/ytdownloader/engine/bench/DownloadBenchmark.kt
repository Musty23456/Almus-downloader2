package com.almus.ytdownloader.engine.bench

import com.almus.ytdownloader.engine.DownloadEngine
import com.almus.ytdownloader.engine.EnqueueResult
import com.almus.ytdownloader.engine.EngineRequest
import com.almus.ytdownloader.engine.EngineState
import com.almus.ytdownloader.engine.Fault
import com.almus.ytdownloader.engine.FakeNetwork
import com.almus.ytdownloader.engine.FakeStorage
import com.almus.ytdownloader.engine.FakeStore
import com.almus.ytdownloader.engine.OkHttpTransport
import com.almus.ytdownloader.engine.RangeFileServer
import com.almus.ytdownloader.engine.RetryPolicy
import com.almus.ytdownloader.engine.TestSettings
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Benchmark of the download engine against a local server with controlled network behaviour. It only uses API that
 * the original engine and the new one share, so the same file is compiled against both and the numbers compare
 * like with like. Run with `engine/bench/run-benchmark.sh`. Prints one JSON object per scenario.
 */
object DownloadBenchmark {
    private const val MIB = 1024 * 1024

    private class Scenario(
        val name: String, val description: String, val sizeMib: Double,
        val perConnection: Long = 0, val total: Long = 0, val latencyMs: Long = 0,
        val ranges: Boolean = true, val cutEveryBytes: Int = 0,
    )

    private val scenarios = listOf(
        Scenario("per-connection-limit-2MBps", "Server caps each connection at 2 MB/s (typical CDN), 20 ms latency, 24 MiB", 24.0, perConnection = 2_000_000, latencyMs = 20),
        Scenario("per-connection-limit-8MBps", "Server caps each connection at 8 MB/s, 20 ms latency, 48 MiB", 48.0, perConnection = 8_000_000, latencyMs = 20),
        Scenario("shared-link-cap-10MBps", "The whole link is 10 MB/s whatever the connection count, 32 MiB (no gain possible)", 32.0, total = 10_000_000, latencyMs = 20),
        Scenario("unthrottled-loopback", "No limits at all, 64 MiB (pure overhead of segmenting and merging)", 64.0),
        Scenario("flaky-connections", "4 MB/s per connection, every request is cut after 1.5 MiB, 32 MiB", 32.0, perConnection = 4_000_000, latencyMs = 20, cutEveryBytes = 1_500_000),
        Scenario("server-without-range-support", "Server never supports Range, 4 MB/s, 16 MiB (must not regress)", 16.0, perConnection = 4_000_000, latencyMs = 20, ranges = false),
        Scenario("small-file", "1 MiB file, 20 ms latency (must not regress)", 1.0, perConnection = 4_000_000, latencyMs = 20),
    )

    @JvmStatic fun main(args: Array<String>) {
        val runs = args.getOrNull(0)?.toIntOrNull() ?: 3
        val only = args.getOrNull(1)
        for (sc in scenarios) {
            if (only != null && sc.name != only) continue
            val times = ArrayList<Double>()
            var requests = 0
            var ok = true
            repeat(runs + 1) { run ->
                val r = runOnce(sc)
                if (run > 0) { // the first run is a warm-up (JIT, connection setup)
                    times += r.ms
                    requests = r.requests
                }
                ok = ok && r.correct
            }
            val median = times.sorted()[times.size / 2]
            val bytes = (sc.sizeMib * MIB).toLong()
            println(
                "{\"scenario\":\"${sc.name}\",\"description\":\"${sc.description}\",\"bytes\":$bytes,\"runsMs\":${times.map { Math.round(it) }},\"medianMs\":${Math.round(median)}," +
                    "\"mbPerSec\":${"%.2f".format(bytes / 1e6 / (median / 1000))},\"requests\":$requests,\"correct\":$ok}",
            )
        }
    }

    private class Result(val ms: Double, val requests: Int, val correct: Boolean)

    private fun runOnce(sc: Scenario): Result {
        val root = Files.createTempDirectory("almus-bench").toFile()
        val body = RangeFileServer.bytes((sc.sizeMib * MIB).toInt())
        val server = RangeFileServer(body).apply {
            perConnectionBytesPerSec = sc.perConnection
            totalBytesPerSec = sc.total
            latencyMs = sc.latencyMs
            honorRanges = sc.ranges
            advertiseRanges = sc.ranges
            if (sc.cutEveryBytes > 0) faultFor = { r -> if ((r.rangeEnd ?: Long.MAX_VALUE) - (r.rangeStart ?: 0) > sc.cutEveryBytes) Fault.Cut(sc.cutEveryBytes) else null }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = FakeStorage(root)
            val engine = DownloadEngine(
                store = FakeStore(), transport = OkHttpTransport(), storage = storage, network = FakeNetwork(),
                settings = TestSettings(maxConcurrent = 1), scope = scope,
                retryPolicy = RetryPolicy(maxRetries = 50, baseDelayMs = 20, maxDelayMs = 100),
            )
            return runBlocking {
                engine.start()
                val t0 = System.nanoTime()
                val id = (engine.enqueue(EngineRequest(server.url(), "bench.bin", "application/octet-stream")) as EnqueueResult.Added).id
                val done = withTimeout(300_000) {
                    engine.items.first { l -> l.any { it.download.id == id && it.download.state.isTerminal } }.first { it.download.id == id }.download
                }
                val ms = (System.nanoTime() - t0) / 1e6
                val ok = done.state == EngineState.Completed && sha(File(done.finalUri!!).readBytes()) == sha(body)
                Result(ms, server.seen.size, ok)
            }
        } finally {
            scope.cancel()
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
