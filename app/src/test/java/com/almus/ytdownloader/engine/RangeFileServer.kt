package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.HttpExchange
import com.almus.ytdownloader.download.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** What a test can make the server do to one ranged (or plain) request. */
sealed interface Fault {
    /** Close the connection after sending this many body bytes. */
    data class Cut(val afterBytes: Int) : Fault

    /** Answer with this status and no body. */
    data class Status(val code: Int, val retryAfter: String? = null) : Fault

    /** Answer 206 but with a Content-Range that does not match the request. */
    data object WrongContentRange : Fault

    /** Answer 200 with the whole file although a range was asked for (a server that ignores Range). */
    data object IgnoreRange : Fault

    /** Send the right length but flip one byte of the body (silent corruption in transit). */
    data class Corrupt(val atBodyOffset: Int) : Fault
}

/** One request as the server saw it. */
data class SeenRequest(val number: Int, val rangeStart: Long?, val rangeEnd: Long?, val ifRange: String?, val headers: Map<String, String>)

/**
 * A local file server for the segmented-download tests: bounded and open-ended ranges, If-Range, per-connection and
 * total speed limits, latency, and scripted faults. Built on the project's own test HTTP server (no new library).
 */
class RangeFileServer(@Volatile var body: ByteArray) {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val seen = CopyOnWriteArrayList<SeenRequest>()
    private val counter = AtomicInteger()
    private val openNow = AtomicInteger()

    @Volatile var peakConcurrent = 0

    @Volatile var etag: String? = "\"v1\""

    @Volatile var lastModified: String? = "Wed, 21 Oct 2015 07:28:00 GMT"

    /** Sends "Accept-Ranges: bytes" (false: sends nothing). */
    @Volatile var advertiseRanges = true

    /** Really answers ranges with 206 (false: always 200, whatever the request says). */
    @Volatile var honorRanges = true

    /** Bytes per second for every connection separately; 0 = unlimited. */
    @Volatile var perConnectionBytesPerSec = 0L

    /** Bytes per second shared by all connections together; 0 = unlimited. */
    @Volatile var totalBytesPerSec = 0L

    @Volatile var latencyMs = 0L

    @Volatile var contentEncoding: String? = null

    /** Decides a fault per request. Return null for none. Runs on the server thread. */
    @Volatile var faultFor: (SeenRequest) -> Fault? = { null }

    private val bucketLock = Any()
    private var bucketAt = System.nanoTime()

    val port: Int get() = server.address.port
    fun url(name: String = "file.bin"): String = "http://127.0.0.1:$port/file/$name"
    fun rangedRequests(): List<SeenRequest> = seen.filter { it.rangeStart != null }

    init {
        server.createContext("/file") { handle(it) }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val n = counter.incrementAndGet()
        val rangeHeader = ex.requestHeaders["range"]
        val m = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val data = body
        val rs = m?.groupValues?.get(1)?.toLongOrNull()
        val re = m?.groupValues?.get(2)?.toLongOrNull() ?: if (rs != null) data.size - 1L else null
        val req = SeenRequest(n, rs, re, ex.requestHeaders["if-range"], ex.requestHeaders)
        seen.add(req)
        val now = openNow.incrementAndGet()
        peakConcurrent = maxOf(peakConcurrent, now)
        try {
            if (latencyMs > 0) Thread.sleep(latencyMs)
            respond(ex, req, data)
        } catch (_: IOException) {
            // The client went away (pause, cancel, a test ending).
        } finally {
            openNow.decrementAndGet()
            ex.close()
        }
    }

    private fun respond(ex: HttpExchange, req: SeenRequest, data: ByteArray) {
        val fault = faultFor(req)
        if (fault is Fault.Status) {
            fault.retryAfter?.let { ex.responseHeaders.set("Retry-After", it) }
            ex.sendResponseHeaders(fault.code, -1)
            return
        }
        if (advertiseRanges) ex.responseHeaders.set("Accept-Ranges", "bytes")
        etag?.let { ex.responseHeaders.set("ETag", it) }
        lastModified?.let { ex.responseHeaders.set("Last-Modified", it) }
        contentEncoding?.let { ex.responseHeaders.set("Content-Encoding", it) }

        var start = 0L
        var endIncl = data.size - 1L
        var code = 200
        val wantsRange = req.rangeStart != null && honorRanges && fault !is Fault.IgnoreRange
        if (wantsRange) {
            val ifRange = req.ifRange
            val valid = ifRange == null || ifRange == etag || ifRange == lastModified
            if (valid) {
                if (req.rangeStart!! >= data.size) {
                    ex.responseHeaders.set("Content-Range", "bytes */${data.size}")
                    ex.sendResponseHeaders(416, -1)
                    return
                }
                start = req.rangeStart
                endIncl = minOf(req.rangeEnd ?: (data.size - 1L), data.size - 1L)
                code = 206
                val shown = if (fault is Fault.WrongContentRange) start + 1 else start
                ex.responseHeaders.set("Content-Range", "bytes $shown-${if (fault is Fault.WrongContentRange) endIncl + 1 else endIncl}/${data.size}")
            }
        }
        val length = endIncl - start + 1
        ex.sendResponseHeaders(code, length)
        val out = ex.responseBody
        val cutAfter = (fault as? Fault.Cut)?.afterBytes
        val corruptAt = (fault as? Fault.Corrupt)?.atBodyOffset
        var sent = 0L
        var pos = start.toInt()
        val chunk = 8192
        while (pos <= endIncl) {
            val end = minOf(pos + chunk - 1L, endIncl).toInt()
            var piece = data.copyOfRange(pos, end + 1)
            if (corruptAt != null && corruptAt >= sent && corruptAt < sent + piece.size) {
                piece[(corruptAt - sent).toInt()] = (piece[(corruptAt - sent).toInt()].toInt() xor 0x55).toByte()
            }
            out.write(piece)
            out.flush()
            sent += piece.size
            pos = end + 1
            throttle(piece.size)
            if (cutAfter != null && sent >= cutAfter) return
        }
    }

    private fun throttle(bytes: Int) {
        val per = perConnectionBytesPerSec
        if (per > 0) Thread.sleep(maxOf(1L, bytes * 1000L / per))
        val total = totalBytesPerSec
        if (total > 0) {
            val wait: Long
            synchronized(bucketLock) {
                val nowNs = System.nanoTime()
                val due = maxOf(bucketAt, nowNs - 20_000_000L) + bytes * 1_000_000_000L / total
                bucketAt = due
                wait = due - nowNs
            }
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
        }
    }

    companion object {
        fun bytes(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { (((it.toLong() * 2654435761L) xor (seed.toLong() * 97)) ushr 5).toByte() }
    }
}
