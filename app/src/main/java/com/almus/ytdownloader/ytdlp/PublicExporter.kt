package com.almus.ytdownloader.ytdlp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File

/**
 * yt-dlp writes into the app's own folder (no permission needed). A finished file is then copied into the public
 * Downloads folder (Android 10+), so it shows up in the Files app and the gallery. On Android 9 and older the
 * file stays in the app's folder.
 */
object PublicExporter {
    const val PUBLIC_SUBFOLDER = "Almus YouTube"

    /** Where yt-dlp works; each job gets its own sub-folder that is deleted afterwards. */
    fun workRoot(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "yt")
    }

    /** Returns the content address of the copy, or null when it stays in the app's folder. */
    fun export(context: Context, file: File): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportScoped(context, file) else null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportScoped(context: Context, file: File): Uri? {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("cannot open output")
            out.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
