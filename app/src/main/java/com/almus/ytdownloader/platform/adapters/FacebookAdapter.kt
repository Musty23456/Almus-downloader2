package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl
import com.almus.ytdownloader.platform.RestrictionReason

/**
 * Recognises Facebook addresses: videos, Reels, live videos, posts, photos, stories, groups, profiles and short links.
 * Detection only: nothing is fetched and no sign-in is attempted. What is done with a recognised video
 * (official public details, ways to open it in Facebook) lives in the separate `facebook` package.
 */
object FacebookAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.Facebook
    override val brandTokens = listOf("facebook")

    private val domains = arrayOf("facebook.com", "fb.com", "fb.watch", "fbcdn.net")
    private val loginPaths = setOf("login", "login.php", "checkpoint", "recover", "r.php")

    /** First path segments that are Facebook pages or tools, never a person's or page's name. */
    private val otherPaths = setOf(
        "events", "marketplace", "gaming", "messages", "pages", "help", "policies", "settings",
        "reels", "home.php", "friends", "hashtag", "bookmarks", "notifications", "saved", "live",
        "ads", "business", "dialog", "sharer", "sharer.php", "plugins", "l.php", "search", "privacy", "about",
    )

    override fun ownsHost(host: String): Boolean = AdapterSupport.hostIs(host, *domains)

    override fun classify(url: ParsedUrl): LinkClassification {
        val host = url.host
        if (AdapterSupport.hostIs(host, "fbcdn.net")) return AdapterSupport.fileHost(url)
        if (AdapterSupport.hostIs(host, "fb.watch")) {
            return if (url.segments.isEmpty()) LinkClassification(LinkKind.Home) else LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
        }

        val s = url.segments.map { it.lowercase() }
        val first = s.firstOrNull()
        return when {
            first == null -> LinkClassification(LinkKind.Home)
            first in loginPaths -> LinkClassification(LinkKind.Login, restriction = RestrictionReason.LoginRequired)
            first == "share" -> LinkClassification(LinkKind.ShortLink, opaqueShortLink = true)
            first == "watch" -> watch(s, url)
            first == "reel" || (first == "reels" && s.size > 1) -> {
                val id = s.getOrNull(1)
                if (AdapterSupport.isDigits(id)) LinkClassification(LinkKind.ShortVideo, id) else LinkClassification(LinkKind.Other)
            }
            first == "video.php" -> {
                val v = url.param("v")
                if (AdapterSupport.isDigits(v)) LinkClassification(LinkKind.Video, v) else LinkClassification(LinkKind.Other)
            }
            first == "stories" -> LinkClassification(LinkKind.Story, restriction = RestrictionReason.LoginRequired)
            first == "groups" -> LinkClassification(LinkKind.Group, restriction = RestrictionReason.PrivateOrProtected)
            first == "photo" || first == "photo.php" || first == "photos" || "photos" in s ->
                LinkClassification(LinkKind.Photo, url.param("fbid"))
            "videos" in s -> {
                val id = s.drop(s.indexOf("videos") + 1).firstOrNull { AdapterSupport.isDigits(it) }
                if (id != null) LinkClassification(LinkKind.Video, id) else LinkClassification(LinkKind.Other)
            }
            first == "permalink.php" || first == "story.php" || "posts" in s -> LinkClassification(LinkKind.Post)
            first == "profile.php" -> LinkClassification(LinkKind.Channel, url.param("id"))
            first in otherPaths -> LinkClassification(LinkKind.Other)
            s.size == 1 -> LinkClassification(LinkKind.Channel, first)
            else -> LinkClassification(LinkKind.Other)
        }
    }

    /** `/watch/?v=ID` is a video, `/watch/live/?v=ID` a live video; `/watch` alone is the video feed. */
    private fun watch(s: List<String>, url: ParsedUrl): LinkClassification {
        val v = url.param("v")
        if (!AdapterSupport.isDigits(v)) return LinkClassification(LinkKind.Other)
        val kind = if (s.getOrNull(1) == "live") LinkKind.Live else LinkKind.Video
        return LinkClassification(kind, v)
    }

    override fun describe(classification: LinkClassification): String = when (classification.kind) {
        LinkKind.Channel -> "Facebook profile or page"
        LinkKind.ShortVideo -> "Facebook Reel"
        else -> super.describe(classification)
    }

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.Video, LinkKind.ShortVideo, LinkKind.Live, LinkKind.Post, LinkKind.Photo ->
            AdapterSupport.idLine(classification) + listOf(
                "Much Facebook content is visible only to signed-in people or to the audience the owner chose.",
                "Facebook delivers video through its own player, not as a file you can save.",
            )
        LinkKind.Group -> listOf("Group posts are usually visible only to members. You can open the link in Facebook if you are a member.")
        LinkKind.Story -> listOf("Stories need a Facebook account and expire after a short time.")
        LinkKind.ShortLink -> listOf("A Facebook short link. The real address is learned by following its redirects.")
        else -> AdapterSupport.idLine(classification)
    }
}
