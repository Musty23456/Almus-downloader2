package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises X (Twitter) addresses. Detection only: nothing is fetched. */
object XAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.X
    override val brandTokens = listOf("twitter")

    private val domains = arrayOf("x.com", "twitter.com", "t.co", "twimg.com")
    private val imageFormats = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val reserved = setOf(
        "home", "explore", "search", "notifications", "messages", "settings", "compose", "login", "logout",
        "signup", "i", "hashtag", "intent", "share", "tos", "privacy", "about", "download", "jobs", "lists", "bookmarks",
    )

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "t.co")) {
            return if (url.segments.isEmpty()) LinkClassification(LinkKind.Other) else LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
        }
        if (AdapterSupport.hostIs(host, "twimg.com")) {
            val format = url.param("format")?.lowercase()
            if (format != null && format in imageFormats) return LinkClassification(LinkKind.DirectFile)
            return AdapterSupport.fileHost(url)
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "login" || (first == "i" && s.getOrNull(1) == "flow") ->
                LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "i" && (s.getOrNull(1) == "spaces" || s.getOrNull(1) == "broadcasts") ->
                LinkClassification(LinkKind.Live, url.segments.getOrNull(2))
            first == "i" && s.getOrNull(1) == "status" && AdapterSupport.isDigits(s.getOrNull(2)) ->
                LinkClassification(LinkKind.Post, s[2])
            first == "i" && s.getOrNull(1) == "web" && s.getOrNull(2) == "status" && AdapterSupport.isDigits(s.getOrNull(3)) ->
                LinkClassification(LinkKind.Post, s[3])
            first == "home" || first == "explore" || first == "notifications" -> LinkClassification(LinkKind.Home)
            first == "search" -> LinkClassification(LinkKind.Search)
            first in reserved -> LinkClassification(LinkKind.Other)
            s.getOrNull(1) == "status" && AdapterSupport.isDigits(s.getOrNull(2)) -> LinkClassification(LinkKind.Post, s[2])
            s.size == 1 -> LinkClassification(LinkKind.Channel, url.segments[0])
            else -> LinkClassification(LinkKind.Other)
        }
    }

    override fun describe(classification: LinkClassification): String = when (classification.kind) {
        LinkKind.Post -> "X (Twitter) post"
        LinkKind.Channel -> "X (Twitter) profile"
        else -> super.describe(classification)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Post ->
            AdapterSupport.idLine(classification) + listOf(
                "A post may contain a video, but X shows it through its own player, not as a file you can save.",
                "Posts from protected accounts are visible only to approved followers.",
            )
        LinkKind.Live -> listOf("Spaces and broadcasts are live; a live stream has no complete file.")
        LinkKind.ShortLink -> listOf("A t.co short link. It can lead to any website, so the real address is learned by following its redirects.")
        else -> AdapterSupport.idLine(classification)
    }
}
