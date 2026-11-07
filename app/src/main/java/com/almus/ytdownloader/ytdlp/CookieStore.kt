package com.almus.ytdownloader.ytdlp

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the optional cookies file inside the app's private storage (not backed up, never logged, never shown).
 * Only YouTube and Google cookies are kept. [clear] deletes it.
 */
class CookieStore(context: Context) {
    private val resolver = context.contentResolver
    private val file = File(context.filesDir, "yt-cookies.txt")

    private val _loaded = MutableStateFlow(file.exists())
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The path handed to yt-dlp, or null when no cookies are loaded. */
    fun path(): String? = if (file.exists()) file.absolutePath else null

    /** Returns how many cookies were kept. */
    fun importFrom(uri: Uri): Result<Int> = runCatching {
        val input = resolver.openInputStream(uri) ?: error("Could not read the file")
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= CookieFile.MAX_BYTES) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.size > CookieFile.MAX_BYTES) error("The file is too large for a cookies file")
        val filtered = CookieFile.filterForYouTube(String(bytes, Charsets.UTF_8))
            ?: error("No YouTube or Google cookies found. Export cookies.txt (Netscape format) while signed in to YouTube")
        file.writeText(filtered.text)
        _loaded.value = true
        filtered.kept
    }

    fun clear() {
        file.delete()
        _loaded.value = false
    }
}
