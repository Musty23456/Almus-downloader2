package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeadersTest {
    @Test fun parsesContentRange() {
        assertEquals(ContentRange(100, 199, 1000), HttpHeaders.parseContentRange("bytes 100-199/1000"))
        assertEquals(ContentRange(0, 0, 1), HttpHeaders.parseContentRange("bytes 0-0/1"))
        assertEquals(ContentRange(5, 9, null), HttpHeaders.parseContentRange("bytes 5-9/*"))
        assertEquals(ContentRange(5, 9, 10), HttpHeaders.parseContentRange("  BYTES 5-9/10  "))
    }

    @Test fun rejectsMalformedContentRange() {
        listOf(null, "", "bytes", "bytes 9-5/10", "bytes a-b/c", "items 0-1/2", "bytes 0-1", "bytes */10", "bytes 0-1/-5")
            .forEach { assertNull("$it", HttpHeaders.parseContentRange(it)) }
    }

    @Test fun parsesUnsatisfiedRange() {
        assertEquals(1000L, HttpHeaders.parseUnsatisfiedTotal("bytes */1000"))
        assertNull(HttpHeaders.parseUnsatisfiedTotal("bytes 0-1/2"))
        assertNull(HttpHeaders.parseUnsatisfiedTotal(null))
    }

    @Test fun readsAcceptRanges() {
        assertEquals(true, HttpHeaders.acceptsRanges("bytes"))
        assertEquals(true, HttpHeaders.acceptsRanges("Bytes"))
        assertEquals(false, HttpHeaders.acceptsRanges("none"))
        assertEquals(false, HttpHeaders.acceptsRanges(" NONE "))
        assertNull(HttpHeaders.acceptsRanges(null))
        assertNull(HttpHeaders.acceptsRanges(""))
        assertNull(HttpHeaders.acceptsRanges("something"))
    }

    @Test fun weakEtagsAreNotStrong() {
        assertTrue(HttpHeaders.isStrongEtag("\"abc\""))
        assertFalse(HttpHeaders.isStrongEtag("W/\"abc\""))
        assertFalse(HttpHeaders.isStrongEtag("w/\"abc\""))
    }

    @Test fun retryAfterIsSecondsOnly() {
        assertEquals(5000L, HttpHeaders.retryAfterMs("5"))
        assertEquals(0L, HttpHeaders.retryAfterMs("0"))
        assertNull(HttpHeaders.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(HttpHeaders.retryAfterMs("-3"))
        assertNull(HttpHeaders.retryAfterMs("999999999"))
        assertNull(HttpHeaders.retryAfterMs(null))
    }

    @Test fun contentLength() {
        assertEquals(10L, HttpHeaders.contentLength("10"))
        assertEquals(0L, HttpHeaders.contentLength("0"))
        assertNull(HttpHeaders.contentLength("-1"))
        assertNull(HttpHeaders.contentLength("abc"))
        assertNull(HttpHeaders.contentLength(null))
    }
}
