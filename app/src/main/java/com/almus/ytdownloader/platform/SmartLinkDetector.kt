package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.MediaFormats
import com.almus.ytdownloader.download.UrlError
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import com.almus.ytdownloader.platform.adapters.DailymotionAdapter
import com.almus.ytdownloader.platform.adapters.DirectLinkAdapter
import com.almus.ytdownloader.platform.adapters.FacebookAdapter
import com.almus.ytdownloader.platform.adapters.InstagramAdapter
import com.almus.ytdownloader.platform.adapters.PinterestAdapter
import com.almus.ytdownloader.platform.adapters.RedditAdapter
import com.almus.ytdownloader.platform.adapters.TikTokAdapter
import com.almus.ytdownloader.platform.adapters.VimeoAdapter
import com.almus.ytdownloader.platform.adapters.XAdapter
import com.almus.ytdownloader.platform.adapters.YouTubeAdapter

/**
 * Looks at a link and says what it is. This is DETECTION, not downloading:
 * - it only reads the text of the address (no connection, no page load, no app launch),
 * - it validates the address with the app's [UrlValidator] first,
 * - it picks the first [MediaPlatformAdapter] that owns the host, or the direct-link fallback,
 * - it never makes a platform page downloadable, never signs in and never gets around any restriction.
 * Pure Kotlin, unit tested.
 */
