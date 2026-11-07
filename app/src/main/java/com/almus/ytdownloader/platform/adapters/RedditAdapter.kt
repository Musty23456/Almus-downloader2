package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises Reddit addresses. Detection only: nothing is fetched. */
object RedditAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Reddit
    override val brandTokens = listOf("reddit")

    private val domains = arrayOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com")
    private val postId = Regex("^[A-Za-z0-9]{4,10}$")
    private val otherPaths = setOf("r", "user", "u", "search", "login", "register", "settings", "premium", "coins", "wiki", "help", "best", "hot", "new", "top", "popular", "all", "rising")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "redditmedia.com", "redditstatic.com")) return AdapterSupport.fileHost(url)
        if (host == "redd.it") return postOrOther(url.segments.firstOrNull(), LinkKind.Post)
        if (AdapterSupport.hostIs(host, "redd.it")) {
            // i.redd.it, preview.redd.it, v.redd.it ...: file hosts.
            val file = AdapterSupport.fileHost(url)
            if (file.kind != LinkKind.Other) return file
            if (host == "v.redd.it") return postOrOther(url.segments.firstOrNull(), LinkKind.Video)
            return file
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "login" || first == "register" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "r" && s.getOrNull(2) == "comments" -> postOrOther(url.segments.getOrNull(3), LinkKind.Post)
            first == "r" && s.getOrNull(2) == "s" && s.size >= 4 -> LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
            first == "comments" -> postOrOther(url.segments.getOrNull(1), LinkKind.Post)
            first == "gallery" -> postOrOther(url.segments.getOrNull(1), LinkKind.Post)
            first == "search" -> LinkClassification(LinkKind.Search)
            first == "r" && s.size >= 2 -> LinkClassification(LinkKind.Channel, url.segments[1])
            (first == "user" || first == "u") && s.size >= 2 -> LinkClassification(LinkKind.Channel, url.segments[1])
            first in otherPaths -> LinkClassification(LinkKind.Other)
            else -> LinkClassification(LinkKind.Other)
        }
    }

    private fun postOrOther(id: String?, kind: LinkKind): LinkClassification =
        if (id != null && postId.matches(id)) LinkClassification(kind, id) else LinkClassification(LinkKind.Other)

    override fun describe(classification: LinkClassification): String = when (classification.kind) {
        LinkKind.Channel -> "Reddit community or profile"
        else -> super.describe(classification)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Post ->
            AdapterSupport.idLine(classification) + listOf(
                "A Reddit post is a web page that may contain text, links, images or video. It is not a file.",
                "Some communities are private, quarantined or restricted to adults and need a sign-in.",
            )
        LinkKind.Video ->
            AdapterSupport.idLine(classification) + "Reddit stores video and sound as separate streams, so there is no single file at this address."
        LinkKind.DirectFile -> listOf("A file hosted by Reddit. Reddit videos stored this way may contain no sound.")
        else -> AdapterSupport.idLine(classification)
    }
}
