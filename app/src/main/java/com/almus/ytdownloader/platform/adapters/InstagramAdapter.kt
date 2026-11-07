package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises Instagram addresses. Detection only: nothing is fetched and no sign-in is attempted. */
object InstagramAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Instagram
    override val brandTokens = listOf("instagram")

    private val domains = arrayOf("instagram.com", "instagr.am", "cdninstagram.com")
    private val code = Regex("^[A-Za-z0-9_-]{5,}$")
    private val reserved = setOf("explore", "accounts", "direct", "stories", "p", "reel", "reels", "tv", "share", "about", "legal", "web", "developer", "directory")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        if (AdapterSupport.hostIs(url.host, "cdninstagram.com")) return AdapterSupport.fileHost(url)

        val s = url.segments
        val low = s.map { it.lowercase() }
        val first = low.firstOrNull()
        // "/user/p/CODE" and "/user/reel/CODE" put the post type one segment later.
        val typeIndex = when {
            first == null || first == "share" -> -1
            first in setOf("p", "reel", "reels", "tv") -> 0
            low.size >= 3 && low[1] in setOf("p", "reel", "reels", "tv") -> 1
            else -> -1
        }
        if (typeIndex >= 0) {
            val id = s.getOrNull(typeIndex + 1)
            val kind = when (low[typeIndex]) {
                "p" -> LinkKind.Post
                "tv" -> LinkKind.Video
                else -> LinkKind.ShortVideo
            }
            return if (id != null && code.matches(id)) LinkClassification(kind, id) else LinkClassification(LinkKind.Other)
        }

        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "share" -> if (s.size >= 2) LinkClassification(LinkKind.ShortLink, opaqueShortLink = true) else LinkClassification(LinkKind.Other)
            first == "accounts" || first == "challenge" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "stories" -> LinkClassification(LinkKind.Story, restriction = RestrictionReason.LoginRequired)
            first == "direct" -> LinkClassification(LinkKind.Other, restriction = RestrictionReason.LoginRequired)
            first in reserved -> LinkClassification(LinkKind.Other)
            s.size == 1 -> LinkClassification(LinkKind.Channel, s[0])
            else -> LinkClassification(LinkKind.Other)
        }
    }

    override fun describe(classification: LinkClassification): String = when (classification.kind) {
        LinkKind.Channel -> "Instagram profile"
        else -> super.describe(classification)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Post, LinkKind.ShortVideo, LinkKind.Video ->
            AdapterSupport.idLine(classification) + listOf(
                "Instagram often asks for a sign-in before showing content, and private accounts are visible only to approved followers.",
                "Instagram shows media through its own app and player, not as a file you can save.",
            )
        LinkKind.Story -> listOf("Stories need an Instagram account and expire after 24 hours.")
        else -> AdapterSupport.idLine(classification)
    }
}
