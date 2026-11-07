package com.almus.ytdownloader.download

import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * Picks a safe file name for a download. Pure Kotlin (JVM testable).
 *
 * Order: Content-Disposition header, then the last path segment of the URL, then a fallback.
 * Whatever the source, the result never contains a path, control characters or characters
 * that are invalid on common file systems, so it cannot escape the download folder.
 */
object FileNameResolver {
    const val FALLBACK_BASE = "download"
    private const val MAX_BYTES = 200
    private const val MAX_EXTENSION_LENGTH = 10

    // filename*=UTF-8''percent%20encoded   (RFC 5987 / 6266)
    private val extendedRegex =
        Regex("""(?i)(?<![A-Za-z0-9_\-*])filename\*\s*=\s*([^'";\s]*)'[^'";\s]*'([^;\s]+)""")
    private val quotedRegex = Regex(
        "(?i)(?<![A-Za-z0-9_\\-*])filename\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
    )
    private val plainRegex =
        Regex("""(?i)(?<![A-Za-z0-9_\-*])filename\s*=\s*([^;"]+)""")

    // Characters that are removed outright.
    private val forbidden = setOf('<', '>', ':', '"', '|', '?', '*', '/', '\\')

    fun resolve(contentDisposition: String?, mimeType: String?, url: String?): String {
        val candidate = sanitize(fromContentDisposition(contentDisposition))
            ?: sanitize(fromUrl(url))
        val withName = candidate ?: FALLBACK_BASE
        return ensureExtension(withName, mimeType)
    }

    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        extendedRegex.find(header)?.let { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1].ifBlank { "UTF-8" }) }
                .getOrDefault(Charsets.UTF_8)
            val decoded = percentDecode(m.groupValues[2], charset)
            if (!decoded.isNullOrBlank()) return decoded
        }
        quotedRegex.find(header)?.let { m ->
            val value = m.groupValues[1].replace(Regex("""\\(.)"""), "$1")
            if (value.isNotBlank()) return value
        }
        plainRegex.find(header)?.let { m ->
            val value = m.groupValues[1].trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    fun fromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val noFragment = url.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val afterScheme = noQuery.substringAfter("://", noQuery)
        val path = afterScheme.substringAfter('/', "")
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return percentDecode(last, Charsets.UTF_8) ?: last
    }

    /**
     * Returns a safe name, or null when nothing usable is left.
     * Strips directories (so "../../x" becomes "x"), control and bidi-override characters,
     * characters invalid on file systems, leading/trailing dots and spaces, and limits the length.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        // Keep only what follows the last slash or backslash: kills path traversal.
        var name = raw.replace('\\', '/').substringAfterLast('/')
        name = buildString {
            for (ch in name) {
                if (ch.isISOControl()) continue
                if (ch in forbidden) continue
                if (isInvisibleFormatting(ch)) continue
                append(ch)
            }
        }
        name = name.replace(Regex("\\s+"), " ").trim()
        name = name.trim('.', ' ')
        if (name.isEmpty()) return null
        name = truncate(name)
        return name.ifEmpty { null }
    }

    /** Adds an extension from [mimeType] when the name has none and the type is known and specific. */
    fun ensureExtension(name: String, mimeType: String?): String {
        if (extensionOf(name) != null) return name
        if (MimeTypes.isGeneric(mimeType)) return name
        val ext = MimeTypes.extensionFor(mimeType) ?: return name
        return "$name.$ext"
    }

    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1)
        return if (ext.length <= MAX_EXTENSION_LENGTH && ext.all { it.isLetterOrDigit() }) ext else null
    }

    /**
     * Makes [name] unique among [taken] (compared case-insensitively):
     * "report.pdf" becomes "report (1).pdf", "report (2).pdf", ...
     */
    fun makeUnique(name: String, taken: Set<String>): String {
        val takenLower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in takenLower) return name
        val ext = extensionOf(name)
        val base = if (ext != null) name.substring(0, name.length - ext.length - 1) else name
        var i = 1
        while (true) {
            val candidate = if (ext != null) "$base ($i).$ext" else "$base ($i)"
            if (candidate.lowercase() !in takenLower) return candidate
            i++
        }
    }

    private fun truncate(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return name
        val ext = extensionOf(name)
        val suffix = if (ext != null) ".$ext" else ""
        var base = if (ext != null) name.substring(0, name.length - suffix.length) else name
        while (base.isNotEmpty() && (base + suffix).toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            base = base.dropLast(1)
        }
        base = base.trim('.', ' ')
        return if (base.isEmpty()) "" else base + suffix
    }

    private fun isInvisibleFormatting(ch: Char): Boolean = ch.code.let {
        it in 0x200B..0x200F || it in 0x202A..0x202E || it in 0x2066..0x2069 || it == 0xFEFF
    }

    private fun percentDecode(value: String, charset: Charset): String? = try {
        // URLDecoder turns '+' into a space; in header and path values '+' is literal.
        URLDecoder.decode(value.replace("+", "%2B"), charset.name())
    } catch (_: IllegalArgumentException) {
        null
    }
}
