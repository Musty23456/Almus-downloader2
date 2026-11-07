package com.almus.ytdownloader.media.sources

import com.almus.ytdownloader.download.MediaFormats
import com.almus.ytdownloader.download.MediaKind
import com.almus.ytdownloader.media.CatalogResult
import com.almus.ytdownloader.media.MediaCatalog
import com.almus.ytdownloader.media.MediaHttp
import com.almus.ytdownloader.media.MediaNetworkException
import com.almus.ytdownloader.media.MediaSource
import com.almus.ytdownloader.media.MediaVariant
import com.almus.ytdownloader.media.UrlText
import com.almus.ytdownloader.media.VariantKind
import com.almus.ytdownloader.util.MiniJson
import java.net.URLEncoder

/**
 * Wikimedia Commons audio and video files (`commons.wikimedia.org/wiki/File:...`). Commons is a library of freely
 * licensed files. Its public API lists the original file and every version the site made of it (different sizes
 * and formats) with address, frame size and bandwidth. Only files on Wikimedia's own file host are accepted. Sizes
 * of the site-made versions are not stated, so they are estimated from bandwidth and duration and labelled so.
 */
class CommonsSource(private val http: MediaHttp) : MediaSource {
    override val id: String = "wikimedia-commons"

    override fun match(url: String): String? {
        if (UrlText.host(url) != "commons.wikimedia.org") return null
        val segments = UrlText.segments(url)
        if (segments.size < 2 || segments[0] != "wiki") return null
        val title = segments[1].replace('_', ' ')
        if (!title.startsWith("File:", ignoreCase = true) || title.length <= 5 || title.length > 300) return null
        return "File:" + title.substring(5).trim()
    }

    override suspend fun load(key: String): CatalogResult {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
            "&prop=videoinfo&viprop=derivatives%7Curl%7Csize%7Cmime%7Cmediatype%7Cextmetadata&titles=" +
            URLEncoder.encode(key, "UTF-8")
        val reply = try {
            http.get(url)
        } catch (_: MediaNetworkException) {
            return CatalogResult.Failed("Could not reach Wikimedia Commons. Check your connection and try again.")
        }
        return when {
            reply.status == 200 -> CommonsParser.parse(reply.body)
            reply.status == 429 -> CatalogResult.Failed("Wikimedia Commons is limiting requests right now. Try again later.")
            else -> CatalogResult.Failed("Wikimedia Commons is not answering right now (status ${reply.status}). Try again later.")
        }
    }
}

internal object CommonsParser {
    private const val MAX_SECONDS = 359_999
    private val tags = Regex("<[^>]*>")
    private val codecsPattern = Regex("codecs=\"?([^\"]*)\"?")

