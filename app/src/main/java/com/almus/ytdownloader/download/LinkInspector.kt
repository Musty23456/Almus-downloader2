package com.almus.ytdownloader.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the link check learned about a file before the user approves the download. */
data class LinkInfo(
    val url: String,
    val finalUrl: String,
    val fileName: String,
    val mimeType: String?,
    /** -1 when the server did not say. */
    val contentLength: Long,
    val redirectCount: Int,
    /** True when the link or any redirect target uses plain http. */
    val insecure: Boolean,
    /** True when the server says the link is an HTML page rather than a file. */
    val looksLikeWebPage: Boolean,
    /** Audio or video details when the server's type (or the file extension) says this is media. */
    val media: MediaDetails? = null,
    /** True or false when the server said whether it can resume (byte ranges); null when it did not say. */
    val acceptsRanges: Boolean? = null,
)

sealed interface InspectResult {
    data class Success(val info: LinkInfo) : InspectResult

    /** [stream] is set when the link is a playlist, a streaming manifest or a live stream (not one file). */
    data class Failure(val message: String, val stream: StreamKind? = null) : InspectResult
}

/**
 * Checks a link with a HEAD request (GET for the first byte if HEAD is refused), following
 * redirects by hand so every hop can be validated. It never reads a file body, never sends
 * credentials, and uses the platform's normal TLS validation.
 * Streaming playlists and live streams are recognised here and refused with an explanation.
 * Pure JVM classes only, so it is unit tested against a local test server.
 */
class LinkInspector(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun inspect(rawUrl: String): InspectResult = withContext(Dispatchers.IO) {
        val valid = when (val v = UrlValidator.validate(rawUrl)) {
            is UrlValidation.Invalid -> return@withContext InspectResult.Failure(v.error.message)
            is UrlValidation.Valid -> v
        }
        try {
            probe(valid.url, valid.isSecure)
        } catch (e: UnknownHostException) {
            InspectResult.Failure("Could not find this website. Check the link and your internet connection.")
        } catch (e: SocketTimeoutException) {
            InspectResult.Failure("The server took too long to answer. Try again later.")
        } catch (e: SSLException) {
            InspectResult.Failure("The secure connection (HTTPS) could not be trusted, so the link was blocked.")
        } catch (e: IOException) {
            InspectResult.Failure("Could not connect to the server. Check your internet connection.")
        } catch (e: SecurityException) {
            InspectResult.Failure("The app is not allowed to use the network.")
        }
    }

    private fun probe(startUrl: String, startSecure: Boolean): InspectResult {
        var method = "HEAD"
        var current = startUrl
        var redirects = 0
        var insecure = !startSecure

        while (true) {
            val connection = openConnection(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", "*/*")
                if (method == "GET") connection.setRequestProperty("Range", "bytes=0-0")

                val code = connection.responseCode
                when {
                    code in REDIRECT_CODES -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return InspectResult.Failure("The link redirects without saying where to.")
                        }
                        if (redirects >= maxRedirects) {
                            return InspectResult.Failure("The link redirects too many times.")
                        }
                        val next = try {
                            URL(URL(current), location).toString()
                        } catch (_: java.net.MalformedURLException) {
                            return InspectResult.Failure("The link redirects to an address that is not valid.")
                        }
                        val checked = UrlValidator.validate(next)
                        if (checked !is UrlValidation.Valid) {
                            return InspectResult.Failure("The link redirects to an unsupported address.")
                        }
                        val wasSecure = current.startsWith("https://", ignoreCase = true)
                        if (wasSecure && !checked.isSecure) {
                            return InspectResult.Failure(
                                "The link redirects from a secure (https) to an insecure (http) address, so it was blocked.",
                            )
                        }
                        if (!checked.isSecure) insecure = true
                        current = checked.url
                        redirects++
                    }

                    code in 200..299 || (code == 416 && method == "GET") -> {
                        return finish(startUrl, current, redirects, insecure, connection, code)
                    }

                    method == "HEAD" -> {
                        // Many servers refuse or mishandle HEAD. Ask for the first byte instead.
                        method = "GET"
                    }

                    else -> return InspectResult.Failure(StatusMapper.httpMessage(code))
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Refuses streams (nothing to save as one file), otherwise describes the file. */
    private fun finish(
        startUrl: String,
        finalUrl: String,
        redirects: Int,
        insecure: Boolean,
        c: HttpURLConnection,
        code: Int,
    ): InspectResult {
        val stream = StreamingFormats.detect(finalUrl, MimeTypes.normalize(c.contentType))
            ?: StreamingFormats.detectLive(c.headerFields)
        if (stream != null) return InspectResult.Failure(stream.message, stream)
        return InspectResult.Success(buildInfo(startUrl, finalUrl, redirects, insecure, c, code))
    }

    private fun buildInfo(
        startUrl: String,
        finalUrl: String,
        redirects: Int,
        insecure: Boolean,
        c: HttpURLConnection,
        code: Int,
    ): LinkInfo {
        val declaredMime = MimeTypes.normalize(c.contentType)
        val disposition = c.getHeaderField("Content-Disposition")
        val name = FileNameResolver.resolve(
            contentDisposition = disposition,
            mimeType = declaredMime,
            url = finalUrl.takeIf { FileNameResolver.fromUrl(it) != null } ?: startUrl,
        )
        val mime = if (MimeTypes.isGeneric(declaredMime)) {
            MimeTypes.forExtension(FileNameResolver.extensionOf(name)) ?: declaredMime
        } else {
            declaredMime
        }
        val hasAttachmentName = FileNameResolver.fromContentDisposition(disposition) != null
        return LinkInfo(
            url = startUrl,
            finalUrl = finalUrl,
            fileName = name,
            mimeType = mime,
            contentLength = totalLength(c),
            redirectCount = redirects,
            insecure = insecure,
            looksLikeWebPage = declaredMime == "text/html" && !hasAttachmentName,
            media = MediaFormats.describe(mime, name),
            acceptsRanges = rangeSupport(c, code),
        )
    }

    /**
     * True when the server proved or said it can resume (206 answer, Content-Range, "Accept-Ranges: bytes"),
     * false for "Accept-Ranges: none", null when the headers say nothing. Not having the header proves nothing.
     */
    private fun rangeSupport(c: HttpURLConnection, code: Int): Boolean? {
        if (code == 206 || c.getHeaderField("Content-Range") != null) return true
        val value = c.getHeaderField("Accept-Ranges")?.trim()?.lowercase() ?: return null
        return when {
            value.contains("bytes") -> true
            value == "none" -> false
            else -> null
        }
    }

    /** Total size from Content-Range ("bytes 0-0/1234") when present, else Content-Length. */
    private fun totalLength(c: HttpURLConnection): Long {
        val range = c.getHeaderField("Content-Range")
        if (range != null) {
            val total = range.substringAfterLast('/', "").trim()
            total.toLongOrNull()?.let { if (it > 0) return it }
            return -1
        }
        // A length of 0 is treated as "unknown": some servers send it for HEAD requests.
        val length = c.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        return if (length > 0) length else -1L
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "ALMUS-Downloader/0.9 (Android)"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
