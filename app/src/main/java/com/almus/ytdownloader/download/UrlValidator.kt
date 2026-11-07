package com.almus.ytdownloader.download

import java.net.URI
import java.net.URISyntaxException

/** Why a link was rejected. [message] is shown to the user as is. */
enum class UrlError(val message: String) {
    Empty("Enter a download link."),
    TooLong("This link is too long (the limit is 2048 characters)."),
    MissingScheme("The link must start with http:// or https://"),
    UnsupportedScheme("Only http:// and https:// links are supported."),
    Malformed("This link is not valid. Copy it again and make sure it has no spaces."),
    MissingHost("The link has no website address."),
    HasCredentials("Links that contain a username or password are not supported."),
}

sealed interface UrlValidation {
    /** [url] is the trimmed link with a lower-case scheme, ready to be used. */
    data class Valid(val url: String, val host: String, val isSecure: Boolean) : UrlValidation

    data class Invalid(val error: UrlError) : UrlValidation
}

/**
 * Pure Kotlin (no Android classes) so it can be unit tested on the JVM.
 * Accepts only absolute http and https links with a host.
 */
object UrlValidator {
    const val MAX_LENGTH = 2048

    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val hostRegex = Regex("^[A-Za-z0-9]([A-Za-z0-9._\\-]*[A-Za-z0-9])?$")
    private val ipv6Regex = Regex("^\\[[0-9A-Fa-f:.]+]$")

    fun validate(input: String?): UrlValidation {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return invalid(UrlError.Empty)
        if (trimmed.length > MAX_LENGTH) return invalid(UrlError.TooLong)

        val schemeMatch = schemeRegex.find(trimmed) ?: return invalid(UrlError.MissingScheme)
        val scheme = schemeMatch.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return invalid(UrlError.UnsupportedScheme)

        // Whitespace or control characters inside the link are never valid.
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return invalid(UrlError.Malformed)

        val afterScheme = trimmed.substring(schemeMatch.value.length)
        if (!afterScheme.startsWith("//")) return invalid(UrlError.Malformed)

        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'), startIndex = 2)
            .let { if (it == -1) afterScheme.length else it }
        val authority = afterScheme.substring(2, authorityEnd)
        if (authority.isEmpty()) return invalid(UrlError.MissingHost)
        if (authority.contains('@')) return invalid(UrlError.HasCredentials)

        // Syntax check by the JDK parser (path, query and fragment characters).
        try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return invalid(UrlError.Malformed)
        }

        val hostAndPort = splitHostAndPort(authority) ?: return invalid(UrlError.Malformed)
        val (host, port) = hostAndPort
        if (host.isEmpty()) return invalid(UrlError.MissingHost)
        if (!hostRegex.matches(host) && !ipv6Regex.matches(host)) return invalid(UrlError.Malformed)
        if (port != null && (port.isEmpty() || port.length > 5 || !port.all { it.isDigit() } || port.toInt() !in 1..65535)) {
            return invalid(UrlError.Malformed)
        }

        val normalized = scheme + trimmed.substring(scheme.length)
        return UrlValidation.Valid(url = normalized, host = host.lowercase(), isSecure = scheme == "https")
    }

    /** Returns host and optional port (without the colon), or null when the authority is malformed. */
    private fun splitHostAndPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close == -1) return null
            val host = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to rest.substring(1)
                else -> null
            }
        }
        val colon = authority.lastIndexOf(':')
        if (colon == -1) return authority to null
        if (authority.indexOf(':') != colon) return null // more than one colon outside brackets
        return authority.substring(0, colon) to authority.substring(colon + 1)
    }

    private fun invalid(error: UrlError) = UrlValidation.Invalid(error)
}
