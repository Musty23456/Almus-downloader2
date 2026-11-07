package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.UrlKey
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import com.almus.ytdownloader.platform.UrlSafety

/**
 * Opens a link and follows its redirects one by one, checking every hop:
 * only http/https (via [UrlValidator]), at most [maxRedirects], no circles, never https to http,
 * never from a public address to a device on a local network. The same headers are sent on every hop;
 * there are no cookies and no login data to leak to another host.
 */
class HttpSession(
    private val transport: HttpTransport,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    class Opened(val response: TransportResponse, val finalUrl: String)

    /** The caller must close [Opened.response]. Throws [DownloadRefused] for an unsafe or invalid path. */
    suspend fun open(url: String, buildRequest: (String) -> TransportRequest): Opened {
        val first = UrlValidator.validate(url) as? UrlValidation.Valid
            ?: throw DownloadRefused("This is not a valid web address.")
        var current = first.url
        var currentHost = first.host
        val seen = hashSetOf(UrlKey.of(current))
        var redirects = 0

        while (true) {
            val response = transport.get(buildRequest(current))
            if (response.code !in REDIRECT_CODES) return Opened(response, current)

            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw DownloadRefused("The link redirects without saying where to.")
            if (redirects >= maxRedirects) throw DownloadRefused("The link redirects too many times.")
            val next = resolve(current, location) ?: throw DownloadRefused("The link redirects to an address that is not valid.")
            val checked = UrlValidator.validate(next) as? UrlValidation.Valid
                ?: throw DownloadRefused("The link redirects to an unsupported address, so it was blocked.")
            if (current.startsWith("https://", ignoreCase = true) && !checked.isSecure) {
                throw DownloadRefused("The link redirects from a secure (https) to an insecure (http) address, so it was blocked.")
            }
            if (!UrlSafety.isLocalOrPrivate(currentHost) && UrlSafety.isLocalOrPrivate(checked.host)) {
                throw DownloadRefused("The link redirects to a device on a local network, which is blocked for your safety.")
            }
            if (!seen.add(UrlKey.of(checked.url))) throw DownloadRefused("The link redirects in a circle.")
            current = checked.url
            currentHost = checked.host
            redirects++
        }
    }

    private fun resolve(base: String, location: String): String? = try {
        java.net.URL(java.net.URL(base), location).toString()
    } catch (_: java.net.MalformedURLException) {
        null
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
