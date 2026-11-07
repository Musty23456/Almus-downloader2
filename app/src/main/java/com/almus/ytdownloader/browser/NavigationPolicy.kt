package com.almus.ytdownloader.browser

sealed interface NavDecision {
    /** A normal web page: let the WebView load it. */
    data object LoadInApp : NavDecision

    /** mailto:, tel: and sms: links that the user tapped. Handed to the system, never loaded in the WebView. */
    data class OpenExternally(val url: String) : NavDecision

    /** Not allowed. [message] explains why. */
    data class Block(val message: String) : NavDecision
}

/**
 * Decides what happens when a page tries to navigate (a tapped link, a redirect or a script).
 * Pure Kotlin so it can be unit tested on the JVM.
 *
 * Only http and https are ever loaded. intent:, javascript:, file:, content:, data:, blob:, market:
 * and every custom scheme are blocked, so a web page cannot launch other apps, read local files
 * or run code through the address.
 */
object NavigationPolicy {
    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val handOffSchemes = setOf("mailto", "tel", "sms", "smsto")

    fun decide(url: String?, hasGesture: Boolean, isMainFrame: Boolean = true): NavDecision {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return NavDecision.Block("Blocked an empty address.")
        if (text.equals("about:blank", ignoreCase = true)) return NavDecision.LoadInApp

        val scheme = schemeRegex.find(text)?.groupValues?.get(1)?.lowercase()
            ?: return NavDecision.Block("Blocked an address that is not a web page.")

        return when {
            scheme == "http" || scheme == "https" -> decideWeb(text)
            scheme in handOffSchemes ->
                if (hasGesture && isMainFrame) {
                    NavDecision.OpenExternally(text)
                } else {
                    NavDecision.Block("Blocked a page that tried to open another app on its own.")
                }
            else -> NavDecision.Block("Blocked a link that is not a web page (${printable(scheme)}:).")
        }
    }

    private fun decideWeb(text: String): NavDecision {
        val afterScheme = text.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return NavDecision.Block("Blocked a malformed web address.")
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
            .let { if (it == -1) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty()) return NavDecision.Block("Blocked a malformed web address.")
        // user:password@host links are a classic way to disguise the real destination.
        if (authority.contains('@')) {
            return NavDecision.Block("Blocked a link that contains a username or password.")
        }
        return NavDecision.LoadInApp
    }

    /** Scheme text is shown in a message, so keep it short and free of odd characters. */
    private fun printable(scheme: String): String = scheme.take(20)
}
