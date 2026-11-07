package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.UrlValidation
import java.net.URLDecoder

/**
 * A web address split into parts, for the adapters to read. Built only from a link that
 * [com.almus.ytdownloader.download.UrlValidator] accepted. It is plain text handling: nothing is opened,
 * resolved or loaded, and the decoded parts are used for matching only.
 */
class ParsedUrl private constructor(
    val url: String,
    val host: String,
    val port: Int?,
    /** Path segments, percent-decoded, empty ones removed. */
    val segments: List<String>,
    private val query: Map<String, String>,
) {
    /** Lower-case segment at [index], or null. */
    fun seg(index: Int): String? = segments.getOrNull(index)?.lowercase()

    /** Value of a query parameter (name compared in lower case; the first one wins), or null. */
    fun param(name: String): String? = query[name.lowercase()]

    val lastSegment: String? get() = segments.lastOrNull()

    /** Lower-case file extension of the last path segment ("mp4"), or null. */
    val extension: String?
        get() {
            val name = lastSegment ?: return null
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return null
            val ext = name.substring(dot + 1).lowercase()
            return if (ext.length <= MAX_EXTENSION && ext.all { it.isLetterOrDigit() }) ext else null
        }

    companion object {
        private const val MAX_EXTENSION = 8
        private const val MAX_PARAMS = 50

        fun parse(valid: UrlValidation.Valid): ParsedUrl {
            val url = valid.url
            val afterScheme = url.substring(url.indexOf("://") + 3)
            val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it == -1) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd)
            val port = if (authority.startsWith("[")) {
                authority.substringAfter("]:", "").toIntOrNull()
            } else {
                authority.substringAfter(':', "").toIntOrNull()
            }

            val rest = afterScheme.substring(authorityEnd).substringBefore('#')
            val path = rest.substringBefore('?')
            val queryText = if (rest.contains('?')) rest.substringAfter('?') else ""

            val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
            val query = LinkedHashMap<String, String>()
            queryText.split('&').filter { it.isNotEmpty() }.take(MAX_PARAMS).forEach { pair ->
                val name = decode(pair.substringBefore('=')).lowercase()
                val value = decode(pair.substringAfter('=', ""))
                if (name.isNotEmpty() && !query.containsKey(name)) query[name] = value
            }
            return ParsedUrl(url, valid.host, port, segments, query)
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }
    }
}
