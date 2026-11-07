package com.almus.ytdownloader.platform.adapters

import com.almus.ytdownloader.platform.AdapterSupport
import com.almus.ytdownloader.platform.LinkClassification
import com.almus.ytdownloader.platform.LinkKind
import com.almus.ytdownloader.platform.MediaPlatform
import com.almus.ytdownloader.platform.MediaPlatformAdapter
import com.almus.ytdownloader.platform.ParsedUrl

/**
 * Fallback for every web address that no platform adapter owns. A known file extension means a direct
 * file link; anything else is an ordinary web link whose content the server must tell us (the existing
 * link check does that). This adapter never refuses on its own: the server's answer decides.
 */
object DirectLinkAdapter : MediaPlatformAdapter {
    override val platform = MediaPlatform.DirectLink
    override val brandTokens: List<String> = emptyList()

    override fun ownsHost(host: String): Boolean = true

    override fun classify(url: ParsedUrl): LinkClassification =
        if (AdapterSupport.fileExtension(url) != null) LinkClassification(LinkKind.DirectFile) else LinkClassification(LinkKind.Unknown)

    override fun notesFor(classification: LinkClassification): List<String> = when (classification.kind) {
        LinkKind.DirectFile -> listOf("The file type is judged from the address only. The server's answer is checked before anything is saved.")
        else -> listOf("Nothing is known about this address yet. The server is asked what it offers before anything is saved.")
    }
}
