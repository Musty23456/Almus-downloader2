package com.almus.ytdownloader.media.sources

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
 * Internet Archive items (`archive.org/details/ID`). The Archive publishes the files of public items for download
 * itself, and its public metadata service lists every file with its format, size, length and frame size. Items that
 * are restricted (borrowing and lending, dark) are refused, and so are files marked private. Items made of several
 * separate originals (albums, collections) are explained instead of listing hundreds of files.
 */
class InternetArchiveSource(private val http: MediaHttp) : MediaSource {
    override val id: String = "internet-archive"

    override fun match(url: String): String? {
        val host = UrlText.host(url)
        if (host != "archive.org" && host != "www.archive.org") return null
        val segments = UrlText.segments(url)
        if (segments.size < 2 || segments[0] != "details") return null
        return segments[1].takeIf { ArchiveParser.isValidIdentifier(it) }
    }

    override suspend fun load(key: String): CatalogResult {
        if (!ArchiveParser.isValidIdentifier(key)) return CatalogResult.Unavailable("This is not a valid Internet Archive item address.")
        val reply = try {
            http.get("https://archive.org/metadata/" + URLEncoder.encode(key, "UTF-8"))
        } catch (_: MediaNetworkException) {
            return CatalogResult.Failed("Could not reach the Internet Archive. Check your connection and try again.")
        }
        return when {
            reply.status == 200 -> ArchiveParser.parse(key, reply.body)
            reply.status == 404 -> CatalogResult.Unavailable("The Internet Archive has no item with this address.")
            reply.status == 429 -> CatalogResult.Failed("The Internet Archive is limiting requests right now. Try again later.")
            else -> CatalogResult.Failed("The Internet Archive is not answering right now (status ${reply.status}). Try again later.")
        }
    }
}

internal object ArchiveParser {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    private const val MAX_SECONDS = 359_999
    private const val MAX_BYTES = 1L shl 40

    fun isValidIdentifier(value: String): Boolean = identifier.matches(value)

    private data class Fmt(val kind: VariantKind, val container: String, val mime: String)

    private fun classify(format: String?, name: String): Fmt? {
        val f = (format ?: "").lowercase()
        return when {
            "mp3" in f -> Fmt(VariantKind.Audio, "MP3", "audio/mpeg")
            "flac" in f -> Fmt(VariantKind.Audio, "FLAC", "audio/flac")
            "vorbis" in f -> Fmt(VariantKind.Audio, "Ogg Vorbis", "audio/ogg")
            f == "opus" -> Fmt(VariantKind.Audio, "Opus", "audio/ogg")
            "apple lossless" in f -> Fmt(VariantKind.Audio, "M4A (Apple Lossless)", "audio/mp4")
            "advanced audio coding" in f || f == "m4a" -> Fmt(VariantKind.Audio, "M4A", "audio/mp4")
            f == "wave" || f == "wav" -> Fmt(VariantKind.Audio, "WAV", "audio/wav")
            "ogg video" in f -> Fmt(VariantKind.Video, "Ogg", "video/ogg")
            "matroska" in f -> Fmt(VariantKind.Video, "Matroska", "video/x-matroska")
            "webm" in f -> Fmt(VariantKind.Video, "WebM", "video/webm")
            "mpeg4" in f || "h.264" in f -> Fmt(VariantKind.Video, "MP4", "video/mp4")
            "mpeg2" in f -> Fmt(VariantKind.Video, "MPEG", "video/mpeg")
            "quicktime" in f -> Fmt(VariantKind.Video, "MOV", "video/quicktime")
            else -> null
        }.takeIf { name.isNotBlank() }
    }

