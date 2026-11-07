package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises Vimeo addresses. Detection only: nothing is fetched and no privacy setting is touched. */
object VimeoAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Vimeo
    override val brandTokens = listOf("vimeo")

    private val domains = arrayOf("vimeo.com", "vimeocdn.com")
    private val otherPaths = setOf("watch", "categories", "staffpicks", "features", "upload", "pricing", "about", "blog", "help", "terms", "privacy", "create", "stock", "enterprise")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "vimeocdn.com")) return AdapterSupport.fileHost(url)
        if (host == "player.vimeo.com") {
            if (url.seg(0) == "video" && AdapterSupport.isDigits(url.seg(1))) return LinkClassification(LinkKind.Video, url.seg(1))
            return AdapterSupport.fileHost(url)
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "log_in" || first == "login" || first == "join" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "ondemand" -> LinkClassification(LinkKind.Other, restriction = RestrictionReason.Protected)
            AdapterSupport.isDigits(first) -> LinkClassification(LinkKind.Video, first)
            first == "channels" || first == "groups" || first == "album" || first == "showcase" -> {
                val id = lastDigits(s)
                if (id != null) LinkClassification(LinkKind.Video, id) else LinkClassification(LinkKind.Playlist, s.getOrNull(1))
            }
            first == "search" -> LinkClassification(LinkKind.Search)
            first == "event" || first == "events" -> LinkClassification(LinkKind.Live, s.getOrNull(1))
            first in otherPaths -> LinkClassification(LinkKind.Other)
            s.size == 1 -> LinkClassification(LinkKind.Channel, url.segments[0])
            else -> LinkClassification(LinkKind.Other)
        }
    }

    /** The video id after a "video" or "videos" segment, or the last all-digit segment. */
    private fun lastDigits(s: List<String>): String? {
        val marker = s.indexOfLast { it == "video" || it == "videos" }
        if (marker >= 0) return s.getOrNull(marker + 1)?.takeIf { AdapterSupport.isDigits(it) }
        return if (s.size >= 3) s.last().takeIf { AdapterSupport.isDigits(it) } else null
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Video ->
            AdapterSupport.idLine(classification) + listOf(
                "Vimeo owners can make a video private, password-protected, domain-restricted or not downloadable. Almus Downloader does not get around those settings.",
                "Vimeo plays videos through its own player, not as a file you can save from this page.",
            )
        else -> AdapterSupport.idLine(classification)
    }
}
