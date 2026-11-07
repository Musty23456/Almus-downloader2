package com.almus.ytdownloader.engine.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.almus.ytdownloader.engine.FinalStorage
import com.almus.ytdownloader.engine.PublishedFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Partial files live in the app's own storage (no permission needed). A complete file is copied into
 * Download/ALMUS (or Download/ALMUS/Video|Audio) through MediaStore, which needs no storage permission
 * on Android 10+. The file is marked "pending" while it is being written, so other apps never see half a file.
 * Android 10 (API 29) or newer only; older versions keep using the DownloadManager-based engine.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AndroidFinalStorage(context: Context) : FinalStorage {
    private val appContext = context.applicationContext

    override fun partDir(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, "parts").apply { mkdirs() }
    }

    override fun freeBytes(): Long = try {
        StatFs(partDir().path).availableBytes
    } catch (_: Exception) {
        -1L
    }

    override suspend fun publish(part: File, fileName: String, mimeType: String?, subfolder: String?): PublishedFile =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val folder = if (subfolder == null) "$ROOT" else "$ROOT/$subfolder"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("The Downloads folder is not available.")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("The file could not be opened for writing.")
                out.use { sink -> part.inputStream().use { source -> source.copyTo(sink) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw if (e is IOException) e else IOException(e.message, e)
            }
            part.delete()
            PublishedFile(uri.toString())
        }

    override suspend fun deletePublished(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.delete(Uri.parse(uri), null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val ROOT = "Download/ALMUS"
    }
}
