package com.almus.ytdownloader.engine

/** Reads the few HTTP headers the engine depends on. Pure Kotlin, unit tested. */
data class ContentRange(val start: Long, val end: Long, val total: Long?)

object HttpHeaders {
    private val contentRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
    private val unsatisfied = Regex("^bytes\\s+\\*/(\\d+)$", RegexOption.IGNORE_CASE)

    /** "bytes 100-199/1000" gives start 100, end 199, total 1000 (total is null for "*"). Null if malformed. */
    fun parseContentRange(value: String?): ContentRange? {
        val m = contentRange.matchEntire(value?.trim() ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = if (m.groupValues[3] == "*") null else m.groupValues[3].toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    /** For a 416 answer: "bytes *\/1000" gives 1000. */
    fun parseUnsatisfiedTotal(value: String?): Long? {
        val m = unsatisfied.matchEntire(value?.trim() ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /** True when the server says it accepts byte ranges, false for "none", null when it did not say. */
    fun acceptsRanges(value: String?): Boolean? {
        val v = value?.trim()?.lowercase() ?: return null
        return when {
            v.isEmpty() -> null
            v == "none" -> false
            v.contains("bytes") -> true
            else -> null
        }
    }

    /** Weak validators ("W/...") must not be used with If-Range. */
    fun isStrongEtag(etag: String): Boolean = !etag.trim().startsWith("W/", ignoreCase = true)

    /** Retry-After in seconds as milliseconds. A date form is ignored (null). */
    fun retryAfterMs(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return if (seconds in 0..86_400) seconds * 1000 else null
    }

    fun contentLength(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}
