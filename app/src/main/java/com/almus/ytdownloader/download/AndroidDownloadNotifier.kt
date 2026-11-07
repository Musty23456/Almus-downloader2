package com.almus.ytdownloader.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.almus.ytdownloader.MainActivity
import com.almus.ytdownloader.R
import com.almus.ytdownloader.data.DownloadEntity

/**
 * Posts "download finished" and "download failed" notifications.
 * Tapping one opens the app's Downloads screen. It never opens or runs the file.
 */
class AndroidDownloadNotifier(context: Context) : DownloadNotifier {
    private val appContext = context.applicationContext

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = appContext.getString(R.string.notif_channel_description) }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission") // checked below
    override fun notifyFinished(entity: DownloadEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return

        val completed = entity.state == DownloadState.Completed.name
        val openApp = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            entity.id.toInt(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (completed) {
            appContext.getString(R.string.notif_completed_text)
        } else {
            entity.errorMessage ?: appContext.getString(R.string.notif_failed_text)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(
                if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(
                if (completed) {
                    appContext.getString(R.string.notif_completed_title, entity.fileName)
                } else {
                    appContext.getString(R.string.notif_failed_title, entity.fileName)
                },
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_TAG, entity.id.toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_TAG = "almus-download"
    }
}
