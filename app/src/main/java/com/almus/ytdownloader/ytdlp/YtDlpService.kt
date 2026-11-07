package com.almus.ytdownloader.ytdlp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.almus.ytdownloader.AlmusApplication
import com.almus.ytdownloader.MainActivity
import com.almus.ytdownloader.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the process, and so yt-dlp, alive while downloads run.
 * Started when a download is queued; it shows one progress notification and stops itself a few seconds
 * after the last download ends. Android 15 limits dataSync services in time; when that limit is reached
 * the service stops and downloads continue only while the process lives.
 */
class YtDlpService : Service() {
    private val container get() = (application as AlmusApplication).container
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) container.ytJobs.cancelAll()
        ensureChannel()
        val first = build(ActiveSummary(active = 1, percent = 0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }
        if (job == null) {
            job = container.applicationScope.launch {
                container.ytJobs.jobs
                    .map { summarize(it) }
                    .distinctUntilChanged()
                    .collectLatest { summary ->
                        if (summary.active > 0) {
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, build(summary))
                        } else {
                            // New work cancels this wait (collectLatest), so a quick second download keeps the service.
                            delay(IDLE_MS)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15: the time limit of a dataSync service was reached. Must stop quickly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.yt_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.yt_notif_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun build(summary: ActiveSummary): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelAll = PendingIntent.getService(
            this,
            1,
            Intent(this, YtDlpService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(resources.getQuantityString(R.plurals.yt_notif_title, summary.active, summary.active))
            .setContentText(getString(R.string.yt_notif_text))
            .setProgress(100, summary.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(0, getString(R.string.yt_notif_cancel_all), cancelAll)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yt_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_CANCEL_ALL = "com.almus.ytdownloader.ytdlp.CANCEL_ALL"
        private const val IDLE_MS = 3_000L

        /** Starts the service. If Android refuses right now, downloads still run while the app is alive. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, YtDlpService::class.java))
            } catch (_: IllegalStateException) {
                // Not allowed from the background at this moment.
            }
        }
    }
}
