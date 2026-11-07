package com.almus.ytdownloader.download

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audio and video links, checked with the real [LinkInspector] (real HttpURLConnection) against two
 * local JVM servers: the second one plays the part of a CDN that a redirect points to.
 */
class LinkInspectorMediaTest {
    private lateinit var server: HttpServer
    private lateinit var cdn: HttpServer
    private lateinit var base: String
    private lateinit var cdnBase: String
    private val loopback = InetAddress.getByName("127.0.0.1")

    /** Answers HEAD with headers only and GET with [length] zero bytes. */
    private fun file(ex: HttpExchange, type: String?, length: Int, vararg extra: Pair<String, String>) {
        type?.let { ex.responseHeaders.set("Content-Type", it) }
        extra.forEach { (name, value) -> ex.responseHeaders.set(name, value) }
        if (ex.requestMethod == "HEAD") {
            ex.responseHeaders.set("Content-Length", length.toString())
            ex.sendResponseHeaders(200, -1)
        } else {
            ex.sendResponseHeaders(200, length.toLong())
            ex.responseBody.write(ByteArray(length))
        }
        ex.close()
    }

    /** Like [file] but announces no size at all. */
    private fun unsizedFile(ex: HttpExchange, type: String, vararg extra: Pair<String, String>) {
        ex.responseHeaders.set("Content-Type", type)
        extra.forEach { (name, value) -> ex.responseHeaders.set(name, value) }
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(200, -1)
        } else {
            ex.sendResponseHeaders(200, 0) // chunked
            ex.responseBody.write(1)
        }
        ex.close()
    }

    private fun redirect(ex: HttpExchange, code: Int, location: String) {
        ex.responseHeaders.set("Location", location)
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }

    @Before fun startServers() {
        cdn = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        cdn.createContext("/cdn/final.mp4") { ex ->
            file(ex, "video/mp4", 7000, "Accept-Ranges" to "bytes")
        }
        cdn.start()
        cdnBase = "http://127.0.0.1:${cdn.address.port}"

        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)

        // Ordinary files.
        server.createContext("/v/clip.mp4") { ex -> file(ex, "video/mp4", 8000, "Accept-Ranges" to "bytes") }
        server.createContext("/v/clip.webm") { ex -> file(ex, "video/webm", 3000) }
        server.createContext("/a/podcast.m4a") { ex -> file(ex, "audio/x-m4a", 4000) }
        server.createContext("/a/raw.mp4") { ex -> file(ex, "application/octet-stream", 2500) }
        server.createContext("/a/noext") { ex -> file(ex, "audio/mpeg", 1500) }
        server.createContext("/a/track") { ex ->
            file(ex, "audio/mpeg", 1200, "Content-Disposition" to "attachment; filename=\"song.mp3\"")
        }
        server.createContext("/x/clip.mp4") { ex -> file(ex, "video/mp4", 900) }
        server.createContext("/norange.mp4") { ex -> file(ex, "video/mp4", 6000, "Accept-Ranges" to "none") }
        server.createContext("/silent.mp4") { ex -> file(ex, "video/mp4", 6000) }
        server.createContext("/fake/video.mp4") { ex -> file(ex, "text/html; charset=utf-8", 300) }
        server.createContext("/misleading.m3u8") { ex -> file(ex, "video/mp4", 5000) }

        // Unknown size.
        server.createContext("/unsized.mp3") { ex -> unsizedFile(ex, "audio/mpeg") }

        // Refuses HEAD, answers the ranged GET with 206.
        server.createContext("/nohead/clip.mp4") { ex ->
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

        // Redirects, one path per status code.
        listOf(301, 302, 303, 307, 308).forEach { code ->
            server.createContext("/r/$code") { ex -> redirect(ex, code, "/v/clip.mp4") }
        }
        server.createContext("/r/cdn") { ex -> redirect(ex, 302, "$cdnBase/cdn/final.mp4") }
        server.createContext("/x/y/z") { ex -> redirect(ex, 301, "../clip.mp4") }
        server.createContext("/r/hls") { ex -> redirect(ex, 302, "/live/index.m3u8") }
        server.createContext("/live/index.m3u8") { ex -> file(ex, "application/vnd.apple.mpegurl", 400) }

        // Streams and playlists.
        server.createContext("/play/master") { ex -> file(ex, "application/vnd.apple.mpegurl", 400) }
        server.createContext("/dash/manifest.mpd") { ex -> file(ex, "application/dash+xml", 600) }
        server.createContext("/hls/plain.m3u8") { ex -> file(ex, "application/octet-stream", 300) }
        server.createContext("/radio.m3u") { ex -> file(ex, "audio/x-mpegurl", 100) }
        server.createContext("/radio") { ex ->
            unsizedFile(ex, "audio/mpeg", "icy-name" to "Test Radio", "icy-metaint" to "16000")
        }

        // Errors.
        server.createContext("/private.mp4") { ex -> ex.sendResponseHeaders(401, -1); ex.close() }
        server.createContext("/missing.mp4") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }

        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServers() {
        server.stop(0)
        cdn.stop(0)
    }

    private fun inspect(url: String) = runBlocking {
        LinkInspector(connectTimeoutMs = 3_000, readTimeoutMs = 3_000).inspect(url)
    }

    private fun success(url: String): LinkInfo {
        val result = inspect(url)
        assertTrue("expected success but was $result", result is InspectResult.Success)
        return (result as InspectResult.Success).info
    }

    private fun failure(url: String): InspectResult.Failure {
        val result = inspect(url)
        assertTrue("expected failure but was $result", result is InspectResult.Failure)
        return result as InspectResult.Failure
    }

    // ---- ordinary audio and video files ----

    @Test fun mp4VideoIsDescribedFromItsHeaders() {
        val info = success("$base/v/clip.mp4")
        assertEquals("clip.mp4", info.fileName)
        assertEquals("video/mp4", info.mimeType)
        assertEquals(8000L, info.contentLength)
        assertEquals(MediaDetails(MediaKind.Video, "MP4"), info.media)
        assertEquals(true, info.acceptsRanges)
    }

    @Test fun webmVideoIsRecognised() {
        val info = success("$base/v/clip.webm")
        assertEquals(MediaDetails(MediaKind.Video, "WebM"), info.media)
        assertEquals("clip.webm", info.fileName)
    }

    @Test fun m4aAudioIsRecognised() {
        val info = success("$base/a/podcast.m4a")
        assertEquals(MediaDetails(MediaKind.Audio, "M4A"), info.media)
        assertEquals(4000L, info.contentLength)
    }

    @Test fun genericTypeWithMediaExtensionIsTreatedAsMedia() {
        val info = success("$base/a/raw.mp4")
        assertEquals("video/mp4", info.mimeType)
        assertEquals(MediaDetails(MediaKind.Video, "MP4"), info.media)
    }

    @Test fun nameWithoutExtensionGetsOneFromTheServerType() {
        val info = success("$base/a/noext")
        assertEquals("noext.mp3", info.fileName)
        assertEquals(MediaDetails(MediaKind.Audio, "MP3"), info.media)
    }

    @Test fun contentDispositionNameIsUsedForMedia() {
        val info = success("$base/a/track")
        assertEquals("song.mp3", info.fileName)
        assertEquals(MediaDetails(MediaKind.Audio, "MP3"), info.media)
    }

    @Test fun aWebPageNamedLikeAVideoIsNotMedia() {
        val info = success("$base/fake/video.mp4")
        assertNull(info.media)
        assertTrue(info.looksLikeWebPage)
        assertTrue(LinkWarnings.of(info).contains(LinkWarning.WebPage))
    }

    @Test fun aRealMediaTypeBeatsAPlaylistLookingAddress() {
        val info = success("$base/misleading.m3u8")
        assertEquals(MediaDetails(MediaKind.Video, "MP4"), info.media)
    }

    // ---- resuming ----

    @Test fun acceptRangesNoneIsReportedAndWarnedAbout() {
        val info = success("$base/norange.mp4")
        assertEquals(false, info.acceptsRanges)
        assertTrue(LinkWarnings.of(info).contains(LinkWarning.NoResume))
    }

    @Test fun missingAcceptRangesHeaderMeansUnknownAndNoWarning() {
        val info = success("$base/silent.mp4")
        assertNull(info.acceptsRanges)
        assertFalse(LinkWarnings.of(info).contains(LinkWarning.NoResume))
    }

    @Test fun aPartialContentAnswerProvesRangeSupport() {
        val info = success("$base/nohead/clip.mp4")
        assertEquals(true, info.acceptsRanges)
        assertEquals(5000L, info.contentLength)
        assertEquals(MediaDetails(MediaKind.Video, "MP4"), info.media)
    }

    // ---- unknown size ----

    @Test fun mediaWithoutASizeIsFlaggedAsPossiblyEndless() {
        val info = success("$base/unsized.mp3")
        assertEquals(-1L, info.contentLength)
        val warnings = LinkWarnings.of(info)
        assertTrue(warnings.contains(LinkWarning.UnknownSize))
        assertTrue(warnings.contains(LinkWarning.MaybeLive))
    }

    @Test fun mediaWithAKnownSizeIsNotFlaggedAsPossiblyEndless() {
        assertFalse(LinkWarnings.of(success("$base/v/clip.mp4")).contains(LinkWarning.MaybeLive))
    }

    // ---- redirects ----

    @Test fun everySupportedRedirectStatusLeadsToTheFile() {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            val info = success("$base/r/$code")
            assertEquals("status $code", 1, info.redirectCount)
            assertEquals("status $code", "clip.mp4", info.fileName)
            assertEquals("status $code", MediaDetails(MediaKind.Video, "MP4"), info.media)
            assertEquals("status $code", "$base/r/$code", info.url)
        }
    }

    @Test fun redirectToAnotherHostIsFollowedAndTheFinalAddressIsReported() {
        val info = success("$base/r/cdn")
        assertEquals(1, info.redirectCount)
        assertEquals("$cdnBase/cdn/final.mp4", info.finalUrl)
        assertEquals("$base/r/cdn", info.url)
        assertEquals("final.mp4", info.fileName)
        assertEquals(7000L, info.contentLength)
        assertTrue(info.insecure)
    }

    @Test fun relativeRedirectWithParentSegmentIsResolved() {
        val info = success("$base/x/y/z")
        assertEquals(1, info.redirectCount)
        assertEquals("$base/x/clip.mp4", info.finalUrl)
        assertEquals(900L, info.contentLength)
    }

    @Test fun redirectToAStreamIsRefusedAndExplained() {
        val failure = failure("$base/r/hls")
        assertEquals(StreamKind.Hls, failure.stream)
        assertEquals(StreamKind.Hls.message, failure.message)
    }

    // ---- streams and playlists are refused with an explanation ----

    @Test fun hlsByMimeTypeIsRefused() {
        val failure = failure("$base/play/master")
        assertEquals(StreamKind.Hls, failure.stream)
        assertTrue(failure.message.contains("HLS"))
    }

    @Test fun dashByMimeTypeIsRefused() {
        assertEquals(StreamKind.Dash, failure("$base/dash/manifest.mpd").stream)
    }

    @Test fun hlsByAddressIsRefusedEvenWhenServedAsGenericType() {
        assertEquals(StreamKind.Hls, failure("$base/hls/plain.m3u8").stream)
    }

    @Test fun playlistIsRefusedAsAPlaylist() {
        val failure = failure("$base/radio.m3u")
        assertEquals(StreamKind.Playlist, failure.stream)
        assertTrue(failure.message.contains("playlist"))
    }

    @Test fun liveRadioIsRefusedFromItsHeaders() {
        val failure = failure("$base/radio")
        assertEquals(StreamKind.Live, failure.stream)
        assertTrue(failure.message.contains("live", ignoreCase = true))
    }

    // ---- access control is never bypassed ----

    @Test fun loginRequiredIsReportedNotBypassed() {
        val failure = failure("$base/private.mp4")
        assertTrue(failure.message, failure.message.contains("401") && failure.message.contains("does not bypass"))
        assertNull(failure.stream)
    }

    @Test fun notFoundIsAPlainFailure() {
        val failure = failure("$base/missing.mp4")
        assertTrue(failure.message.contains("404"))
        assertNull(failure.stream)
    }
}
