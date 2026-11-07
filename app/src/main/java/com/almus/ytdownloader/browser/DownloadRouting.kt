package com.almus.ytdownloader.browser

import com.almus.ytdownloader.download.StreamingFormats
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator

sealed interface DownloadRoute {
    /** A normal http(s) file link: hand it to the app's download flow (link check, confirmation, DownloadManager). */
    data class ToManager(val url: String) : DownloadRoute

    /** Cannot be downloaded this way. [message] explains why. */
    data class Unsupported(val message: String) : DownloadRoute
}

/**
 * Decides whether something the WebView wants to download can go to the app's download manager.
 * Pure Kotlin so it can be unit tested on the JVM.
 *
 * This is intentionally narrow. The app downloads direct http(s) file links. It does NOT download:
 * - blob: / data: addresses (the file only exists inside the page's memory),
 * - streaming playlists (HLS .m3u8, DASH .mpd, Smooth Streaming): they list many small pieces, not one file,
 * - anything that needs the page's login cookies (the download manager never receives them).
 */
object DownloadRouting {
    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    const val STREAMING_MESSAGE =
        "This is a streaming playlist or manifest (HLS, DASH or similar). It points to many small pieces " +
            "instead of one file, so it cannot be downloaded here. Look for a direct link to a single audio or video file."
    const val INLINE_MESSAGE =
        "This file is created inside the web page (blob: or data: address) and cannot be handed to the " +
            "download manager. Look for a direct download link instead."

    fun route(url: String?, mimeType: String? = null, contentDisposition: String? = null): DownloadRoute {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return DownloadRoute.Unsupported("This download has no address.")

        val scheme = schemeRegex.find(text)?.groupValues?.get(1)?.lowercase()
        when (scheme) {
            "http", "https" -> Unit
            "blob", "data", "filesystem" -> return DownloadRoute.Unsupported(INLINE_MESSAGE)
            else -> return DownloadRoute.Unsupported("Only http and https downloads are supported.")
        }

        val valid = when (val validation = UrlValidator.validate(text)) {
            is UrlValidation.Invalid -> return DownloadRoute.Unsupported(validation.error.message)
            is UrlValidation.Valid -> validation
        }

        if (isStreaming(valid.url, mimeType)) return DownloadRoute.Unsupported(STREAMING_MESSAGE)
        return DownloadRoute.ToManager(valid.url)
    }

    /** The detection itself lives in [StreamingFormats], shared with the link check. */
    internal fun isStreaming(url: String, mimeType: String?): Boolean =
        StreamingFormats.detect(url, mimeType) != null
}

/**
 * Limits how often a page can open the download prompt. A page can trigger downloads from a script
 * without any tap; without a limit it could re-open the dialog the moment the user closes it.
 * [clock] returns milliseconds from a monotonic clock.
 */
class DownloadPromptThrottle(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long,
) {
    private var lastAllowed: Long? = null

    /** True when a prompt may be shown now. Calling it counts as showing one. */
    fun tryAcquire(): Boolean {
        val now = clock()
        val last = lastAllowed
        if (last != null && now - last in 0 until minIntervalMs) return false
        lastAllowed = now
        return true
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 3_000L
    }
}
