package com.almus.ytdownloader.browser

import android.net.http.SslError
import android.webkit.WebViewClient

enum class BrowserErrorKind { Network, Security, Renderer }

/** A page that could not be shown. [title] and [detail] are shown to the user as is. */
data class BrowserError(
    val kind: BrowserErrorKind,
    val title: String,
    val detail: String,
    val failingUrl: String?,
)

/**
 * Plain-language messages for WebView failures. The Android constants are compile-time values,
 * so this file can be unit tested on the JVM.
 */
object BrowserErrors {
    const val SSL_BLOCKED_NOTE =
        "The connection was stopped to protect you. This browser never lets you continue past a certificate error."

    fun network(code: Int, url: String?): BrowserError {
        if (code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
            return BrowserError(
                kind = BrowserErrorKind.Security,
                title = "Secure connection failed",
                detail = "A secure connection could not be set up with this site. $SSL_BLOCKED_NOTE",
                failingUrl = url,
            )
        }
        val (title, detail) = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP ->
                "Site not found" to "The address could not be found. Check the spelling and your internet connection."
            WebViewClient.ERROR_CONNECT ->
                "Could not connect" to "The site did not accept the connection. It may be down, or your internet is off."
            WebViewClient.ERROR_TIMEOUT ->
                "Connection timed out" to "The site took too long to answer. Try again in a moment."
            WebViewClient.ERROR_IO ->
                "Connection interrupted" to "The connection broke while loading. Check your internet and try again."
            WebViewClient.ERROR_REDIRECT_LOOP ->
                "Too many redirects" to "The site keeps sending you in circles. Clearing browsing data may help."
            WebViewClient.ERROR_BAD_URL ->
                "Bad address" to "This web address is not valid."
            WebViewClient.ERROR_UNSUPPORTED_SCHEME ->
                "Unsupported link" to "This kind of link cannot be opened in the browser."
            WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME,
            WebViewClient.ERROR_AUTHENTICATION,
            WebViewClient.ERROR_PROXY_AUTHENTICATION ->
                "Sign-in not supported" to "This page asks for a browser login prompt, which this browser does not handle."
            WebViewClient.ERROR_TOO_MANY_REQUESTS ->
                "Too many requests" to "The site is limiting requests. Wait a little and try again."
            WebViewClient.ERROR_UNSAFE_RESOURCE ->
                "Blocked as unsafe" to "Safe Browsing marked this page as dangerous, so it was not opened."
            WebViewClient.ERROR_FILE, WebViewClient.ERROR_FILE_NOT_FOUND ->
                "Page unavailable" to "The page could not be read."
            else ->
                "Page could not be loaded" to "Something went wrong while loading this page."
        }
        return BrowserError(BrowserErrorKind.Network, title, detail, url)
    }

    fun ssl(primaryError: Int, url: String?): BrowserError {
        val reason = when (primaryError) {
            SslError.SSL_NOTYETVALID -> "The site's certificate is not valid yet. Check that your phone's date and time are correct."
            SslError.SSL_EXPIRED -> "The site's certificate has expired."
            SslError.SSL_IDMISMATCH -> "The certificate was issued for a different website address."
            SslError.SSL_UNTRUSTED -> "The certificate was not issued by an authority this phone trusts."
            SslError.SSL_DATE_INVALID -> "The date of the site's certificate is invalid."
            else -> "The site's certificate is not valid."
        }
        return BrowserError(
            kind = BrowserErrorKind.Security,
            title = "Connection is not secure",
            detail = "$reason $SSL_BLOCKED_NOTE",
            failingUrl = url,
        )
    }

    fun rendererGone(url: String?) = BrowserError(
        kind = BrowserErrorKind.Renderer,
        title = "The page stopped working",
        detail = "The web engine ran out of memory or crashed while showing this page. Reload to try again.",
        failingUrl = url,
    )

    /** A short notice for a page that loaded but answered with an error status, or null when it is fine. */
    fun httpStatus(status: Int): String? = when {
        status < 400 -> null
        status == 401 || status == 403 -> "The site refused access ($status). This browser does not bypass logins or restrictions."
        status == 404 -> "Page not found (404)."
        status == 410 -> "This page is gone (410)."
        status == 429 -> "The site is limiting requests (429). Wait a little and try again."
        status in 500..599 -> "The site has a problem on its side ($status). Try again later."
        else -> "The site answered with an error ($status)."
    }
}
