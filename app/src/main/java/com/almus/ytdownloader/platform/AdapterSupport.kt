package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.MimeTypes
import com.almus.ytdownloader.download.StreamingFormats

/** Small helpers the adapters share. They hold no platform knowledge. */
internal object AdapterSupport {
    /** Extensions that name a web page, not a file. */
    private val pageExtensions = setOf("html", "htm")

    /** File types that are common downloads but not in [MimeTypes]. */
    private val extraFileExtensions = setOf("iso", "exe", "msi", "dmg", "deb", "rpm", "bin", "img", "jar", "torrent")

    fun hostIs(host: String, vararg domains: String): Boolean = domains.any { UrlSafety.hostIs(host, it) }

    fun isDigits(text: String?): Boolean = !text.isNullOrEmpty() && text.all { it in '0'..'9' }

    /** The address's file extension when it names a downloadable file type, else null. */
    fun fileExtension(url: ParsedUrl): String? {
        val ext = url.extension ?: return null
        if (ext in pageExtensions) return null
        return if (MimeTypes.forExtension(ext) != null || ext in extraFileExtensions) ext else null
    }

    /** For a file host of a platform: a file by extension, a streaming manifest, or something else. */
    fun fileHost(url: ParsedUrl): LinkClassification = when {
        fileExtension(url) != null -> LinkClassification(LinkKind.DirectFile)
        StreamingFormats.detect(url.url, null) != null ->
            LinkClassification(LinkKind.StreamEndpoint, restriction = RestrictionReason.AdaptiveStream)
        else -> LinkClassification(LinkKind.Other)
    }

    fun idLine(classification: LinkClassification): List<String> =
        classification.contentId?.let { listOf("ID: $it") } ?: emptyList()
}
