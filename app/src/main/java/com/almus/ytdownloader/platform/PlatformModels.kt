package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.MediaDetails
import com.almus.ytdownloader.download.MediaKind

/** The sources the link detector can tell apart. [DirectLink] is the fallback for every other web address. */
enum class MediaPlatform(val displayName: String) {
    YouTube("YouTube"),
    Facebook("Facebook"),
    TikTok("TikTok"),
    Instagram("Instagram"),
    X("X (Twitter)"),
    Vimeo("Vimeo"),
    Dailymotion("Dailymotion"),
    Reddit("Reddit"),
    Pinterest("Pinterest"),
    DirectLink("Direct link"),
}

/** What a recognised address points to. [label] is a lower-case noun used inside sentences. */
enum class LinkKind(val label: String) {
    Video("video"),
    ShortVideo("short video"),
    Live("live stream or broadcast"),
    Playlist("playlist"),
    Channel("profile or channel"),
    Post("post"),
    Photo("photo"),
    Story("story"),
    Group("group"),
    Search("search page"),
    Home("home page"),
    Login("sign-in page"),
    Consent("consent page"),
    StreamEndpoint("streaming address"),
    ShortLink("short link"),
    DirectFile("file"),
    Other("page"),
    Unknown("link"),
}

/** Why a link cannot be used. The app never signs in, never bypasses protection and never works around any of these. */
enum class RestrictionReason {
    LoginRequired,
    PrivateOrProtected,
    Consent,
    Protected,
    TemporaryStream,
    AdaptiveStream,
    Unavailable,
    ;

    fun message(platformName: String): String = when (this) {
        LoginRequired ->
            "$platformName needs you to be signed in to see this. Almus Downloader never signs in for you " +
                "and never gets around a sign-in, so this link cannot be used."
        PrivateOrProtected ->
            "This content is often private or limited to certain people. Almus Downloader does not get " +
                "around privacy settings or access rules, so this link cannot be used."
        Consent ->
            "This is a consent or cookie page, not the content itself. Nothing can be saved from it."
        Protected ->
            "This content is paid or copy-protected (DRM). Almus Downloader does not remove or get " +
                "around protection, so nothing can be saved from this link."
        TemporaryStream ->
            "This is a temporary, signed address used by the $platformName player to stream pieces of a video. " +
                "It is not a file that can be saved, so this link cannot be used."
        AdaptiveStream ->
            "This is a streaming playlist or manifest. It points to many small pieces instead of one file, " +
                "so it cannot be saved as a single file."
        Unavailable ->
            "$platformName answered that this content is not available (removed, private, or blocked). " +
                "Nothing can be saved from this link."
    }
}

/** What the app should do next with the link. */
enum class LinkVerdict {
    /** Not a usable web address. */
    Invalid,

    /** Could run code or hide its real target. Never opened. */
    Blocked,

    /** An opaque short link: its redirects can be followed safely to learn the real address. */
    ShortLink,

    /** Looks like a direct link to a file. The server is still asked before anything is saved. */
    DirectFile,

    /** An ordinary web address of unknown content. The server is asked before anything is saved. */
    ServerCheck,

    /** A page of a known platform (video, post, profile ...). Recognised and explained, but not a file. */
    PageNotDownloadable,

    /** A known platform page that needs sign-in, is private, protected, a temporary stream or unavailable. */
    Restricted,
}

enum class SafetyNoteKind { InsecureHttp, LocalNetwork, InternationalizedName, LookalikeAddress }

/** A caution shown next to the result. It never blocks by itself. */
data class SafetyNote(val kind: SafetyNoteKind, val message: String)

/** What a safe redirect check saw, so the detector can use it. [status] is the final HTTP status, if known. */
data class ObservedResponse(val hops: List<String> = emptyList(), val status: Int? = null)

/**
 * Result of looking at one link. Pure data; building it never opens the link.
 * [url] is null when the link is invalid or blocked.
 */
data class LinkDetection(
    val input: String,
    val url: String?,
    val host: String?,
    val platform: MediaPlatform?,
    val kind: LinkKind,
    val contentId: String?,
    val verdict: LinkVerdict,
    val restriction: RestrictionReason?,
    /** Short heading, for example "YouTube video". */
    val title: String,
    /** Plain-language explanation of the verdict. Shown as is. */
    val message: String,
    /** Platform-specific facts and cautions. */
    val details: List<String>,
    val notes: List<SafetyNote>,
    /** Audio or video format when the address names a media file. */
    val media: MediaDetails?,
    val redirectHops: List<String> = emptyList(),
) {
    /** True when the existing link check (the server is asked what it offers) may go ahead. */
    val canCheckAsFile: Boolean get() = verdict == LinkVerdict.DirectFile || verdict == LinkVerdict.ServerCheck

    /** One short line for under the address field. */
    val hint: String
        get() = when (verdict) {
            LinkVerdict.Invalid, LinkVerdict.Blocked -> message
            LinkVerdict.DirectFile -> {
                val m = media
                val what = if (m == null) "file" else "${m.format} ${if (m.kind == MediaKind.Video) "video" else "audio"} file"
                "$title · $what"
            }
            LinkVerdict.ServerCheck -> "$title · the server is checked before anything is saved"
            LinkVerdict.ShortLink -> "$title · redirects are followed safely"
            LinkVerdict.PageNotDownloadable -> "$title · not a direct file"
            LinkVerdict.Restricted -> "$title · cannot be used"
        }

    /** "Source: ..." line for the download confirmation, or null when there is nothing useful to add. */
    val sourceLine: String?
        get() = when {
            platform == null -> null
            platform == MediaPlatform.DirectLink -> "Source: direct link"
            else -> "Source: file hosted by ${platform.displayName}"
        }
}
