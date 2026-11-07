package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/**
 * Recognises TikTok addresses: videos (`/@name/video/ID`, `/embed/v2/ID`, `/player/v1/ID`, legacy `/v/ID.html`),
 * photo posts, live pages, profiles, sign-in pages and short links (`vm.`, `vt.` and `/t/...`).
 * Detection only: nothing is fetched and no sign-in is attempted. What is done with a recognised video (public
 * details from TikTok's official oEmbed service, ways to open it in TikTok) lives in the separate `tiktok` package.
 */
object TikTokAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.TikTok
    override val brandTokens = listOf("tiktok")

    /**
     * A plain request for a TikTok page is often answered with a bot-protection error that says nothing about the
     * video. TikTok's own oEmbed service tells whether a video is public, so that answer is used instead.
     */
    override val pageStatusIsReliable: Boolean = false

    private val domains = arrayOf("tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokv.com")
    private val shortHosts = setOf("vm.tiktok.com", "vt.tiktok.com")
    private val otherPaths = setOf("tag", "music", "discover", "search", "foryou", "following", "explore", "trending", "upload", "legal", "about")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (!AdapterSupport.hostIs(host, "tiktok.com")) return AdapterSupport.fileHost(url)
        if (host in shortHosts) {
            return if (url.segments.isEmpty()) LinkClassification(LinkKind.Home) else LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        val second = s.getOrNull(1)
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "login" || first == "signup" -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "t" && s.size >= 2 -> LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
            first == "embed" -> {
                val id = s.lastOrNull()
                if (s.size >= 2 && AdapterSupport.isDigits(id)) LinkClassification(LinkKind.Video, id) else LinkClassification(LinkKind.Other)
            }
            first == "player" && second == "v1" && AdapterSupport.isDigits(s.getOrNull(2)) -> LinkClassification(LinkKind.Video, s[2])
            first == "v" && s.size == 2 -> {
                // Legacy mobile address: /v/ID.html
                val id = s[1].removeSuffix(".html")
                if (AdapterSupport.isDigits(id)) LinkClassification(LinkKind.Video, id) else LinkClassification(LinkKind.Other)
            }
            first == "live" -> LinkClassification(LinkKind.Live)
            first.startsWith("@") -> when {
                second == "video" && AdapterSupport.isDigits(s.getOrNull(2)) -> LinkClassification(LinkKind.Video, s[2])
                second == "photo" && AdapterSupport.isDigits(s.getOrNull(2)) -> LinkClassification(LinkKind.Photo, s[2])
                second == "live" -> LinkClassification(LinkKind.Live)
                second == null -> LinkClassification(LinkKind.Channel, first)
                else -> LinkClassification(LinkKind.Other)
            }
            first in otherPaths -> LinkClassification(LinkKind.Other)
            else -> LinkClassification(LinkKind.Other)
        }
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Video, LinkKind.Photo ->
            AdapterSupport.idLine(classification) + listOf(
                "TikTok shows videos through its own player, not as a file you can save.",
                "The owner may restrict who can view, download or reuse a video.",
            )
        LinkKind.Live -> listOf("A live broadcast never ends while it is on, so there is no complete file.")
        LinkKind.ShortLink -> listOf("A TikTok short link. The real address is learned by following its redirects.")
        else -> AdapterSupport.idLine(classification)
    }
}
