package com.almus.ytdownloader.browser

/** Extracts a normalised host from an http(s) address without using Android classes. */
object HostKey {
    /** Lower-case host without port, user info or trailing dot; null for anything that is not http(s). */
    fun of(url: String?): String? {
        val text = url?.trim() ?: return null
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val afterScheme = text.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
            .let { if (it == -1) afterScheme.length else it }
        var authority = afterScheme.substring(0, authorityEnd)
        authority = authority.substringAfterLast('@')

        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close == -1) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        val cleaned = host.lowercase().trimEnd('.')
        return cleaned.ifEmpty { null }
    }

    /** True for the blank page the browser shows when there is nothing to display. */
    fun isBlank(url: String?): Boolean = url == null || url.isBlank() || url == "about:blank"
}
