package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises Dailymotion addresses. Detection only: nothing is fetched. */
object DailymotionAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Dailymotion
    override val brandTokens = listOf("dailymotion")

    private val domains = arrayOf("dailymotion.com", "dai.ly", "dmcdn.net")
    private val videoId = Regex("^[A-Za-z0-9]{4,}$")
    private val otherPaths = setOf("us", "fr", "about", "legal", "help", "browse", "explore", "channel", "videos", "trending")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "dmcdn.net")) return AdapterSupport.fileHost(url)
        if (AdapterSupport.hostIs(host, "dai.ly")) return withId(url.segments.firstOrNull())
        if (host == "geo.dailymotion.com") return withId(url.param("video"))

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "signin" || first == "login" || first == "register" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "video" -> withId(url.segments.getOrNull(1))
            first == "embed" && s.getOrNull(1) == "video" -> withId(url.segments.getOrNull(2))
            first == "playlist" -> LinkClassification(LinkKind.Playlist, url.segments.getOrNull(1))
            first == "search" -> LinkClassification(LinkKind.Search)
            first == "user" -> LinkClassification(LinkKind.Channel, url.segments.getOrNull(1))
            first in otherPaths -> LinkClassification(LinkKind.Other)
            s.size == 1 -> LinkClassification(LinkKind.Channel, url.segments[0])
            else -> LinkClassification(LinkKind.Other)
        }
    }

    /** Ids look like "x8abc12", sometimes followed by "_title-words". Only the id part is kept. */
    private fun withId(raw: String?): LinkClassification {
        val id = raw?.substringBefore('_')
        return if (id != null && videoId.matches(id)) LinkClassification(LinkKind.Video, id) else LinkClassification(LinkKind.Other)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Video ->
            AdapterSupport.idLine(classification) + listOf(
                "Dailymotion plays videos through its own player as streams, not as one file you can save.",
                "Some videos are limited by region, age or the owner's settings.",
            )
        else -> AdapterSupport.idLine(classification)
    }
}
