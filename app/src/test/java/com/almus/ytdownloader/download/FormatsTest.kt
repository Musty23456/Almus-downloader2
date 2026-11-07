package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormatsTest {
    @Test fun speedLabel() {
        assertEquals("1.0 MB/s", ByteFormat.speed(1024L * 1024))
        assertEquals("512 B/s", ByteFormat.speed(512))
    }

    @Test fun remainingTime() {
        assertEquals("about 1 s left", DurationFormat.remaining(1))
        assertEquals("about 45 s left", DurationFormat.remaining(45))
        assertEquals("about 1 min left", DurationFormat.remaining(60))
        assertEquals("about 2 min left", DurationFormat.remaining(61))
        assertEquals("about 59 min left", DurationFormat.remaining(3540))
        assertEquals("about 1 h left", DurationFormat.remaining(3600))
        assertEquals("about 1 h 1 min left", DurationFormat.remaining(3601))
        assertEquals("about 2 h left", DurationFormat.remaining(7199))
        assertEquals("about 5 h 30 min left", DurationFormat.remaining(5 * 3600 + 30 * 60))
    }

    @Test fun urlKeyIgnoresCaseOfSchemeAndHostDefaultPortsAndFragments() {
        val key = UrlKey.of("https://example.com/a/b.zip")
        assertEquals(key, UrlKey.of("HTTPS://Example.COM/a/b.zip"))
        assertEquals(key, UrlKey.of("https://example.com:443/a/b.zip"))
        assertEquals(key, UrlKey.of("https://example.com/a/b.zip#top"))
        assertEquals(key, UrlKey.of("  https://example.com/a/b.zip  "))
    }

    @Test fun urlKeyTreatsBareHostAndRootPathAsEqual() {
        assertEquals(UrlKey.of("http://example.com"), UrlKey.of("http://example.com/"))
        assertEquals(UrlKey.of("http://example.com?x=1"), UrlKey.of("http://example.com/?x=1"))
        assertEquals(UrlKey.of("http://example.com:80/"), UrlKey.of("http://example.com/"))
    }

    @Test fun urlKeyKeepsPathQueryAndNonDefaultPortsDistinct() {
        val base = UrlKey.of("https://example.com/a.zip")
        assertNotEquals(base, UrlKey.of("https://example.com/A.zip"))
        assertNotEquals(base, UrlKey.of("https://example.com/a.zip?x=1"))
        assertNotEquals(base, UrlKey.of("https://example.com:8443/a.zip"))
        assertNotEquals(base, UrlKey.of("http://example.com/a.zip"))
        assertNotEquals(UrlKey.of("http://example.com:443/"), UrlKey.of("http://example.com/"))
    }
}
