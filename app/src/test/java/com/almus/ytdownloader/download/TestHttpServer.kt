package com.almus.ytdownloader.download

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny local HTTP server for tests, built only on java.net. The JDK's own com.sun.net.httpserver is
 * not visible to the Android unit-test compiler, and a new library would need a safety review.
 * The names and calls follow the JDK server so the tests read the same.
 */
class HttpExchange(
    private val socket: Socket,
    val requestMethod: String,
    /** Request headers, names in lower case. */
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestPath: String = "/",
) {
    class Headers {
        private val values = LinkedHashMap<String, String>()

        fun set(name: String, value: String) {
            values.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { values.remove(it) }
            values[name] = value
        }

        fun snapshot(): LinkedHashMap<String, String> = LinkedHashMap(values)
    }

    val responseHeaders = Headers()
    private val out: OutputStream = socket.getOutputStream()

    val responseBody: OutputStream get() = out

    /**
     * Like the JDK: a positive [length] announces that size, 0 means "size unknown" (the body ends when the
     * connection closes), -1 means no body (a HEAD answer keeps the headers the test set, such as Content-Length).
     */
    fun sendResponseHeaders(code: Int, length: Long) {
        val headers = responseHeaders.snapshot()
        val head = requestMethod == "HEAD"
        if (length > 0) {
            headers["Content-Length"] = length.toString()
        } else if (length == -1L && !head) {
            headers["Content-Length"] = "0"
        }
        headers["Connection"] = "close"
        val text = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        headers.forEach { (name, value) -> text.append(name).append(": ").append(value).append("\r\n") }
        text.append("\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun close() {
        runCatching { out.flush() }
        runCatching { socket.close() }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }
}

class HttpServer private constructor(private val socket: ServerSocket) {
    private val contexts = CopyOnWriteArrayList<Pair<String, (HttpExchange) -> Unit>>()

    @Volatile
    private var running = false

    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

    /** The longest matching path prefix answers, as in the JDK server. */
    fun createContext(path: String, handler: (HttpExchange) -> Unit) {
        contexts.add(path to handler)
    }

    fun start() {
        running = true
        daemon("test-http-accept") { acceptLoop() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stop(delaySeconds: Int) {
        running = false
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            daemon("test-http-client") { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val requestHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) requestHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val path = parts[1].substringBefore('?')
            val handler = contexts.filter { path.startsWith(it.first) }.maxByOrNull { it.first.length }?.second
            val exchange = HttpExchange(client, parts[0], requestHeaders, parts[1])
            if (handler == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                handler(exchange)
            }
        } catch (e: Exception) {
            // A broken test connection must not take the server down.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun daemon(name: String, block: () -> Unit) {
        val thread = Thread(block, name)
        thread.isDaemon = true
        thread.start()
    }

    companion object {
        fun create(address: InetSocketAddress, backlog: Int): HttpServer {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(address, backlog)
            return HttpServer(socket)
        }
    }
}
