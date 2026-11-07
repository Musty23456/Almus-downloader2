package com.almus.ytdownloader.platform

/**
 * What an adapter learned about one address on a host it owns.
 * [opaqueShortLink] is true for short links whose target cannot be read from the text (it needs a redirect check).
 */
data class LinkClassification(
    val kind: LinkKind,
    val contentId: String? = null,
    val restriction: RestrictionReason? = null,
    val opaqueShortLink: Boolean = false,
)

/**
 * One independent adapter per platform. An adapter only RECOGNISES addresses and describes them:
 * it never opens a link, never makes a network call and never downloads or extracts anything.
 * Detection is a separate step from downloading, and a recognised platform page is not made downloadable
 * by having an adapter.
 */
interface MediaPlatformAdapter {
    val platform: MediaPlatform

    /** Words used only to warn about look-alike addresses (for example "youtube"). */
    val brandTokens: List<String>

    /** True when [host] (lower case) belongs to this platform: its pages, short-link hosts and file hosts. */
    fun ownsHost(host: String): Boolean

    /** Reads an address on an owned host. Pure text handling. */
    fun classify(url: ParsedUrl): LinkClassification

    /** Heading such as "YouTube video". */
    fun describe(classification: LinkClassification): String =
        "${platform.displayName} ${classification.kind.label}"

    /** Platform-specific facts and cautions for this kind of address. */
    fun notesFor(classification: LinkClassification): List<String> = emptyList()

    /**
     * True when the HTTP status a plain page request got (401/403, or 404/410) may be read as "this content is
     * restricted or gone". A platform whose pages answer plain requests with bot-protection errors, and whose own
     * official service says whether content is public, returns false: that service decides, not the page status.
     */
    val pageStatusIsReliable: Boolean get() = true
}