class SmartLinkDetector(
    private val adapters: List<MediaPlatformAdapter> = defaultAdapters,
    private val fallback: MediaPlatformAdapter = DirectLinkAdapter,
) {
    /**
     * @param observed what a safe redirect check saw (final status), when one was done. A 401/403 or 404/410
     * on a known platform page turns it into a restricted link with a matching explanation.
     */
    fun detect(raw: String?, observed: ObservedResponse? = null): LinkDetection {
        val text = raw?.trim().orEmpty()
        return when (val validation = UrlValidator.validate(text)) {
            is UrlValidation.Invalid -> rejected(text, validation.error)
            is UrlValidation.Valid -> analyze(text, validation, observed)
        }
    }

    private fun analyze(input: String, valid: UrlValidation.Valid, observed: ObservedResponse?): LinkDetection {
        val parsed = ParsedUrl.parse(valid)
        val adapter = adapters.firstOrNull { it.ownsHost(valid.host) } ?: fallback
        val c = adapter.classify(parsed)
        val platformName = adapter.platform.displayName

        var verdict = verdictOf(c)
        var restriction = c.restriction
        val status = observed?.status
        if (verdict == LinkVerdict.PageNotDownloadable && status != null && adapter.pageStatusIsReliable) {
            if (status == 401 || status == 403) {
                verdict = LinkVerdict.Restricted
                restriction = RestrictionReason.PrivateOrProtected
            } else if (status == 404 || status == 410) {
                verdict = LinkVerdict.Restricted
                restriction = RestrictionReason.Unavailable
            }
        }

        val media = if (verdict == LinkVerdict.DirectFile) MediaFormats.describe(null, parsed.lastSegment) else null
        val title = when (verdict) {
            LinkVerdict.DirectFile ->
                if (adapter.platform == MediaPlatform.DirectLink) "Direct link" else "$platformName file"
            LinkVerdict.ServerCheck -> "Web link"
            else -> adapter.describe(c)
        }
        val message = when (verdict) {
            LinkVerdict.DirectFile ->
                "This looks like a direct link to a file. The server is asked what it really offers before anything is saved."
            LinkVerdict.ServerCheck ->
                "This is a web link. The server is asked what it offers before anything is saved."
            LinkVerdict.ShortLink ->
                "This is a short link. The app can follow its redirects safely (at most $MAX_HOPS, web addresses only) " +
                    "to see where it leads. Nothing is opened or saved while it does."
            LinkVerdict.PageNotDownloadable ->
                "This is a link to a $platformName ${c.kind.label}, not to a file. Almus Downloader saves files that " +
                    "a server offers directly. It does not take videos or media out of $platformName pages, so " +
                    "nothing can be saved from this link."
            LinkVerdict.Restricted -> (restriction ?: RestrictionReason.PrivateOrProtected).message(platformName)
            LinkVerdict.Invalid, LinkVerdict.Blocked -> ""
        }

        return LinkDetection(
            input = input,
            url = valid.url,
            host = valid.host,
            platform = adapter.platform,
            kind = c.kind,
            contentId = c.contentId,
            verdict = verdict,
            restriction = restriction,
            title = title,
            message = message,
            details = adapter.notesFor(c),
            notes = safetyNotes(valid, adapter),
            media = media,
            redirectHops = observed?.hops.orEmpty(),
        )
    }

    private fun verdictOf(c: LinkClassification): LinkVerdict = when {
        c.restriction != null -> LinkVerdict.Restricted
        c.opaqueShortLink -> LinkVerdict.ShortLink
        c.kind == LinkKind.DirectFile -> LinkVerdict.DirectFile
        c.kind == LinkKind.Unknown -> LinkVerdict.ServerCheck
        else -> LinkVerdict.PageNotDownloadable
    }

    private fun safetyNotes(valid: UrlValidation.Valid, adapter: MediaPlatformAdapter): List<SafetyNote> {
        val notes = mutableListOf<SafetyNote>()
        if (!valid.isSecure) {
            notes += SafetyNote(
                SafetyNoteKind.InsecureHttp,
                "This link uses plain http, so anyone on the network could read or change what is sent.",
            )
        }
        if (UrlSafety.isLocalOrPrivate(valid.host)) {
            notes += SafetyNote(
                SafetyNoteKind.LocalNetwork,
                "This address points to a device or name on a local network, not to a public website.",
            )
        }
        if (UrlSafety.hasPunycode(valid.host)) {
            notes += SafetyNote(
                SafetyNoteKind.InternationalizedName,
                "This address uses an internationalized (xn--) name, which can imitate a familiar site. Check the spelling.",
            )
        }
        if (adapter.platform == MediaPlatform.DirectLink) {
            lookalikeOf(valid.host)?.let { name ->
                notes += SafetyNote(
                    SafetyNoteKind.LookalikeAddress,
                    "This address mentions $name but does not belong to $name. Make sure you trust this site.",
                )
            }
        }
        return notes
    }

    /** Name of a platform whose brand appears in [host] although the host is not that platform's. */
    private fun lookalikeOf(host: String): String? {
        val squashed = UrlSafety.squash(host)
        for (a in adapters) {
            if (a.brandTokens.any { squashed.contains(it) }) return a.platform.displayName
        }
        return null
    }

    private fun rejected(text: String, error: UrlError): LinkDetection {
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        val blocked = error == UrlError.HasCredentials ||
            (error == UrlError.UnsupportedScheme && scheme != null && scheme in DANGEROUS_SCHEMES)
        val message = when {
            error == UrlError.HasCredentials ->
                "This link has a username or password inside the address, a common way to hide where a link really " +
                    "goes. It was blocked and nothing was opened."
            blocked ->
                "Links that start with \"$scheme:\" can run code or open other apps, so this one was blocked. " +
                    "Nothing was opened or downloaded."
            else -> error.message
        }
        return LinkDetection(
            input = text,
            url = null,
            host = null,
            platform = null,
            kind = LinkKind.Unknown,
            contentId = null,
            verdict = if (blocked) LinkVerdict.Blocked else LinkVerdict.Invalid,
            restriction = null,
            title = if (blocked) "Blocked link" else "Invalid link",
            message = message,
            details = emptyList(),
            notes = emptyList(),
            media = null,
        )
    }

    companion object {
        const val MAX_HOPS = SafeRedirectResolver.MAX_REDIRECTS

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
        private val DANGEROUS_SCHEMES = setOf(
            "javascript", "data", "vbscript", "file", "content", "intent", "blob", "filesystem",
            "about", "view-source", "android-app", "market",
        )

        /** One independent adapter per platform. The order does not matter: no two adapters own the same host. */
        val defaultAdapters: List<MediaPlatformAdapter> = listOf(
            YouTubeAdapter,
            FacebookAdapter,
            TikTokAdapter,
            InstagramAdapter,
            XAdapter,
            VimeoAdapter,
            DailymotionAdapter,
            RedditAdapter,
            PinterestAdapter,
        )
    }
}
