package com.almus.ytdownloader.download

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real [LinkInspector] (real HttpURLConnection) against a local HTTP server on the JVM. */
class LinkInspectorTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)

        // A normal file with a Content-Disposition name.
        server.createContext("/files/report") { ex ->
            ex.responseHeaders.set("Content-Type", "application/pdf")
            ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"Quarterly Report.pdf\"")
            respondWithLength(ex, 2048)
        }
        // Name only in the URL, generic type.
        server.createContext("/dl/archive.zip") { ex ->
            ex.responseHeaders.set("Content-Type", "application/octet-stream")
            respondWithLength(ex, 4096)
        }
        // Relative redirect to the file above.
        server.createContext("/old") { ex ->
            ex.responseHeaders.set("Location", "/files/report")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        // Redirect loop.
        server.createContext("/loop") { ex ->
            ex.responseHeaders.set("Location", "/loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        // Redirect to a scheme that is not allowed.
        server.createContext("/to-ftp") { ex ->
            ex.responseHeaders.set("Location", "ftp://example.com/file.zip")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.createContext("/private") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        server.createContext("/broken") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }

        // Refuses HEAD, answers a ranged GET with 206 and the total size.
        server.createContext("/no-head") { ex ->
            if (ex.requestMethod == "HEAD") {
                ex.sendResponseHeaders(405, -1)
            } else {
                ex.responseHeaders.set("Content-Type", "video/mp4")
                ex.responseHeaders.set("Content-Range", "bytes 0-0/5000")
                ex.sendResponseHeaders(206, 1)
                ex.responseBody.write(0)
            }
            ex.close()
        }
        // No size announced at all.
        server.createContext("/stream.mp3") { ex ->
            ex.responseHeaders.set("Content-Type", "audio/mpeg")
            if (ex.requestMethod == "HEAD") {
                ex.sendResponseHeaders(200, -1)
            } else {
                ex.sendResponseHeaders(200, 0) // chunked
                ex.responseBody.write(1)
            }
            ex.close()
        }
        // An HTML page.
        server.createContext("/page") { ex ->
            ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            respondWithLength(ex, 300)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServer() {
        server.stop(0)
    }

    private fun respondWithLength(ex: HttpExchange, length: Int) {
        if (ex.requestMethod == "HEAD") {
            ex.responseHeaders.set("Content-Length", length.toString())
            ex.sendResponseHeaders(200, -1)
        } else {
            ex.sendResponseHeaders(200, length.toLong())
            ex.responseBody.write(ByteArray(length))
        }
        ex.close()
    }

    private fun inspect(url: String, inspector: LinkInspector = LinkInspector(connectTimeoutMs = 3_000, readTimeoutMs = 3_000)) =
        runBlocking { inspector.inspect(url) }

    private fun success(url: String): LinkInfo {
        val result = inspect(url)
        assertTrue("expected success but was $result", result is InspectResult.Success)
        return (result as InspectResult.Success).info
    }

    private fun failureMessage(url: String): String {
        val result = inspect(url)
        assertTrue("expected failure but was $result", result is InspectResult.Failure)
        return (result as InspectResult.Failure).message
    }

    // ---- successful requests ----

    @Test fun successfulCheckReadsNameTypeAndSizeFromHeaders() {
        val info = success("$base/files/report")
        assertEquals("Quarterly Report.pdf", info.fileName)
        assertEquals("application/pdf", info.mimeType)
        assertEquals(2048L, info.contentLength)
        assertEquals(0, info.redirectCount)
        assertTrue("http link must be flagged insecure", info.insecure)
        assertFalse(info.looksLikeWebPage)
    }

    @Test fun nameComesFromUrlAndGenericTypeIsInferredFromExtension() {
        val info = success("$base/dl/archive.zip")
        assertEquals("archive.zip", info.fileName)
        assertEquals("application/zip", info.mimeType)
        assertEquals(4096L, info.contentLength)
    }

    @Test fun followsRelativeRedirect() {
        val info = success("$base/old")
        assertEquals(1, info.redirectCount)
        assertTrue(info.finalUrl.endsWith("/files/report"))
        assertEquals("Quarterly Report.pdf", info.fileName)
        assertEquals("$base/old", info.url)
    }

    @Test fun fallsBackToRangedGetWhenHeadIsRefused() {
        val info = success("$base/no-head")
        assertEquals(5000L, info.contentLength)
        assertEquals("video/mp4", info.mimeType)
        assertEquals("no-head.mp4", info.fileName)
    }

    @Test fun missingSizeIsReportedAsUnknown() {
        val info = success("$base/stream.mp3")
        assertEquals(-1L, info.contentLength)
        assertTrue(LinkWarnings.of(info).contains(LinkWarning.UnknownSize))
    }

    @Test fun htmlPagesAreFlagged() {
        val info = success("$base/page")
        assertTrue(info.looksLikeWebPage)
        assertTrue(LinkWarnings.of(info).contains(LinkWarning.WebPage))
    }

    // ---- invalid URL ----

    @Test fun invalidUrlIsRejectedBeforeAnyNetworkAccess() {
        assertEquals(UrlError.UnsupportedScheme.message, failureMessage("ftp://example.com/a.zip"))
        assertEquals(UrlError.MissingScheme.message, failureMessage("example.com/a.zip"))
        assertEquals(UrlError.Empty.message, failureMessage("  "))
    }

    // ---- HTTP errors ----

    @Test fun http404GivesNotFoundMessage() {
        val message = failureMessage("$base/missing")
        assertTrue(message, message.contains("404") && message.contains("not found", ignoreCase = true))
    }

    @Test fun http403SaysAccessIsNotBypassed() {
        val message = failureMessage("$base/private")
        assertTrue(message, message.contains("403") && message.contains("Access denied"))
    }

    @Test fun http500GivesServerErrorMessage() {
        assertTrue(failureMessage("$base/broken").contains("Server error"))
    }

    // ---- redirects ----

    @Test fun redirectLoopStopsAfterTheLimit() {
        assertTrue(failureMessage("$base/loop").contains("too many", ignoreCase = true))
    }

    @Test fun redirectToUnsupportedSchemeIsBlocked() {
        assertTrue(failureMessage("$base/to-ftp").contains("unsupported", ignoreCase = true))
    }

    // ---- network failure ----

    @Test fun refusedConnectionGivesNetworkMessage() {
        val deadPort = server.address.port
        server.stop(0) // nothing listens on this port any more
        val message = failureMessage("http://127.0.0.1:$deadPort/file.zip")
        assertTrue(message, message.contains("connect", ignoreCase = true) || message.contains("too long", ignoreCase = true))
    }

    @Test fun unknownHostGivesNetworkMessage() {
        // ".invalid" is reserved and never resolves.
        val result = inspect("http://almus-test.invalid/file.zip")
        assertTrue(result is InspectResult.Failure)
    }
}
