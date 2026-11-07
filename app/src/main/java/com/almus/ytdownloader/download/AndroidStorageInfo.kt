package com.almus.ytdownloader.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Scoped storage rules:
 * - Android 10+: DownloadManager writes to Download/ALMUS without any storage permission.
 * - Android 9 and older: the public folder needs WRITE_EXTERNAL_STORAGE; without it files go
 *   to the app's own folder, so a download never fails just because the permission is missing.
 */
class AndroidStorageInfo(context: Context) : StorageInfo {
    private val appContext = context.applicationContext

    override fun target(): StorageTarget = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> StorageTarget.PublicDownloads
        hasLegacyWritePermission(appContext) -> StorageTarget.PublicDownloads
        else -> StorageTarget.AppPrivate
    }

    @Suppress("DEPRECATION")
    override fun freeBytes(target: StorageTarget): Long = try {
        val dir = when (target) {
            StorageTarget.PublicDownloads -> Environment.getExternalStorageDirectory()
            StorageTarget.AppPrivate -> appContext.getExternalFilesDir(null) ?: appContext.filesDir
        }
        StatFs(dir.path).availableBytes
    } catch (_: Exception) {
        -1L
    }

    @Suppress("DEPRECATION")
    override fun fileExists(target: StorageTarget, fileName: String, subfolder: String?): Boolean = try {
        val relative = SaveFolders.relativePath(subfolder, fileName)
        val file = when (target) {
            StorageTarget.PublicDownloads -> File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    AndroidSystemDownloader.PUBLIC_SUBFOLDER,
                ),
                relative,
            )
            StorageTarget.AppPrivate -> File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                relative,
            )
        }
        file.exists()
    } catch (_: Exception) {
        false
    }

    companion object {
        fun hasLegacyWritePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
    }
}
