package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    private fun valid(input: String): UrlValidation.Valid {
        val result = UrlValidator.validate(input)
        assertTrue("expected valid but was $result", result is UrlValidation.Valid)
        return result as UrlValidation.Valid
    }

    private fun assertInvalid(input: String?, expected: UrlError) {
        val result = UrlValidator.validate(input)
        assertEquals(UrlValidation.Invalid(expected), result)
    }

    @Test fun acceptsHttps() {
        val v = valid("https://example.com/file.zip")
        assertEquals("https://example.com/file.zip", v.url)
        assertEquals("example.com", v.host)
        assertTrue(v.isSecure)
    }

    @Test fun acceptsHttpAndMarksItInsecure() {
        val v = valid("http://example.com/a.pdf")
        assertFalse(v.isSecure)
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com/a", valid("  https://example.com/a \n").url)
    }

    @Test fun lowercasesSchemeSoDownloadManagerAcceptsIt() {
        assertEquals("https://Example.com/A", valid("HTTPS://Example.com/A").url)
    }

    @Test fun acceptsPortQueryFragmentAndIpAddress() {
        valid("http://192.168.1.10:8080/files/a.mp4?token=abc&x=1#frag")
        valid("https://example.com:443/")
        valid("http://[::1]:8080/a.txt")
        valid("https://my_server.example.com/file.bin")
    }

    @Test fun rejectsEmptyAndNull() {
        assertInvalid("", UrlError.Empty)
        assertInvalid("   ", UrlError.Empty)
        assertInvalid(null, UrlError.Empty)
    }

    @Test fun rejectsUnsupportedSchemes() {
        listOf(
            "ftp://example.com/a.zip",
            "file:///sdcard/a.zip",
            "content://media/external/file/1",
            "javascript:alert(1)",
            "data:text/plain;base64,AAAA",
            "intent://scan/#Intent;scheme=zxing;end",
            "magnet:?xt=urn:btih:abc",
        ).forEach { assertInvalid(it, UrlError.UnsupportedScheme) }
    }

    @Test fun rejectsMissingScheme() {
        assertInvalid("example.com/file.zip", UrlError.MissingScheme)
        assertInvalid("www.example.com", UrlError.MissingScheme)
        assertInvalid("//example.com/file.zip", UrlError.MissingScheme)
    }

    @Test fun rejectsMissingHost() {
        assertInvalid("http://", UrlError.MissingHost)
        assertInvalid("https:///file.zip", UrlError.MissingHost)
        assertInvalid("https://?x=1", UrlError.MissingHost)
    }

    @Test fun rejectsEmbeddedCredentials() {
        assertInvalid("https://user:pass@example.com/a.zip", UrlError.HasCredentials)
        assertInvalid("http://user@example.com/a.zip", UrlError.HasCredentials)
    }

    @Test fun rejectsSpacesAndControlCharacters() {
        assertInvalid("https://example.com/my file.zip", UrlError.Malformed)
        assertInvalid("https://example.com/a\u0000b", UrlError.Malformed)
        assertInvalid("https://example.com/a\tb", UrlError.Malformed)
    }

    @Test fun rejectsMalformedHostsAndPorts() {
        assertInvalid("https://exa mple.com/", UrlError.Malformed)
        assertInvalid("https://-bad.com/", UrlError.Malformed)
        assertInvalid("https://example.com:99999/", UrlError.Malformed)
        assertInvalid("https://example.com:abc/", UrlError.Malformed)
        assertInvalid("https://example.com:/a", UrlError.Malformed)
        assertInvalid("https://a:b:c/", UrlError.Malformed)
        assertInvalid("http:example.com", UrlError.Malformed)
    }

    @Test fun rejectsOverlongLinks() {
        val long = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH)
        assertInvalid(long, UrlError.TooLong)
    }

    @Test fun everyErrorHasAReadableMessage() {
        UrlError.entries.forEach { assertTrue(it.message.isNotBlank()) }
    }
}
