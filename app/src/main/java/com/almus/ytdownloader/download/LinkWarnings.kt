package com.almus.ytdownloader.download

enum class LinkWarning { Insecure, WebPage, UnknownSize, LargeFile, Apk, MaybeLive, NoResume }

/** Things the user should know before approving a download. Pure Kotlin, unit tested. */
object LinkWarnings {
    const val LARGE_FILE_BYTES = 500L * 1024 * 1024

    fun of(info: LinkInfo): List<LinkWarning> = buildList {
        if (info.insecure) add(LinkWarning.Insecure)
        if (info.looksLikeWebPage) add(LinkWarning.WebPage)
        if (info.contentLength < 0) add(LinkWarning.UnknownSize)
        if (info.contentLength >= LARGE_FILE_BYTES) add(LinkWarning.LargeFile)
        if (FileNameResolver.extensionOf(info.fileName)?.lowercase() == "apk" ||
            info.mimeType == "application/vnd.android.package-archive"
        ) {
            add(LinkWarning.Apk)
        }
        // Audio or video without a size can be an endless stream that only looks like a file.
        if (info.media != null && info.contentLength < 0) add(LinkWarning.MaybeLive)
        if (info.media != null && info.acceptsRanges == false) add(LinkWarning.NoResume)
    }
}
