package com.almus.ytdownloader.ytdlp

/**
 * A cookies.txt file is a login. Only the cookies of YouTube and Google are kept when it is imported; everything
 * else in the file (other sites) is thrown away. Pure Kotlin, unit tested.
 */
object CookieFile {
    const val MAX_BYTES = 1_000_000
    private val keepDomains = listOf("youtube.com", "google.com")

    data class Filtered(val text: String, val kept: Int, val dropped: Int)

    /** Returns the filtered file in Netscape format, or null when it holds no YouTube or Google cookie. */
    fun filterForYouTube(raw: String): Filtered? {
        var kept = 0
        var dropped = 0
        val out = StringBuilder("# Netscape HTTP Cookie File\n")
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            val httpOnly = line.startsWith("#HttpOnly_")
            if (line.startsWith("#") && !httpOnly) continue
            val fields = line.split('\t')
            if (fields.size != 7) continue
            val domain = fields[0].removePrefix("#HttpOnly_").trimStart('.').lowercase()
            if (keepDomains.any { domain == it || domain.endsWith(".$it") }) {
                out.append(line).append('\n')
                kept++
            } else {
                dropped++
            }
        }
        return if (kept == 0) null else Filtered(out.toString(), kept, dropped)
    }
}
