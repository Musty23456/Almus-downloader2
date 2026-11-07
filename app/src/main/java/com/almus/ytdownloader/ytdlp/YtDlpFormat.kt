package com.almus.ytdownloader.ytdlp

import java.util.Locale

/** Small pure helpers for the YouTube screen. */
object UrlInput {
    /** Every usable link in a pasted text (separated by spaces, new lines or commas), without repeats. */
    fun extractAll(raw: String): List<String> =
        raw.split(Regex("[\\s,]+")).mapNotNull { normalize(it) }.distinct()

    /** True for a YouTube playlist page (youtube.com/playlist?list=...). A video link that only carries a list stays a single video. */
    fun isPlaylist(url: String): Boolean {
        val path = url.lowercase().substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path.trimEnd('/') == "playlist"
    }

    /** Returns a clean http(s) address, or null when the text is not a usable link. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
        val host = withScheme.substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return withScheme
    }
}

fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/** yt-dlp errors are long and multi-line; the last non-empty line is the useful one. */
fun shortError(error: Throwable): String {
    val text = error.message ?: error.javaClass.simpleName
    val line = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: error.javaClass.simpleName
    return line.take(200)
}

/** How many downloads are still working and their average progress; the service notification shows this. */
data class ActiveSummary(val active: Int, val percent: Int)

fun summarize(jobs: List<DownloadJob>): ActiveSummary {
    val working = jobs.filter { it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.SAVING }
    if (working.isEmpty()) return ActiveSummary(0, 0)
    return ActiveSummary(working.size, working.map { it.percent.toDouble() }.average().toInt().coerceIn(0, 100))
}

object SubtitleLangs {
    const val DEFAULT = "en"

    /** Keeps plain language codes (en, ha, pt-BR, en.*), at most 6. Returns null when nothing usable is left. */
    fun sanitize(raw: String): String? {
        val parts = raw.split(Regex("[\\s,;]+"))
            .filter { it.isNotEmpty() && it.length <= 12 && !it.startsWith("-") }
            .filter { code -> code.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '*' } }
            .distinct()
            .take(6)
        return if (parts.isEmpty()) null else parts.joinToString(",")
    }
}
