package com.almus.ytdownloader.browser

import android.net.http.SslError
import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserErrorsTest {
    @Test fun everyWebViewErrorCodeHasAMessage() {
        for (code in -16..-1) {
            val e = BrowserErrors.network(code, "https://example.com")
            assertTrue("code $code", e.title.isNotBlank() && e.detail.isNotBlank())
            assertEquals("https://example.com", e.failingUrl)
        }
    }

    @Test fun commonErrorsAreExplainedInPlainLanguage() {
        assertEquals("Site not found", BrowserErrors.network(WebViewClient.ERROR_HOST_LOOKUP, null).title)
        assertEquals("Could not connect", BrowserErrors.network(WebViewClient.ERROR_CONNECT, null).title)
        assertEquals("Connection timed out", BrowserErrors.network(WebViewClient.ERROR_TIMEOUT, null).title)
        assertEquals("Too many redirects", BrowserErrors.network(WebViewClient.ERROR_REDIRECT_LOOP, null).title)
        assertEquals("Page could not be loaded", BrowserErrors.network(12345, null).title)
        assertEquals(BrowserErrorKind.Network, BrowserErrors.network(WebViewClient.ERROR_HOST_LOOKUP, null).kind)
    }

    @Test fun sslHandshakeFailureIsASecurityErrorWithNoWayThrough() {
        val e = BrowserErrors.network(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, "https://bad.example")
        assertEquals(BrowserErrorKind.Security, e.kind)
        assertTrue(e.detail.contains("never lets you continue"))
    }

    @Test fun everyCertificateErrorIsASecurityErrorThatExplainsTheBlock() {
        for (primary in 0..5) {
            val e = BrowserErrors.ssl(primary, "https://bad.example")
            assertEquals(BrowserErrorKind.Security, e.kind)
            assertEquals("https://bad.example", e.failingUrl)
            assertTrue(e.detail.endsWith(BrowserErrors.SSL_BLOCKED_NOTE))
        }
    }

    @Test fun certificateErrorsHaveDistinctReasons() {
        val reasons = listOf(
            SslError.SSL_NOTYETVALID,
            SslError.SSL_EXPIRED,
            SslError.SSL_IDMISMATCH,
            SslError.SSL_UNTRUSTED,
            SslError.SSL_DATE_INVALID,
        ).map { BrowserErrors.ssl(it, null).detail }
        assertEquals(5, reasons.toSet().size)
        assertTrue(BrowserErrors.ssl(SslError.SSL_EXPIRED, null).detail.contains("expired"))
        assertTrue(BrowserErrors.ssl(SslError.SSL_IDMISMATCH, null).detail.contains("different website"))
        assertNotEquals(
            BrowserErrors.ssl(SslError.SSL_UNTRUSTED, null).detail,
            BrowserErrors.ssl(SslError.SSL_INVALID, null).detail,
        )
    }

    @Test fun httpStatusNotices() {
        assertNull(BrowserErrors.httpStatus(200))
        assertNull(BrowserErrors.httpStatus(304))
        assertNull(BrowserErrors.httpStatus(399))
        assertTrue(BrowserErrors.httpStatus(404)!!.contains("404"))
        assertTrue(BrowserErrors.httpStatus(403)!!.contains("does not bypass"))
        assertTrue(BrowserErrors.httpStatus(401)!!.contains("does not bypass"))
        assertTrue(BrowserErrors.httpStatus(503)!!.contains("503"))
        assertTrue(BrowserErrors.httpStatus(418)!!.contains("418"))
    }
}
