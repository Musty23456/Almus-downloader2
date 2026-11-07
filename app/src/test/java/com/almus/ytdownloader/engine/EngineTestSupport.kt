package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.HttpExchange
import com.almus.ytdownloader.download.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeStore : EngineStore {
    private val map = ConcurrentHashMap<Long, EngineDownload>()
    private val next = AtomicLong(1)

    override suspend fun insert(download: EngineDownload): Long {
        val id = next.getAndIncrement()
        map[id] = download.copy(id = id)
        return id
    }

    override suspend fun update(download: EngineDownload) {
        map[download.id] = download
    }

    override suspend fun all(): List<EngineDownload> = map.values.sortedBy { it.id }
    override suspend fun delete(id: Long) {
        map.remove(id)
    }

    fun get(id: Long): EngineDownload? = map[id]
}

class FakeStorage(root: File) : FinalStorage {
    val parts = File(root, "parts").apply { mkdirs() }
    val public = File(root, "public").apply { mkdirs() }

    @Volatile var free = -1L

    @Volatile var failPublish = false

    override fun partDir(): File = parts
    override fun freeBytes(): Long = free

    override suspend fun publish(part: File, fileName: String, mimeType: String?, subfolder: String?): PublishedFile {
        if (failPublish) throw IOException("publish failed")
        synchronized(this) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            var target = File(public, fileName)
            var n = 1
            while (target.exists()) {
                target = File(public, "$base ($n).$ext")
                n++
            }
            part.copyTo(target)
            part.delete()
            return PublishedFile(target.absolutePath)
        }
    }

    override suspend fun deletePublished(uri: String): Boolean = File(uri).delete()
}

class FakeNetwork(online: Boolean = true, metered: Boolean = false) : NetworkMonitor {
    val flow = MutableStateFlow(NetworkStatus(online, metered))
    override val status: StateFlow<NetworkStatus> get() = flow
}

class TestSettings(
    override var maxConcurrent: Int = 3,
    override var wifiOnly: Boolean = false,
    override var segmentedDownloads: Boolean = true,
    override var maxConnectionsPerDownload: Int = 0,
) : EngineSettings

enum class RangeMode { Honor, Ignore, Refuse }

/**
 * A local file server with the behaviours the engine must survive: Range support (or none), cut connections,
 * temporary errors, a changing file, slow bodies. Built on the project's own test HTTP server.
 */
class FileServer(@Volatile var body: ByteArray) {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    val paths = CopyOnWriteArrayList<String>()
    private val count = AtomicInteger()

    @Volatile var etag: String? = "\"v1\""

    @Volatile var rangeMode = RangeMode.Honor

    /** 1-based request numbers whose connection is cut after [cutAfter] body bytes. */
    val cutOnRequest: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile var cutAfter = 100_000

    /** Status codes answered (with no body) to the next requests, in order. */
    val statusQueue = ConcurrentLinkedQueue<Int>()

    @Volatile var retryAfter: String? = null

    @Volatile var chunkDelayMs = 0L

    /** Called at the start of each request with its number. */
    @Volatile var beforeRequest: ((Int) -> Unit)? = null

    val port: Int get() = server.address.port

    fun url(name: String = "file.bin"): String = "http://127.0.0.1:$port/file/$name"

    init {
        server.createContext("/file") { handle(it) }
        server.createContext("/redir") { ex ->
            ex.responseHeaders.set("Location", "/file/redirected.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/loop") { ex ->
            ex.responseHeaders.set("Location", "/loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val n = count.incrementAndGet()
        requests.add(ex.requestHeaders)
        paths.add(ex.requestPath)
        beforeRequest?.invoke(n)

        val queued = statusQueue.poll()
        if (queued != null) {
            retryAfter?.let { ex.responseHeaders.set("Retry-After", it) }
            ex.sendResponseHeaders(queued, -1)
            ex.close()
            return
        }

        val data = body
        when (rangeMode) {
            RangeMode.Honor -> ex.responseHeaders.set("Accept-Ranges", "bytes")
            RangeMode.Refuse -> ex.responseHeaders.set("Accept-Ranges", "none")
            RangeMode.Ignore -> Unit
        }
        etag?.let { ex.responseHeaders.set("ETag", it) }

        var start = 0
        var code = 200
        val range = ex.requestHeaders["range"]
        if (rangeMode == RangeMode.Honor && range != null) {
            val from = Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toIntOrNull()
            val ifRange = ex.requestHeaders["if-range"]
            if (from != null && (ifRange == null || ifRange == etag)) {
                if (from >= data.size) {
                    ex.responseHeaders.set("Content-Range", "bytes */${data.size}")
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return
                }
                start = from
                code = 206
                ex.responseHeaders.set("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            }
        }

        ex.sendResponseHeaders(code, (data.size - start).toLong())
        val out = ex.responseBody
        val cut = n in cutOnRequest
        var pos = start
        var sent = 0
        try {
            while (pos < data.size) {
                val end = minOf(pos + CHUNK, data.size)
                out.write(data, pos, end - pos)
                out.flush()
                sent += end - pos
                pos = end
                if (cut && sent >= cutAfter) {
                    ex.close()
                    return
                }
                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            }
        } catch (_: IOException) {
            // The client went away (pause, cancel or a test ending): nothing to do.
        } finally {
            ex.close()
        }
    }

    companion object {
        private const val CHUNK = 8192

        fun bytes(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }
    }
}
