package com.almus.ytdownloader.media

import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.MediaKind
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import java.net.URLDecoder

/** One answer of an HTTP GET. */
data class MediaReply(val status: Int, val body: String)

/** Thrown by a [MediaHttp] when the service cannot be reached or its answer is too large. */
class MediaNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The only door to the network for the catalog services. Tests replace it. */
interface MediaHttp {
    suspend fun get(url: String): MediaReply
}

sealed interface CatalogResult {
    data class Found(val catalog: MediaCatalog) : CatalogResult

    /** The source answered but offers nothing downloadable for this address. [message] says why in plain words. */
    data class Unavailable(val message: String) : CatalogResult

    data class Failed(val message: String) : CatalogResult
}

/**
 * A source that publishes several real versions of a file for download (an authorized source). It recognises its own
 * addresses by text alone and asks its own public service for the list. It never reads a web page, never signs in
 * and never touches a stream.
 */
interface MediaSource {
    val id: String

    /** A key for the item when [url] is an address this source handles, else null. Text only, no network. */
    fun match(url: String): String?

    suspend fun load(key: String): CatalogResult
}

data class MediaMatch(val source: MediaSource, val key: String)

class MediaSources(private val sources: List<MediaSource>) {
    fun match(raw: String?): MediaMatch? {
        val valid = UrlValidator.validate(raw) as? UrlValidation.Valid ?: return null
        for (source in sources) {
            val key = source.match(valid.url) ?: continue
            return MediaMatch(source, key)
        }
        return null
    }
}

/** Small text helpers shared by the sources. */
internal object UrlText {
    /** The decoded path segments of an http(s) address (empty segments dropped). */
    fun segments(url: String): List<String> {
        val afterScheme = url.substringAfter("://", "")
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
    }

    fun host(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()

    fun decode(part: String): String = try {
        URLDecoder.decode(part.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        part
    }
}

/**
 * The catalog of an ordinary direct media file: exactly the one file the link check found. Its size and format are
 * what the server stated; the resolution and the duration are not stated by a plain file link, so they stay unknown.
 */
object DirectFileSource {
    const val ID = "direct"

    fun catalogOf(info: LinkInfo): MediaCatalog? {
        val media = info.media ?: return null
        val kind = if (media.kind == MediaKind.Video) VariantKind.Video else VariantKind.Audio
        val variant = MediaVariant(
            id = "direct",
            kind = kind,
            container = media.format,
            mimeType = info.mimeType,
            url = info.url,
            sizeBytes = info.contentLength.takeIf { it > 0 },
            // The file exactly as the server provides it.
            isOriginal = true,
        )
        return MediaCatalog(
            sourceId = ID,
            sourceLabel = "Direct file link",
            basis = "A direct link to a single file. The file is saved exactly as the server provides it.",
            title = info.fileName,
            durationSeconds = null,
            pageUrl = null,
            variants = listOf(variant),
            notices = listOf("A plain file link does not say its resolution or length, so only the format and size are shown."),
            verifiedInfo = info,
        )
    }
}
