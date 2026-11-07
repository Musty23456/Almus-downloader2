package com.almus.ytdownloader.files

import android.content.Context
import android.net.Uri
import com.almus.ytdownloader.data.DownloadEntity
import com.almus.ytdownloader.download.AndroidSystemDownloader
import java.io.File
import java.io.FileNotFoundException

/**
 * Checks the real file behind a download record, without any storage permission:
 * the file is reached through the content:// uri that DownloadManager hands out for the
 * download (scoped storage friendly). A plain path is only a fallback.
 */
class AndroidFileProbe(
    context: Context,
    private val systemDownloader: AndroidSystemDownloader,
) : FileProbe {
    private val resolver = context.applicationContext.contentResolver

    override fun probe(entity: DownloadEntity): ProbeResult {
        val localFile = localFileOf(entity)
        val uri = entity.systemId?.let { id -> runCatching { systemDownloader.contentUriFor(id) }.getOrNull() }
        if (uri != null) {
            try {
                val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                if (size != null) {
                    val mime = runCatching { resolver.getType(uri) }.getOrNull()
                    val modified = runCatching { localFile?.lastModified() ?: 0L }.getOrDefault(0L)
                    return ProbeResult.Available(size, modified, mime)
                }
            } catch (e: FileNotFoundException) {
                return ProbeResult.Missing
            } catch (e: SecurityException) {
                return ProbeResult.NoAccess
            } catch (e: Exception) {
                // fall through to the path check
            }
        }
        if (localFile != null) {
            return try {
                if (localFile.isFile) {
                    ProbeResult.Available(localFile.length(), localFile.lastModified(), null)
                } else {
                    ProbeResult.Missing
                }
            } catch (e: SecurityException) {
                ProbeResult.NoAccess
            }
        }
        return ProbeResult.Missing
    }

    private fun localFileOf(entity: DownloadEntity): File? {
        val raw = entity.localUri ?: return null
        val path = runCatching { Uri.parse(raw).path }.getOrNull() ?: return null
        return File(path)
    }
}
