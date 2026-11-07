package com.almus.ytdownloader.download

/**
 * Small built-in table of common file types. Pure Kotlin, so it also works in JVM unit tests
 * (android.webkit.MimeTypeMap does not).
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"

    private val byExtension: Map<String, String> = mapOf(
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "json" to "application/json",
        "xml" to "application/xml",
        "epub" to "application/epub+zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        // More audio and video formats. Added after the entries above so the first extension
        // listed for an existing type still wins when a type is turned back into an extension.
        "m4v" to "video/x-m4v",
        "ogv" to "video/ogg",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "opus" to "audio/opus",
        "oga" to "audio/ogg",
        "weba" to "audio/webm",
        "mka" to "audio/x-matroska",
        "wma" to "audio/x-ms-wma",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "amr" to "audio/amr",
    )

    // First extension listed for a type wins (jpg before jpeg, html before htm).
    private val byMime: Map<String, String> = LinkedHashMap<String, String>().also { map ->
        byExtension.forEach { (ext, mime) -> if (!map.containsKey(mime)) map[mime] = ext }
    }

    /** Removes parameters such as "; charset=utf-8" and lower-cases the type. Null when blank. */
    fun normalize(raw: String?): String? {
        val base = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return base.ifEmpty { null }
    }

    fun forExtension(extension: String?): String? =
        extension?.trim()?.trimStart('.')?.lowercase()?.let { byExtension[it] }

    fun extensionFor(mime: String?): String? = normalize(mime)?.let { byMime[it] }

    fun isGeneric(mime: String?): Boolean {
        val m = normalize(mime) ?: return true
        return m == OCTET_STREAM || m == "binary/octet-stream" || m == "application/force-download"
    }
}
