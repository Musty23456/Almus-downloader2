package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/** Recognises YouTube addresses. It only describes them; it cannot and does not fetch any video. */
object YouTubeAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.YouTube
    override val brandTokens = listOf("youtube")

    private val domains = arrayOf("youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "googlevideo.com")
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val reserved = setOf("feed", "gaming", "premium", "podcasts", "hashtag", "playlists", "history", "account")

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        val first = url.seg(0)

        if (AdapterSupport.hostIs(host, "googlevideo.com")) {
            return LinkClassification(LinkKind.StreamEndpoint, restriction = RestrictionReason.TemporaryStream)
        }
        if (AdapterSupport.hostIs(host, "ytimg.com")) return AdapterSupport.fileHost(url)
        if (host == "youtu.be" || host.endsWith(".youtu.be")) return withId(url.segments.firstOrNull(), LinkKind.Video)
        if (host == "consent.youtube.com") {
            return LinkClassification(LinkKind.Consent, restriction = RestrictionReason.Consent)
        }
        if (host == "accounts.youtube.com" || first == "signin" || first == "servicelogin") {
            return LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
        }

        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first == "watch" -> {
                val v = url.param("v")
                when {
                    v != null && videoId.matches(v) -> LinkClassification(LinkKind.Video, v)
                    url.param("list") != null -> LinkClassification(LinkKind.Playlist, url.param("list"))
                    else -> LinkClassification(LinkKind.Other)
                }
            }
            first == "shorts" -> withId(url.segments.getOrNull(1), LinkKind.ShortVideo)
            first == "live" -> withId(url.segments.getOrNull(1), LinkKind.Live)
            first == "embed" || first == "v" -> withId(url.segments.getOrNull(1), LinkKind.Video)
            first == "playlist" -> LinkClassification(LinkKind.Playlist, url.param("list"))
            first.startsWith("@") || first == "channel" || first == "c" || first == "user" ->
                LinkClassification(LinkKind.Channel, url.segments.getOrNull(if (first.startsWith("@")) 0 else 1))
            first == "results" -> LinkClassification(LinkKind.Search)
            first in reserved -> LinkClassification(LinkKind.Other)
            else -> LinkClassification(LinkKind.Other)
        }
    }

    private fun withId(id: String?, kind: LinkKind): LinkClassification =
        if (id != null && videoId.matches(id)) LinkClassification(kind, id) else LinkClassification(LinkKind.Other)

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Video, LinkKind.ShortVideo, LinkKind.Live ->
            AdapterSupport.idLine(classification) + listOf(
                "YouTube delivers videos to its own player as separate streams, not as one file you can save.",
                "Saving videos may also be limited by YouTube's terms and by the owner's settings.",
            )
        LinkKind.Playlist -> AdapterSupport.idLine(classification) + "A playlist is a list of videos, not a file."
        LinkKind.StreamEndpoint -> listOf("Addresses on this host expire quickly and are meant for the player only.")
        else -> AdapterSupport.idLine(classification)
    }
}