    fun parse(identifier: String, body: String): CatalogResult {
        val root = MiniJson.parseObject(body) ?: return CatalogResult.Failed("The Internet Archive gave an answer that could not be read.")
        val metadata = root["metadata"] as? Map<*, *>
        if (metadata == null || metadata.isEmpty()) return CatalogResult.Unavailable("The Internet Archive has no readable item with this address.")
        if (root["is_dark"] == true || root["is_dark"] == "true") {
            return CatalogResult.Unavailable("This item is not available (the Internet Archive has taken it offline).")
        }
        if (text(metadata["access-restricted-item"]).equals("true", ignoreCase = true)) {
            return CatalogResult.Unavailable("This item is restricted (for example borrowing only). It cannot be downloaded here.")
        }
        val mediatype = text(metadata["mediatype"])?.lowercase()
        if (mediatype != "movies" && mediatype != "audio") {
            return CatalogResult.Unavailable("This item is not audio or video, so there is nothing to choose here.")
        }

        val files = root["files"] as? List<*> ?: return CatalogResult.Unavailable("This item lists no files.")
        val variants = mutableListOf<MediaVariant>()
        val lengths = mutableListOf<Int>()
        var originals = 0
        for (entry in files) {
            val file = entry as? Map<*, *> ?: continue
            val name = text(file["name"]) ?: continue
            if (text(file["private"]).equals("true", ignoreCase = true)) continue
            val fmt = classify(text(file["format"]), name) ?: continue
            val original = text(file["source"]).equals("original", ignoreCase = true)
            if (original) originals++
            val length = seconds(file["length"])
            if (length != null) lengths += length
            variants += MediaVariant(
                id = "ia:$name",
                kind = fmt.kind,
                container = fmt.container,
                mimeType = fmt.mime,
                url = "https://archive.org/download/" + encodeIdentifier(identifier) + "/" + encodePath(name),
                widthPx = if (fmt.kind == VariantKind.Video) number(file["width"]) else null,
                heightPx = if (fmt.kind == VariantKind.Video) number(file["height"]) else null,
                bitrateKbps = number(file["bitrate"]),
                sizeBytes = longNumber(file["size"])?.takeIf { it in 1..MAX_BYTES },
                isOriginal = original,
            )
        }
        if (originals > 1) {
            return CatalogResult.Unavailable(
                "This item holds $originals separate media files (for example an album or a collection). Open the item, " +
                    "copy the address of the one file you want (it looks like archive.org/download/$identifier/FILE) and paste that address here.",
            )
        }
        if (variants.isEmpty()) return CatalogResult.Unavailable("This item has no audio or video files that can be downloaded.")

        val license = text(metadata["licenseurl"])
        val notices = buildList {
            add("Offered for download by archive.org. Check the item's page for its terms of use.")
            if (license != null) add("License: $license")
        }
        val openLicense = license != null && ("creativecommons.org" in license || "publicdomain" in license)
        return CatalogResult.Found(
            MediaCatalog(
                sourceId = "internet-archive",
                sourceLabel = "Internet Archive",
                basis = "The Internet Archive publishes the files of this public item for download.",
                title = text(metadata["title"]),
                durationSeconds = lengths.maxOrNull(),
                pageUrl = "https://archive.org/details/" + encodeIdentifier(identifier),
                variants = variants,
                notices = notices,
                conversionAllowed = openLicense,
            ),
        )
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is List<*> -> value.firstOrNull { it is String && it.isNotBlank() }?.let { (it as String).trim() }
        is Double -> value.toLong().toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun number(value: Any?): Int? = when (value) {
        is Double -> value.toInt().takeIf { it > 0 }
        is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    private fun longNumber(value: Any?): Long? = when (value) {
        is Double -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    /** A length given as seconds ("596.57") or as clock text ("9:56", "1:02:03"). */
    internal fun seconds(value: Any?): Int? {
        val raw = when (value) {
            is Double -> value.toString()
            is String -> value.trim()
            else -> return null
        }
        if (raw.contains(':')) {
            val parts = raw.split(':').map { it.toDoubleOrNull() ?: return null }
            if (parts.size !in 2..3) return null
            val total = parts.fold(0.0) { acc, p -> acc * 60 + p }
            return total.toInt().takeIf { it in 1..MAX_SECONDS }
        }
        return raw.toDoubleOrNull()?.toInt()?.takeIf { it in 1..MAX_SECONDS }
    }

    private fun encodeIdentifier(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun encodePath(name: String) = name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