    fun parse(body: String): CatalogResult {
        val root = MiniJson.parseObject(body) ?: return CatalogResult.Failed("Wikimedia Commons gave an answer that could not be read.")
        val query = root["query"] as? Map<*, *> ?: return CatalogResult.Unavailable("Wikimedia Commons has no file with this address.")
        val page = (query["pages"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: return CatalogResult.Unavailable("Wikimedia Commons has no file with this address.")
        if (page["missing"] == true || page["invalid"] == true) {
            return CatalogResult.Unavailable("Wikimedia Commons has no file with this address.")
        }
        val info = ((page["videoinfo"] ?: page["imageinfo"]) as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: return CatalogResult.Unavailable("This is not an audio or video file, so there is nothing to choose here.")
        val mediatype = (info["mediatype"] as? String)?.uppercase()
        if (mediatype != "VIDEO" && mediatype != "AUDIO") {
            return CatalogResult.Unavailable("This is not an audio or video file, so there is nothing to choose here.")
        }

        val duration = (info["duration"] as? Double)?.toInt()?.takeIf { it in 1..MAX_SECONDS }
        val variants = mutableListOf<MediaVariant>()

        // The original upload.
        val originalUrl = officialFileUrl(info["url"] as? String)
        val originalMime = info["mime"] as? String
        if (originalUrl != null) {
            describe(originalMime)?.let { (kind, container) ->
                variants += MediaVariant(
                    id = "cm:original",
                    kind = kind,
                    container = container,
                    mimeType = baseMime(originalMime),
                    url = originalUrl,
                    widthPx = if (kind == VariantKind.Video) positive(info["width"]) else null,
                    heightPx = if (kind == VariantKind.Video) positive(info["height"]) else null,
                    sizeBytes = (info["size"] as? Double)?.toLong()?.takeIf { it > 0 },
                    isOriginal = true,
                )
            }
        }

        // Versions the site made from it.
        for (entry in info["derivatives"] as? List<*> ?: emptyList<Any?>()) {
            val d = entry as? Map<*, *> ?: continue
            val src = officialFileUrl(d["src"] as? String) ?: continue
            if (src == originalUrl) continue
            val type = d["type"] as? String
            val (kind, container) = describe(type) ?: continue
            val codecs = codecsOf(type)
            variants += MediaVariant(
                id = "cm:" + ((d["transcodekey"] as? String) ?: src),
                kind = kind,
                container = container,
                mimeType = baseMime(type),
                url = src,
                widthPx = if (kind == VariantKind.Video) positive(d["width"]) else null,
                heightPx = if (kind == VariantKind.Video) positive(d["height"]) else null,
                bitrateKbps = (d["bandwidth"] as? Double)?.let { (it / 1000.0).toInt() }?.takeIf { it > 0 },
                videoCodec = if (kind == VariantKind.Video) codecs.getOrNull(0) else null,
                audioCodec = if (kind == VariantKind.Video) codecs.getOrNull(1) else codecs.getOrNull(0),
                hasAudio = if (kind == VariantKind.Video && codecs.size >= 2) true else null,
            )
        }
        if (variants.isEmpty()) return CatalogResult.Unavailable("This file has no versions that can be downloaded.")

        val title = ((page["title"] as? String) ?: "").removePrefix("File:").ifBlank { null }
        val license = plain(((info["extmetadata"] as? Map<*, *>)?.get("LicenseShortName") as? Map<*, *>)?.get("value") as? String)
        val notices = buildList {
            add("Offered for download by Wikimedia Commons. Reuse follows the file's license; check its page.")
            if (license != null) add("License: $license")
        }
        return CatalogResult.Found(
            MediaCatalog(
                sourceId = "wikimedia-commons",
                sourceLabel = "Wikimedia Commons",
                basis = "Wikimedia Commons publishes this freely licensed file and its versions for download.",
                title = title,
                durationSeconds = duration,
                pageUrl = null,
                variants = variants,
                notices = notices,
                conversionAllowed = license != null,
            ),
        )
    }

    private fun describe(type: String?): Pair<VariantKind, String>? {
        val details = MediaFormats.describe(baseMime(type), null) ?: return null
        val kind = if (details.kind == MediaKind.Video) VariantKind.Video else VariantKind.Audio
        return kind to details.format
    }

    private fun baseMime(type: String?): String? = type?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun codecsOf(type: String?): List<String> {
        val raw = codecsPattern.find(type ?: "")?.groupValues?.get(1) ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun positive(value: Any?): Int? = (value as? Double)?.toInt()?.takeIf { it > 0 }

    /** Only https addresses on Wikimedia's own file host are accepted (protocol-relative ones are completed). */
    internal fun officialFileUrl(raw: String?): String? {
        val url = when {
            raw == null -> return null
            raw.startsWith("//") -> "https:$raw"
            else -> raw
        }
        if (!url.startsWith("https://")) return null
        return url.takeIf { UrlText.host(it) == "upload.wikimedia.org" }
    }

    private fun plain(html: String?): String? = html?.let { tags.replace(it, "").trim().takeIf { t -> t.isNotEmpty() } }
}
