package com.almus.ytdownloader.download

/**
 * Things that look like audio or video but are NOT one downloadable file.
 * [message] is shown to the user as is, so it says what the link is and why nothing was saved.
 */
enum class StreamKind(val message: String) {
    Hls(
        "This is an HLS stream (.m3u8): a playlist of many small video or audio pieces, not one file. " +
            "This app only downloads single files, so nothing was saved.",
    ),
    Dash(
        "This is a DASH stream (.mpd): a manifest that points to many small video and audio pieces, " +
            "not one file. This app only downloads single files, so nothing was saved.",
    ),
    OtherAdaptive(
        "This is an adaptive streaming manifest (Smooth Streaming or similar). It points to many small " +
            "pieces, not one file. This app only downloads single files, so nothing was saved.",
    ),
    Playlist(
        "This is a playlist (a list of links), not an audio or video file. Open it in a player app, " +
            "or look for a direct link to the file itself.",
    ),
    Live(
        "This looks like a live stream (such as internet radio). It never ends, so there is no complete " +
            "file to save. Nothing was downloaded.",
    ),
}

/**
 * Recognises streaming formats from the address, the MIME type and (for live streams) response headers.
 * Pure Kotlin, unit tested. These are heuristics: they refuse what clearly is not a single file, and
 * never try to get around a stream, so protected or segmented media stays out of reach by design.
 */
object StreamingFormats {
    private val hlsMimes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")
    private val dashMimes = setOf("application/dash+xml")
    private val adaptiveMimes = setOf("application/vnd.ms-sstr+xml", "application/f4m+xml")
    private val playlistMimes = setOf(
        "audio/mpegurl",
        "audio/x-mpegurl",
        "audio/x-scpls",
        "application/pls+xml",
        "video/x-ms-asx",
    )

    /** Response headers that Icecast and Shoutcast style radio servers send. */
    private val liveHeaderNames = setOf(
        "icy-metaint",
        "icy-name",
        "icy-br",
        "icy-genre",
        "icy-url",
        "icy-description",
        "icy-notice1",
        "icy-notice2",
        "ice-audio-info",
    )

    /**
     * What kind of stream [url] and [mimeType] describe, or null for an ordinary file.
     * A real audio or video MIME type wins over the address: a server that sends "video/mp4" is
     * sending a file, even if the address ends in something that looks like a playlist.
     */
    fun detect(url: String, mimeType: String?): StreamKind? {
        val type = MimeTypes.normalize(mimeType)
        if (type != null) {
            kindForMime(type)?.let { return it }
            if (type.startsWith("video/") || type.startsWith("audio/")) return null
        }
        return kindForPath(url.substringBefore('#').substringBefore('?').lowercase())
    }

    /** [headers] is what HttpURLConnection.getHeaderFields() returns (the status line has a null key). */
    fun detectLive(headers: Map<String?, List<String>>?): StreamKind? {
        if (headers == null) return null
        val live = headers.keys.any { name -> name != null && name.lowercase() in liveHeaderNames }
        return if (live) StreamKind.Live else null
    }

    private fun kindForMime(type: String): StreamKind? = when (type) {
        in hlsMimes -> StreamKind.Hls
        in dashMimes -> StreamKind.Dash
        in adaptiveMimes -> StreamKind.OtherAdaptive
        in playlistMimes -> StreamKind.Playlist
        else -> null
    }

    private fun kindForPath(path: String): StreamKind? = when {
        path.endsWith(".m3u8") -> StreamKind.Hls
        path.endsWith(".mpd") -> StreamKind.Dash
        path.endsWith(".ism") || path.endsWith(".ismc") || path.contains(".ism/") || path.endsWith(".f4m") ->
            StreamKind.OtherAdaptive
        path.endsWith(".m3u") || path.endsWith(".pls") || path.endsWith(".asx") -> StreamKind.Playlist
        else -> null
    }
}
