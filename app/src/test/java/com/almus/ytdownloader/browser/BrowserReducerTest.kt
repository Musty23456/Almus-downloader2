package com.almus.ytdownloader.browser

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserReducerTest {
    private val start = BrowserUiState()

    @Test fun startingAPageShowsItsAddressAndLoading() {
        val s = BrowserReducer.pageStarted(start, "https://example.com/a")
        assertEquals("https://example.com/a", s.url)
        assertTrue(s.isLoading)
        assertEquals(0, s.progress)
        assertEquals("example.com", s.host)
        assertEquals(PageSecurity.Encrypted, s.security)
    }

    @Test fun theBlankPageIsTheStartPage() {
        val s = BrowserReducer.pageStarted(start.copy(url = "https://a.com"), "about:blank")
        assertNull(s.url)
        assertFalse(s.isLoading)
        assertEquals(PageSecurity.None, s.security)
    }

    @Test fun startingANewPageClearsErrorsAndNotices() {
        val failed = BrowserReducer.networkError(
            BrowserReducer.notice(start, "old"), "https://a.com", WebViewClient.ERROR_HOST_LOOKUP,
        )
        val s = BrowserReducer.pageStarted(failed, "https://b.com")
        assertNull(s.error)
        assertNull(s.notice)
        assertNull(s.addressError)
    }

    @Test fun progressIsClampedAndFinishesLoading() {
        var s = BrowserReducer.pageStarted(start, "https://a.com")
        s = BrowserReducer.progress(s, 40)
        assertEquals(40, s.progress)
        assertTrue(s.isLoading)
        assertEquals(0, BrowserReducer.progress(s, -5).progress)
        s = BrowserReducer.progress(s, 250)
        assertEquals(100, s.progress)
        assertFalse(s.isLoading)
    }

    @Test fun finishingUpdatesButtons() {
        val s = BrowserReducer.pageFinished(
            BrowserReducer.pageStarted(start, "https://a.com"), "https://a.com/x", canGoBack = true, canGoForward = false,
        )
        assertFalse(s.isLoading)
        assertEquals(100, s.progress)
        assertTrue(s.canGoBack)
        assertFalse(s.canGoForward)
        assertEquals("https://a.com/x", s.url)
    }

    @Test fun historyChangeUpdatesAddressAndButtonsOnly() {
        val loading = BrowserReducer.pageStarted(start, "https://a.com")
        val s = BrowserReducer.historyChanged(loading, "https://a.com/spa", canGoBack = true, canGoForward = true)
        assertEquals("https://a.com/spa", s.url)
        assertTrue(s.canGoBack && s.canGoForward)
        assertTrue(s.isLoading)
    }

    @Test fun stopEndsLoadingButKeepsThePage() {
        val s = BrowserReducer.stopped(BrowserReducer.pageStarted(start, "https://a.com"))
        assertFalse(s.isLoading)
        assertEquals("https://a.com", s.url)
    }

    @Test fun loadErrorIsShownAndSurvivesPageFinished() {
        var s = BrowserReducer.pageStarted(start, "https://nope.invalid")
        s = BrowserReducer.networkError(s, "https://nope.invalid", WebViewClient.ERROR_HOST_LOOKUP)
        assertEquals("Site not found", s.error?.title)
        assertFalse(s.isLoading)
        // WebView calls onPageFinished after an error: the error panel must stay.
        s = BrowserReducer.pageFinished(s, "https://nope.invalid", canGoBack = false, canGoForward = false)
        assertEquals("Site not found", s.error?.title)
    }

    @Test fun certificateErrorIsNotOverwrittenByTheGenericErrorThatFollows() {
        var s = BrowserReducer.pageStarted(start, "https://bad.example")
        s = BrowserReducer.sslError(s, "https://bad.example", android.net.http.SslError.SSL_EXPIRED)
        val first = s.error
        s = BrowserReducer.networkError(s, "https://bad.example", WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)
        assertSame(first, s.error)
        assertEquals(BrowserErrorKind.Security, s.error?.kind)
        // A different page that fails is a different error.
        s = BrowserReducer.networkError(s, "https://other.example", WebViewClient.ERROR_CONNECT)
        assertEquals("Could not connect", s.error?.title)
    }

    @Test fun httpErrorStatusBecomesANoticeAndOkStatusChangesNothing() {
        val s = BrowserReducer.pageStarted(start, "https://a.com")
        assertTrue(BrowserReducer.httpStatus(s, 404).notice!!.contains("404"))
        assertSame(s, BrowserReducer.httpStatus(s, 200))
    }

    @Test fun rendererCrashShowsErrorAndRebuildsTheEngine() {
        val s = BrowserReducer.rendererGone(
            BrowserReducer.pageFinished(BrowserReducer.pageStarted(start, "https://a.com"), "https://a.com", true, true),
        )
        assertEquals(BrowserErrorKind.Renderer, s.error?.kind)
        assertEquals("https://a.com", s.error?.failingUrl)
        assertEquals(1, s.engineGeneration)
        assertFalse(s.canGoBack || s.canGoForward || s.isLoading)
    }

    @Test fun titleIgnoresBlankValues() {
        assertEquals("Hello", BrowserReducer.title(start, "Hello").title)
        assertNull(BrowserReducer.title(start.copy(title = "x"), "  ").title)
        assertNull(BrowserReducer.title(start.copy(title = "x"), null).title)
        assertNull(BrowserReducer.title(start.copy(title = "x"), "about:blank").title)
    }

    @Test fun resetReturnsToTheStartPageButKeepsEngineAndPrivacyChoice() {
        val busy = BrowserReducer.rendererGone(BrowserReducer.pageStarted(start.copy(privacyAccepted = true), "https://a.com"))
        val s = BrowserReducer.reset(busy)
        assertNull(s.url)
        assertNull(s.error)
        assertFalse(s.canGoBack)
        assertEquals(busy.engineGeneration, s.engineGeneration)
        assertTrue(s.privacyAccepted)
    }

    @Test fun privacyDialogAndAcceptance() {
        var s = BrowserReducer.privacyDialog(start, true)
        assertTrue(s.privacyDialogVisible)
        assertFalse(s.privacyAccepted)
        s = BrowserReducer.privacyAccepted(s)
        assertTrue(s.privacyAccepted)
        assertFalse(s.privacyDialogVisible)
    }

    @Test fun pageSecurityLabels() {
        assertEquals(PageSecurity.Encrypted, PageSecurity.of("https://a.com"))
        assertEquals(PageSecurity.Encrypted, PageSecurity.of("HTTPS://a.com"))
        assertEquals(PageSecurity.NotEncrypted, PageSecurity.of("http://a.com"))
        assertEquals(PageSecurity.None, PageSecurity.of(null))
        assertEquals(PageSecurity.None, PageSecurity.of("about:blank"))
    }
}
