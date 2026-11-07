package com.almus.ytdownloader.download

/**
 * Key used to recognise the same link entered twice: scheme and host are case-insensitive,
 * default ports and the #fragment are ignored, "http://a.com" equals "http://a.com/".
 * The path and query are compared exactly (they are case-sensitive on most servers).
 */
object UrlKey {
    private val pattern = Regex("^(https?)://([^/?#]*)(.*)$", RegexOption.IGNORE_CASE)

    fun of(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val match = pattern.find(noFragment) ?: return noFragment
        val scheme = match.groupValues[1].lowercase()
        var authority = match.groupValues[2].lowercase()
        if ((scheme == "http" && authority.endsWith(":80")) || (scheme == "https" && authority.endsWith(":443"))) {
            authority = authority.substringBeforeLast(':')
        }
        var rest = match.groupValues[3]
        if (rest.isEmpty() || rest.startsWith("?")) rest = "/$rest"
        return "$scheme://$authority$rest"
    }
}
