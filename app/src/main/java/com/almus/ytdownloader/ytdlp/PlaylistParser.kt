package com.almus.ytdownloader.ytdlp

/** Reads the lines yt-dlp prints for `--flat-playlist --print PRINT_TEMPLATE`. Pure Kotlin, unit tested. */
object PlaylistParser {
    const val SEP = "|||"
    const val PRINT_TEMPLATE = "%(id)s|||%(title)s|||%(duration)s|||%(playlist_title)s|||%(url)s"
    private const val MISSING = "NA"

    fun parse(sourceUrl: String, output: String): PlaylistDetails? {
        var playlistTitle: String? = null
        val entries = ArrayList<PlaylistEntry>()
        for (raw in output.lineSequence()) {
            val parts = raw.trim().split(SEP)
            if (parts.size < 5) continue
            val url = parts[4].trim()
            if (!url.startsWith("http")) continue
            val title = parts[1].trim().takeUnless { it.isEmpty() || it == MISSING } ?: url
            val seconds = parts[2].trim().toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
            if (playlistTitle == null) playlistTitle = parts[3].trim().takeUnless { it.isEmpty() || it == MISSING }
            entries += PlaylistEntry(url = url, title = title, durationSeconds = seconds)
        }
        if (entries.isEmpty()) return null
        return PlaylistDetails(url = sourceUrl, title = playlistTitle ?: sourceUrl, entries = entries)
    }
}
