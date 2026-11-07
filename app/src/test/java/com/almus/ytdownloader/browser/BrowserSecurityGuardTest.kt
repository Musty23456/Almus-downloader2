package com.almus.ytdownloader.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the app's own source code and fails if anything that would weaken the browser's security
 * is ever added. Comments are ignored, so explanations that mention these names are fine.
 * Gradle runs unit tests with the module folder (app/) as working directory.
 */
class BrowserSecurityGuardTest {
    private val sources: List<Pair<String, String>> by lazy {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main sources not found from ${File(".").absolutePath}")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.path to stripComments(it.readText()) }
            .toList()
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("(?m)(^|\\s)//[^\\n]*"), " ")

    private fun assertNone(description: String, pattern: Regex) {
        val hits = sources.filter { pattern.containsMatchIn(it.second) }.map { it.first }
        assertTrue("$description found in: $hits", hits.isEmpty())
    }

    @Test fun sourcesWereFound() {
        assertTrue(sources.any { it.first.endsWith("BrowserWebView.kt") })
        assertTrue(sources.size > 20)
    }

    @Test fun certificateErrorsAreNeverBypassed() {
        assertNone("SslErrorHandler.proceed()", Regex("\\.proceed\\s*\\("))
        assertNone("custom trust manager", Regex("TrustManager|HostnameVerifier|setDefaultHostnameVerifier|setDefaultSSLSocketFactory"))
        val webView = sources.first { it.first.endsWith("BrowserWebView.kt") }.second
        assertTrue("onReceivedSslError must cancel", Regex("onReceivedSslError[\\s\\S]{0,200}handler\\.cancel\\(\\)").containsMatchIn(webView))
    }

    @Test fun noJavaScriptBridgeIsExposedToWebPages() {
        assertNone("addJavascriptInterface", Regex("addJavascriptInterface"))
        assertNone("JavascriptInterface annotation", Regex("@JavascriptInterface"))
        assertNone("evaluateJavascript", Regex("evaluateJavascript"))
    }

    @Test fun javaScriptIsNeverTurnedOnUnconditionally() {
        assertNone("javaScriptEnabled = true", Regex("javaScriptEnabled\\s*=\\s*true"))
        assertNone("setJavaScriptEnabled(true)", Regex("setJavaScriptEnabled\\(\\s*true"))
        val webView = sources.first { it.first.endsWith("BrowserWebView.kt") }.second
        assertTrue("default is off", Regex("javaScriptEnabled\\s*=\\s*false").containsMatchIn(webView))
    }

    @Test fun cookiesAreNeverReadOrCopied() {
        assertNone("reading cookies", Regex("getCookie\\s*\\("))
        assertNone("cookie header", Regex("\"Cookie\"|\"cookie\"|addRequestHeader\\s*\\(\\s*\"Cookie"))
        assertNone("third-party cookies on", Regex("setAcceptThirdPartyCookies\\([^)]*,\\s*true"))
    }

    @Test fun webViewDoesNotTouchLocalFilesOrMixedContent() {
        assertNone("file access on", Regex("allow(File|Content)Access\\s*=\\s*true|allow(Universal|File)AccessFromFileURLs\\s*=\\s*true"))
        assertNone("mixed content allowed", Regex("MIXED_CONTENT_ALWAYS_ALLOW|MIXED_CONTENT_COMPATIBILITY_MODE"))
        assertNone("geolocation on", Regex("setGeolocationEnabled\\(\\s*true"))
        assertNone("remote debugging on", Regex("setWebContentsDebuggingEnabled\\(\\s*true"))
    }

    @Test fun pagesCannotGrantThemselvesPermissions() {
        val webView = sources.first { it.first.endsWith("BrowserWebView.kt") }.second
        assertTrue(Regex("onPermissionRequest[\\s\\S]{0,120}request\\.deny\\(\\)").containsMatchIn(webView))
        assertNone("permission grant", Regex("request\\.grant\\s*\\("))
    }
}
