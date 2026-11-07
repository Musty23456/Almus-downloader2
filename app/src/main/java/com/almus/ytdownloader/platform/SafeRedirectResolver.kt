package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.LinkInspector
import com.almus.ytdownloader.download.UrlKey
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface RedirectTrace {
    /** [hops] lists every address visited, starting with the one given. [status] is the last HTTP status. */
    data class Resolved(val finalUrl: String, val hops: List<String>, val status: Int) : RedirectTrace

    /** [message] is shown to the user as is. */
    data class Failed(val message: String) : RedirectTrace
}

/**
 * Follows the redirects of a short link by hand to learn where it leads, without opening or saving anything.
 * Safety rules, all enforced here and tested:
 * - only http and https addresses; each hop is validated by [UrlValidator],
 * - at most [MAX_REDIRECTS] hops and no circles,
 * - never from https to http,
 * - never from a public address to a device on a local network,
 * - HEAD first (a one-byte ranged GET if HEAD is refused); the response body is NEVER read,
 * - no cookies, no login data, no referrer: only User-Agent, Accept-Encoding, Accept and Range are sent.
 * Pure JVM classes only; the connection is injectable so tests run without a network.
 */
class SafeRedirectResolver(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun resolve(startUrl: String): RedirectTrace = withContext(Dispatchers.IO) {
        try {
            trace(startUrl)
        } catch (e: UnknownHostException) {
            RedirectTrace.Failed("Could not find this website. Check the link and your internet connection.")
        } catch (e: SocketTimeoutException) {
            RedirectTrace.Failed("The server took too long to answer. Try again later.")
        } catch (e: SSLException) {
            RedirectTrace.Failed("The secure connection (HTTPS) could not be trusted, so the link was blocked.")
        } catch (e: IOException) {
            RedirectTrace.Failed("Could not connect to the server. Check your internet connection.")
        } catch (e: SecurityException) {
            RedirectTrace.Failed("The app is not allowed to use the network.")
        }
    }

    private fun trace(startUrl: String): RedirectTrace {
        val first = UrlValidator.validate(startUrl) as? UrlValidation.Valid
            ?: return RedirectTrace.Failed("This is not a valid web address.")
        val hops = mutableListOf(first.url)
        val seen = hashSetOf(UrlKey.of(first.url))
        var current = first.url
        var currentHost = first.host
        var method = "HEAD"
        var redirects = 0

        while (true) {
            val connection = openConnection(URL(current))
            val answer = try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("User-Agent", LinkInspector.USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", "*/*")
                if (method == "GET") connection.setRequestProperty("Range", "bytes=0-0")
                Pair(connection.responseCode, connection.getHeaderField("Location"))
            } finally {
                connection.disconnect()
            }
            val code = answer.first
            val location = answer.second

            if (code in REDIRECT_CODES) {
                if (location.isNullOrBlank()) return RedirectTrace.Failed("The link redirects without saying where to.")
                if (redirects >= maxRedirects) return RedirectTrace.Failed("The link redirects too many times.")
                val next = try {
                    URL(URL(current), location).toString()
                } catch (_: MalformedURLException) {
                    return RedirectTrace.Failed("The link redirects to an address that is not valid.")
                }
                val checked = UrlValidator.validate(next) as? UrlValidation.Valid
                    ?: return RedirectTrace.Failed("The link redirects to an unsupported address, so it was blocked.")
                if (current.startsWith("https://", ignoreCase = true) && !checked.isSecure) {
                    return RedirectTrace.Failed(
                        "The link redirects from a secure (https) to an insecure (http) address, so it was blocked.",
                    )
                }
                if (!UrlSafety.isLocalOrPrivate(currentHost) && UrlSafety.isLocalOrPrivate(checked.host)) {
                    return RedirectTrace.Failed(
                        "The link redirects to a device on a local network, which is blocked for your safety.",
                    )
                }
                if (!seen.add(UrlKey.of(checked.url))) return RedirectTrace.Failed("The link redirects in a circle.")
                current = checked.url
                currentHost = checked.host
                hops += checked.url
                redirects++
                method = "HEAD"
            } else if (method == "HEAD" && code >= 400) {
                // Many servers refuse or mishandle HEAD: ask once more for the first byte only.
                method = "GET"
            } else {
                // A final 4xx/5xx is not an error here: the detector turns it into an explanation for known platforms.
                return RedirectTrace.Resolved(current, hops, code)
            }
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
