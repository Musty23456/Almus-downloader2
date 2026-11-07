package com.almus.ytdownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRoutingTest {
    private fun unsupported(url: String?, mime: String? = null): String {
        val r = DownloadRouting.route(url, mime, null)
        assertTrue("expected Unsupported for <$url> but was $r", r is DownloadRoute.Unsupported)
        return (r as DownloadRoute.Unsupported).message
    }

    @Test fun directFileLinksGoToTheDownloadManager() {
        assertEquals(
            DownloadRoute.ToManager("https://example.com/file.zip"),
            DownloadRouting.route("https://example.com/file.zip", "application/zip", null),
        )
        assertEquals(
            DownloadRoute.ToManager("http://example.com/a.pdf?x=1"),
            DownloadRouting.route("http://example.com/a.pdf?x=1", null, "attachment; filename=a.pdf"),
        )
        assertEquals(
            DownloadRoute.ToManager("https://example.com/not-m3u8.zip"),
            DownloadRouting.route("https://example.com/not-m3u8.zip"),
        )
    }

    @Test fun pageGeneratedDownloadsAreExplainedNotAttempted() {
        assertEquals(DownloadRouting.INLINE_MESSAGE, unsupported("blob:https://example.com/uuid"))
        assertEquals(DownloadRouting.INLINE_MESSAGE, unsupported("data:text/plain;base64,aGk="))
    }

    @Test fun otherSchemesAndEmptyAreRejected() {
        assertEquals("Only http and https downloads are supported.", unsupported("ftp://example.com/x"))
        assertEquals("Only http and https downloads are supported.", unsupported("javascript:alert(1)"))
        assertEquals("This download has no address.", unsupported(null))
        assertEquals("This download has no address.", unsupported("  "))
    }

    @Test fun addressesWithCredentialsAreRejected() {
        unsupported("https://user:pw@example.com/f.zip")
    }

    @Test fun streamingPlaylistsAreNotDownloadable() {
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/video/master.m3u8"))
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/video/master.M3U8?token=abc"))
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/manifest.mpd#t=1"))
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/v.ism"))
    }

    @Test fun streamingMimeTypesAreNotDownloadableEvenWithoutAnExtension() {
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/play", "application/vnd.apple.mpegurl"))
        assertEquals(
            DownloadRouting.STREAMING_MESSAGE,
            unsupported("https://example.com/play", "Application/X-MPEGURL; charset=utf-8"),
        )
        assertEquals(DownloadRouting.STREAMING_MESSAGE, unsupported("https://example.com/play", "application/dash+xml"))
    }

    @Test fun throttleAllowsOnePromptPerInterval() {
        var now = 10_000L
        val throttle = DownloadPromptThrottle(minIntervalMs = 3_000, clock = { now })
        assertTrue(throttle.tryAcquire())
        now = 10_000 + 1_000
        assertFalse(throttle.tryAcquire())
        now = 10_000 + 2_999
        assertFalse(throttle.tryAcquire())
        now = 10_000 + 3_000
        assertTrue(throttle.tryAcquire())
        now += 1_000
        assertFalse(throttle.tryAcquire())
    }

    @Test fun throttleBlockedAttemptsDoNotExtendTheWindow() {
        var now = 0L
        val throttle = DownloadPromptThrottle(minIntervalMs = 3_000, clock = { now })
        assertTrue(throttle.tryAcquire())
        now = 2_000
        assertFalse(throttle.tryAcquire())
        now = 3_000
        assertTrue(throttle.tryAcquire())
    }
}
