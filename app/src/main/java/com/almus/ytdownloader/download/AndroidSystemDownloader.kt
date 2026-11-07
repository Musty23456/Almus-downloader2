package com.almus.ytdownloader.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** [SystemDownloader] backed by Android's DownloadManager (reliable background transfers). */
class AndroidSystemDownloader(context: Context) : SystemDownloader {
    private val appContext = context.applicationContext
    private val manager: DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun enqueue(request: EnqueueRequest): Long {
        val uri = Uri.parse(request.url)
        val dm = DownloadManager.Request(uri)
            .setTitle(request.fileName)
            .setDescription(uri.host ?: request.url)
            // Progress is shown by the system while running. Completion and failure are
            // announced by this app (DownloadNotifier), so the system one is hidden at the end.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .addRequestHeader("User-Agent", LinkInspector.USER_AGENT)
        request.mimeType?.let { dm.setMimeType(it) }

        // The sub-folder is one of the fixed names in SaveFolders, never text from the network.
        val relative = SaveFolders.relativePath(request.subfolder, request.fileName)
        when (request.target) {
            StorageTarget.PublicDownloads -> dm.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$PUBLIC_SUBFOLDER/$relative",
            )
            StorageTarget.AppPrivate -> dm.setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                relative,
            )
        }
        return manager.enqueue(dm)
    }

    override fun query(ids: List<Long>): Map<Long, SystemSnapshot> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, SystemSnapshot>()
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val mimeCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            while (c.moveToNext()) {
                result[c.getLong(idCol)] = SystemSnapshot(
                    status = c.getInt(statusCol),
                    reason = c.getInt(reasonCol),
                    downloadedBytes = c.getLong(doneCol),
                    totalBytes = c.getLong(totalCol),
                    localUri = c.getString(uriCol),
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        return result
    }

    /** DownloadManager.remove stops the download AND deletes its file (partial or complete). */
    override fun remove(id: Long): Int = manager.remove(id)

    override fun unfinishedIds(): Set<Long> {
        val ids = HashSet<Long>()
        val unfinished = DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PAUSED
        manager.query(DownloadManager.Query().setFilterByStatus(unfinished))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) ids += c.getLong(idCol)
        }
        return ids
    }

    /** content:// uri of a finished download for the "Open" button, or null. */
    fun contentUriFor(id: Long): Uri? = manager.getUriForDownloadedFile(id)

    companion object {
        const val PUBLIC_SUBFOLDER = "ALMUS"
    }
}
