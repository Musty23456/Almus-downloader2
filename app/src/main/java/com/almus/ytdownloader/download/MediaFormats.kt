package com.almus.ytdownloader.download

enum class MediaKind { Video, Audio }

/** [format] is a short readable name such as "MP4" or "MP3". It is shown to the user. */
data class MediaDetails(val kind: MediaKind, val format: String)

/**
 * Tells whether a server response describes an audio or video FILE, and which format.
 * Pure Kotlin, unit tested. It only reads the declared MIME type and the file name; it never
 * opens, converts or re-packs the file.
 */
object MediaFormats {
    private data class Known(val kind: MediaKind, val format: String)

    private val byMime: Map<String, Known> = mapOf(
        "video/mp4" to Known(MediaKind.Video, "MP4"),
        "video/webm" to Known(MediaKind.Video, "WebM"),
        "video/x-matroska" to Known(MediaKind.Video, "Matroska"),
        "video/quicktime" to Known(MediaKind.Video, "QuickTime"),
        "video/x-msvideo" to Known(MediaKind.Video, "AVI"),
        "video/3gpp" to Known(MediaKind.Video, "3GP"),
        "video/ogg" to Known(MediaKind.Video, "Ogg"),
        "video/x-m4v" to Known(MediaKind.Video, "M4V"),
        "video/mpeg" to Known(MediaKind.Video, "MPEG"),
        "video/x-flv" to Known(MediaKind.Video, "FLV"),
        "video/x-ms-wmv" to Known(MediaKind.Video, "WMV"),
        "audio/mpeg" to Known(MediaKind.Audio, "MP3"),
        "audio/mp3" to Known(MediaKind.Audio, "MP3"),
        "audio/mp4" to Known(MediaKind.Audio, "M4A"),
        "audio/x-m4a" to Known(MediaKind.Audio, "M4A"),
        "audio/m4a" to Known(MediaKind.Audio, "M4A"),
        "audio/aac" to Known(MediaKind.Audio, "AAC"),
        "audio/ogg" to Known(MediaKind.Audio, "Ogg"),
        "audio/opus" to Known(MediaKind.Audio, "Opus"),
        "audio/flac" to Known(MediaKind.Audio, "FLAC"),
        "audio/x-flac" to Known(MediaKind.Audio, "FLAC"),
        "audio/wav" to Known(MediaKind.Audio, "WAV"),
        "audio/x-wav" to Known(MediaKind.Audio, "WAV"),
        "audio/wave" to Known(MediaKind.Audio, "WAV"),
        "audio/vnd.wave" to Known(MediaKind.Audio, "WAV"),
        "audio/webm" to Known(MediaKind.Audio, "WebM"),
        "audio/x-matroska" to Known(MediaKind.Audio, "Matroska"),
        "audio/x-ms-wma" to Known(MediaKind.Audio, "WMA"),
        "audio/aiff" to Known(MediaKind.Audio, "AIFF"),
        "audio/x-aiff" to Known(MediaKind.Audio, "AIFF"),
        "audio/amr" to Known(MediaKind.Audio, "AMR"),
    )

    private const val MAX_FORMAT_LENGTH = 24

    /**
     * Audio or video details, or null when this is not media.
     * A specific MIME type from the server is trusted (a server that says "text/html" for "a.mp4"
     * is sending a web page). When the type is missing or generic (octet-stream), the file extension decides.
     */
    fun describe(mimeType: String?, fileName: String?): MediaDetails? {
        val declared = MimeTypes.normalize(mimeType)?.takeUnless { MimeTypes.isGeneric(it) }
        val extension = fileName?.let { FileNameResolver.extensionOf(it) }
        val mime = declared ?: MimeTypes.forExtension(extension) ?: return null
        return fromMime(mime, extension)
    }

    private fun fromMime(mime: String, extension: String?): MediaDetails? {
        byMime[mime]?.let { return MediaDetails(it.kind, it.format) }
        if (mime == "application/ogg") {
            val video = extension?.lowercase() == "ogv"
            return MediaDetails(if (video) MediaKind.Video else MediaKind.Audio, "Ogg")
        }
        val kind = when {
            mime.startsWith("video/") -> MediaKind.Video
            mime.startsWith("audio/") -> MediaKind.Audio
            else -> return null
        }
        // The subtype comes from the server, so keep only harmless characters and a short length.
        val subtype = mime.substringAfter('/').removePrefix("x-")
            .filter { it.isLetterOrDigit() || it == '+' || it == '.' || it == '-' }
            .take(MAX_FORMAT_LENGTH)
            .uppercase()
        return if (subtype.isEmpty()) null else MediaDetails(kind, subtype)
    }
}
