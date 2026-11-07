package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises Pinterest addresses (including its country domains). Detection only: nothing is fetched. */
object PinterestAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Pinterest
    override val brandTokens = listOf("pinterest")

    private val countryEndings = setOf(
        "com", "co.uk", "ca", "fr", "de", "es", "it", "jp", "ru", "pt", "at", "ch", "nl", "se", "dk", "fi", "ie",
        "com.au", "com.mx", "com.br", "co.in", "in", "co.kr", "ph", "cl", "pl", "nz", "com.pe", "com.co", "com.ar",
    )
    private val otherPaths = setOf("ideas", "today", "categories", "business", "about", "help", "news_hub", "explore", "topics")

    override fun ownsHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (AdapterSupport.hostIs(h, "pin.it", "pinimg.com")) return true
        val labels = h.split('.')
        val index = labels.indexOf("pinterest")
        if (index < 0) return false
        // "pinterest" must be the registrable name: <optional sub-domains>.pinterest.<known ending>
        val ending = labels.drop(index + 1).joinToString(".")
        return ending in countryEndings
    }

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "pinimg.com")) return AdapterSupport.fileHost(url)
        if (AdapterSupport.hostIs(host, "pin.it")) {
            return if (url.segments.isEmpty()) LinkClassification(LinkKind.Home) else LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "login" || first == "signup" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "pin" -> {
                val id = s.getOrNull(1)?.let { pinIdOf(it) }
                if (id != null) LinkClassification(LinkKind.Post, id) else LinkClassification(LinkKind.Other)
            }
            first == "search" -> LinkClassification(LinkKind.Search)
            first in otherPaths -> LinkClassification(LinkKind.Other)
            s.size == 1 || s.size == 2 -> LinkClassification(LinkKind.Channel, url.segments[0])
            else -> LinkClassification(LinkKind.Other)
        }
    }

    /** Pin addresses are "/pin/123456" or "/pin/some-title--123456". */
    private fun pinIdOf(segment: String): String? {
        val tail = segment.substringAfterLast("--")
        return tail.takeIf { AdapterSupport.isDigits(it) }
    }

    override fun describe(classification: LinkClassification): String = when (classification.kind) {
        LinkKind.Post -> "Pinterest pin"
        LinkKind.Channel -> "Pinterest profile or board"
        else -> super.describe(classification)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Post ->
            AdapterSupport.idLine(classification) + listOf(
                "A pin is a web page that points to an image, video or another website. It is not a file.",
                "Pins often link to content owned by someone else.",
            )
        else -> AdapterSupport.idLine(classification)
    }
}
