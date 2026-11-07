package com.almus.ytdownloader.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions that matter for downloads. Nothing is asked at app start. */
object Permissions {
    /**
     * Permissions worth asking for right before a download starts:
     * - POST_NOTIFICATIONS on Android 13+ (for the finished/failed notification)
     * - WRITE_EXTERNAL_STORAGE on Android 9 and older (for the public Downloads folder)
     * Declining either never blocks the download.
     */
    fun missing(context: Context): List<String> = buildList {
        if (needsNotificationPermission(context)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}
