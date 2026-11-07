package com.almus.ytdownloader.download

import com.almus.ytdownloader.browser.DownloadRoute
import com.almus.ytdownloader.browser.DownloadRouting
import com.almus.ytdownloader.files.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {
    @Test fun newMediaExtensionsHaveTypes() {
        assertEquals("video/x-m4v", MimeTypes.forExtension("m4v"))
        assertEquals("video/ogg", MimeTypes.forExtension("ogv"))
        assertEquals("video/mpeg", MimeTypes.forExtension("MPEG"))
        assertEquals("audio/opus", MimeTypes.forExtension("opus"))
        assertEquals("audio/x-ms-wma", MimeTypes.forExtension(".wma"))
        assertEquals("audio/aiff", MimeTypes.forExtension("aif"))
    }

    @Test fun addingExtensionsDidNotChangeWhichExtensionATypeMapsBackTo() {
        assertEquals("mp3", MimeTypes.extensionFor("audio/mpeg"))
        assertEquals("m4a", MimeTypes.extensionFor("audio/mp4"))
        assertEquals("mp4", MimeTypes.extensionFor("video/mp4"))
        assertEquals("ogg", MimeTypes.extensionFor("audio/ogg"))
        assertEquals("mpg", MimeTypes.extensionFor("video/mpeg"))
        assertEquals("webm", MimeTypes.extensionFor("video/webm"))
    }

    @Test fun extensionIsAddedFromAMediaTypeWhenTheNameHasNone() {
        assertEquals("clip.webm", FileNameResolver.resolve(null, "video/webm", "https://example.com/clip"))
        assertEquals("x.opus", FileNameResolver.resolve(null, "audio/opus", "https://example.com/x"))
        assertEquals("clip.mp4", FileNameResolver.resolve(null, "video/mp4", "https://example.com/clip.mp4"))
    }

    @Test fun fileManagerGroupsNewFormatsAsVideoOrMusic() {
        listOf("a.m4v", "a.ogv", "a.mpg", "a.wmv", "a.flv").forEach {
            assertEquals(it, FileKind.Videos, FileKind.classify(it, null))
        }
        listOf("a.opus", "a.oga", "a.wma", "a.aiff", "a.amr", "a.weba").forEach {
            assertEquals(it, FileKind.Music, FileKind.classify(it, null))
        }
        assertEquals(FileKind.Videos, FileKind.classify("download", "video/webm"))
        assertEquals(FileKind.Music, FileKind.classify("download", "audio/opus"))
    }

    @Test fun directMediaLinksStillGoToTheDownloadManager() {
        listOf(
            "https://example.com/a.mp4" to "video/mp4",
            "https://example.com/a.webm" to "video/webm",
            "https://example.com/a.mp3" to "audio/mpeg",
            "https://example.com/a.m4a" to "audio/mp4",
        ).forEach { (url, mime) ->
            assertEquals(DownloadRoute.ToManager(url), DownloadRouting.route(url, mime, null))
        }
    }

    @Test fun routingSharesTheStreamDetectionOfTheLinkCheck() {
        listOf(
            "https://example.com/v.ism/Manifest",
            "https://example.com/radio.pls",
            "https://example.com/radio.asx",
            "https://example.com/a.f4m",
        ).forEach { url ->
            val route = DownloadRouting.route(url, null, null)
            assertTrue("$url gave $route", route is DownloadRoute.Unsupported)
            assertEquals(DownloadRouting.STREAMING_MESSAGE, (route as DownloadRoute.Unsupported).message)
        }
    }

    @Test fun aRealMediaTypeIsADownloadableFileEvenWithAPlaylistLookingAddress() {
        assertEquals(
            DownloadRoute.ToManager("https://example.com/odd.m3u8"),
            DownloadRouting.route("https://example.com/odd.m3u8", "video/mp4", null),
        )
    }

    @Test fun streamingMessagePointsToADirectFile() {
        assertTrue(DownloadRouting.STREAMING_MESSAGE.contains("direct link"))
    }
}
