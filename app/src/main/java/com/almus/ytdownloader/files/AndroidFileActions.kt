package com.almus.ytdownloader.files

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.almus.ytdownloader.download.AndroidSystemDownloader
import com.almus.ytdownloader.download.MimeTypes

/**
 * Builds the intents for opening and sharing. Files are only ever handed to other apps as
 * content:// uris with a temporary read grant (never file://), and nothing is started here:
 * the caller starts the intent after a user tap.
 */
class AndroidFileActions(
    context: Context,
    private val systemDownloader: AndroidSystemDownloader,
) {
    private val resolver = context.applicationContext.contentResolver

    fun uriFor(source: FileSource): Uri? = when (source) {
        is FileSource.Download -> source.systemId?.let { systemDownloader.contentUriFor(it) }
        is FileSource.Folder -> Uri.parse(source.documentUri)
    }

    fun openIntent(entry: FileEntry): Intent? {
        val uri = uriFor(entry.source) ?: return null
        val type = specificMime(entry) ?: runCatching { resolver.getType(uri) }.getOrNull() ?: "*/*"
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(entry: FileEntry, chooserTitle: String): Intent? {
        val uri = uriFor(entry.source) ?: return null
        val type = specificMime(entry) ?: runCatching { resolver.getType(uri) }.getOrNull() ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(entry.name, uri)
        return Intent.createChooser(send, chooserTitle)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Copies the file's bytes into [destination], a document the user just created with the system
     * "save as" picker. The original stays where it is. Never throws.
     */
    fun copyTo(entry: FileEntry, destination: Uri): CopyResult {
        val source = uriFor(entry.source) ?: return CopyResult.Failed("The file could not be reached.", 0)
        return try {
            val input = resolver.openInputStream(source)
                ?: return CopyResult.Failed("The file could not be opened.", 0)
            input.use { reader ->
                val output = resolver.openOutputStream(destination, "w")
                    ?: return CopyResult.Failed("The chosen place cannot be written to.", 0)
                output.use { writer -> FileCopier.copy(reader, writer, entry.sizeBytes) }
            }
        } catch (e: java.io.IOException) {
            CopyResult.Failed(e.message ?: "The copy failed.", 0)
        } catch (e: SecurityException) {
            CopyResult.Failed("Android did not allow writing to the chosen place.", 0)
        }
    }

    /** Removes an empty or partial document created for a copy that did not happen. Best effort. */
    fun discard(destination: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, destination) }
    }

    /** The system's own Downloads screen. Used for APKs, which this app never opens or installs. */
    fun systemDownloadsIntent(): Intent =
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun specificMime(entry: FileEntry): String? =
        entry.mimeType?.takeUnless { MimeTypes.isGeneric(it) }
}
