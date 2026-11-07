package com.almus.ytdownloader.browser

import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator

sealed interface AddressResult {
    /** [url] is a validated absolute http or https address. */
    data class Navigate(val url: String) : AddressResult

    /** [message] is shown to the user as is. */
    data class Invalid(val message: String) : AddressResult
}

/**
 * Turns what the user typed in the address bar into a safe address to load.
 * Pure Kotlin (no Android classes) so it can be unit tested on the JVM.
 *
 * - Only http and https are accepted. Any other scheme is rejected, never "fixed".
 * - An address without a scheme ("example.com/page") gets https:// in front of it.
 * - There is deliberately no web search: text that is not an address is rejected instead of
 *   being sent to a search engine, so nothing the user types leaves the device by accident.
 */
object AddressResolver {
    private val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

    /** Schemes written without "//" (javascript:, mailto:, data: ...). They must never be guessed as hosts. */
    private val opaqueScheme = Regex(
        "^(javascript|data|file|content|intent|mailto|tel|sms|smsto|blob|about|view-source|ftp|market|vbscript):",
        RegexOption.IGNORE_CASE,
    )

    const val NOT_AN_ADDRESS =
        "Enter a full web address, such as example.com or https://example.com/page. Searching is not supported."
    const val ONLY_HTTP = "Only http:// and https:// addresses can be opened."

    fun resolve(input: String?): AddressResult {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return AddressResult.Invalid("Enter a web address.")

        val candidate = when {
            explicitScheme.containsMatchIn(text) -> text
            opaqueScheme.containsMatchIn(text) -> return AddressResult.Invalid(ONLY_HTTP)
            else -> {
                if (text.any { it.isWhitespace() || it.isISOControl() }) {
                    return AddressResult.Invalid(NOT_AN_ADDRESS)
                }
                if (!looksLikeHost(text)) return AddressResult.Invalid(NOT_AN_ADDRESS)
                "https://$text"
            }
        }

        return when (val validation = UrlValidator.validate(candidate)) {
            is UrlValidation.Valid -> AddressResult.Navigate(validation.url)
            is UrlValidation.Invalid -> AddressResult.Invalid(validation.error.message)
        }
    }

    /** True when the part before the first "/", "?" or "#" has a dot, is "localhost", or is an IPv6 literal. */
    private fun looksLikeHost(text: String): Boolean {
        val end = text.indexOfAny(charArrayOf('/', '?', '#')).let { if (it == -1) text.length else it }
        val authority = text.substring(0, end)
        if (authority.isEmpty()) return false
        if (authority.startsWith("[")) return true
        val host = authority.substringBefore(':')
        return host.equals("localhost", ignoreCase = true) || host.contains('.')
    }
}
