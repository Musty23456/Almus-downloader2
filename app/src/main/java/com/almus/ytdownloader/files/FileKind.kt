package com.almus.ytdownloader.files

import com.almus.ytdownloader.download.FileNameResolver
import com.almus.ytdownloader.download.MimeTypes

/** File groups of the file manager. Pure Kotlin so classification can be unit tested. */
enum class FileKind {
    Videos,
    Music,
    Images,
    Documents,
    Apks,
    Archives,
    Others,
    ;

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"

        private val archiveExtensions = setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz")
        private val archiveMimes = setOf(
            "application/zip",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/x-bzip2",
            "application/x-xz",
        )
        private val documentMimes = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/epub+zip",
            "application/json",
            "application/xml",
        )

        /**
         * Groups a file. The extension is trusted first (servers often send "application/zip" for a
         * .docx), then a specific MIME type. APKs are recognised by extension or type before anything else.
         */
        fun classify(fileName: String, mimeType: String?): FileKind {
            val extension = FileNameResolver.extensionOf(fileName)?.lowercase()
            if (extension == "apk") return Apks
            val mime = MimeTypes.forExtension(extension)
                ?: MimeTypes.normalize(mimeType)?.takeUnless { MimeTypes.isGeneric(it) }
            if (mime == null) {
                return if (extension != null && extension in archiveExtensions) Archives else Others
            }
            return when {
                mime == APK_MIME -> Apks
                mime.startsWith("video/") -> Videos
                mime.startsWith("audio/") -> Music
                mime.startsWith("image/") -> Images
                mime.startsWith("text/") -> Documents
                mime in documentMimes || mime.startsWith("application/vnd.openxmlformats-officedocument") -> Documents
                mime in archiveMimes || (extension != null && extension in archiveExtensions) -> Archives
                else -> Others
            }
        }
    }
}
