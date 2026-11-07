package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal fun parsed(url: String): ParsedUrl = ParsedUrl.parse(UrlValidator.validate(url) as UrlValidation.Valid)

class ParsedUrlTest {
    @Test fun splitsHostPathAndQuery() {
        val p = parsed("https://Www.Example.com:8443/a/b%20c/file.MP4?x=1&Y=two&x=3#frag")
        assertEquals("www.example.com", p.host)
        assertEquals(8443, p.port)
        assertEquals(listOf("a", "b c", "file.MP4"), p.segments)
        assertEquals("1", p.param("x"))
        assertEquals("two", p.param("y"))
        assertEquals("mp4", p.extension)
        assertEquals("file.MP4", p.lastSegment)
    }

    @Test fun noPathMeansNoSegments() {
        val p = parsed("https://example.com")
        assertEquals(emptyList<String>(), p.segments)
        assertNull(p.extension)
        assertNull(p.port)
    }

    @Test fun fragmentIsIgnored() {
        assertEquals(listOf("a"), parsed("https://example.com/a#/b/c").segments)
    }

    @Test fun percentEscapesThatAreNotUtf8DoNotCrash() {
        assertEquals(1, parsed("https://example.com/%ff").segments.size)
    }

    @Test fun extensionNeedsADotAndReasonableLength() {
        assertNull(parsed("https://example.com/readme").extension)
        assertNull(parsed("https://example.com/.hidden").extension)
        assertNull(parsed("https://example.com/a.verylongextension").extension)
    }

    @Test fun ipv6HostWithPort() {
        val p = parsed("http://[::1]:8080/x")
        assertEquals(8080, p.port)
        assertEquals(listOf("x"), p.segments)
    }

    @Test fun manyQueryParametersAreCapped() {
        val query = (1..200).joinToString("&") { "p$it=v" }
        val p = parsed("https://example.com/?$query")
        assertEquals("v", p.param("p1"))
        assertNull(p.param("p200"))
    }
}
