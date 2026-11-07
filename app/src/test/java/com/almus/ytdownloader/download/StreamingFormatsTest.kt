package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFormatsTest {
    private fun kind(url: String, mime: String? = null) = StreamingFormats.detect(url, mime)

    @Test fun manifestsAreRecognisedByAddress() {
        assertEquals(StreamKind.Hls, kind("https://e.com/v/master.m3u8"))
        assertEquals(StreamKind.Hls, kind("https://e.com/v/MASTER.M3U8?token=abc#t=1"))
        assertEquals(StreamKind.Dash, kind("https://e.com/manifest.mpd"))
        assertEquals(StreamKind.OtherAdaptive, kind("https://e.com/v.ism"))
        assertEquals(StreamKind.OtherAdaptive, kind("https://e.com/v.ism/Manifest"))
        assertEquals(StreamKind.OtherAdaptive, kind("https://e.com/v.f4m"))
    }

    @Test fun playlistsAreRecognisedByAddress() {
        assertEquals(StreamKind.Playlist, kind("https://e.com/radio.m3u"))
        assertEquals(StreamKind.Playlist, kind("https://e.com/radio.pls"))
        assertEquals(StreamKind.Playlist, kind("https://e.com/radio.asx"))
    }

    @Test fun manifestsAreRecognisedByMimeType() {
        assertEquals(StreamKind.Hls, kind("https://e.com/play", "application/vnd.apple.mpegurl"))
        assertEquals(StreamKind.Hls, kind("https://e.com/play", "Application/X-MPEGURL; charset=utf-8"))
        assertEquals(StreamKind.Dash, kind("https://e.com/play", "application/dash+xml"))
        assertEquals(StreamKind.OtherAdaptive, kind("https://e.com/play", "application/vnd.ms-sstr+xml"))
        assertEquals(StreamKind.Playlist, kind("https://e.com/play", "audio/x-mpegurl"))
    }

    @Test fun aManifestAddressServedAsGenericOrTextTypeIsStillAManifest() {
        assertEquals(StreamKind.Hls, kind("https://e.com/a.m3u8", "application/octet-stream"))
        assertEquals(StreamKind.Hls, kind("https://e.com/a.m3u8", "text/plain"))
        assertEquals(StreamKind.Dash, kind("https://e.com/a.mpd", "text/xml"))
    }

    @Test fun aRealMediaTypeMeansARealFileWhateverTheAddressLooksLike() {
        assertNull(kind("https://e.com/a.m3u8", "video/mp4"))
        assertNull(kind("https://e.com/a.mpd", "audio/mpeg"))
    }

    @Test fun ordinaryFilesAreNotStreams() {
        assertNull(kind("https://e.com/file.zip", "application/zip"))
        assertNull(kind("https://e.com/clip.mp4", "video/mp4"))
        assertNull(kind("https://e.com/not-m3u8.zip"))
        assertNull(kind("https://e.com/a.m3u8x"))
        assertNull(kind("https://e.com/song.mp3"))
        assertNull(kind("https://e.com/dir.m3u8/file.mp4"))
    }

    @Test fun radioServerHeadersMeanALiveStream() {
        assertEquals(StreamKind.Live, StreamingFormats.detectLive(mapOf("icy-name" to listOf("Radio"))))
        assertEquals(StreamKind.Live, StreamingFormats.detectLive(mapOf("Icy-Metaint" to listOf("16000"))))
        assertEquals(StreamKind.Live, StreamingFormats.detectLive(mapOf("ICE-AUDIO-INFO" to listOf("bitrate=128"))))
    }

    @Test fun ordinaryHeadersAreNotALiveStream() {
        assertNull(StreamingFormats.detectLive(null))
        assertNull(StreamingFormats.detectLive(emptyMap()))
        // The status line has a null key in HttpURLConnection.getHeaderFields().
        assertNull(StreamingFormats.detectLive(mapOf(null to listOf("HTTP/1.1 200 OK"), "Content-Type" to listOf("audio/mpeg"))))
    }

    @Test fun everyKindHasItsOwnExplanation() {
        val messages = StreamKind.entries.map { it.message }
        assertTrue(messages.all { it.length > 40 })
        assertEquals(messages.size, messages.toSet().size)
        assertNotEquals(StreamKind.Hls.message, StreamKind.Dash.message)
        assertTrue(StreamKind.Live.message.contains("live", ignoreCase = true))
        assertTrue(StreamKind.Hls.message.contains("HLS"))
    }
}
