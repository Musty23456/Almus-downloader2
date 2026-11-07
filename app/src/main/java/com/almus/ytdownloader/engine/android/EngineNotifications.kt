package com.almus.ytdownloader.engine.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.almus.ytdownloader.MainActivity
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.AndroidDownloadNotifier
import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.download.DurationFormat
import com.almus.ytdownloader.engine.EngineDownload
import com.almus.ytdownloader.engine.EngineItem
import com.almus.ytdownloader.engine.EngineState

/**
 * Notifications of the new engine: one ongoing progress notification per download (with Pause / Resume /
 * Cancel buttons), a summary notification for the foreground service, and "finished" / "failed" notifications.
 * Buttons only send explicit, non-exported broadcasts to [EngineActionReceiver].
 */
class EngineNotifications(context: Context) {
    private val app = context.applicationContext
    private val shown = HashSet<Long>()
    private var lastSignature: List<Pair<Long, EngineState>> = emptyList()
    private var lastRenderAt = 0L

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_PROGRESS,
            app.getString(R.string.engine_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = app.getString(R.string.engine_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** The notification the foreground service (or the resume worker) shows. */
    fun summary(items: List<EngineItem>): Notification {
        ensureChannel()
        val active = items.filter { it.download.state.isActive }
        val speed = active.sumOf { it.speed?.bytesPerSecond ?: 0L }
        val text = when {
            active.isEmpty() -> app.getString(R.string.engine_notif_summary_idle)
            speed > 0 -> app.resources.getQuantityString(R.plurals.engine_notif_summary_active, active.size, active.size) +
                " · " + ByteFormat.speed(speed)
            else -> app.resources.getQuantityString(R.plurals.engine_notif_summary_active, active.size, active.size)
        }
        return NotificationCompat.Builder(app, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(app.getString(R.string.engine_notif_summary_title))
            .setContentText(text)
            .setContentIntent(openDownloads(0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission") // checked in canNotify()
    fun updateSummary(items: List<EngineItem>) {
        if (!canNotify()) return
        NotificationManagerCompat.from(app).notify(SUMMARY_ID, summary(items))
    }

    fun foregroundInfo(): ForegroundInfo {
        val notification = summary(emptyList())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SUMMARY_ID, notification)
        }
    }

    /** Posts, updates or removes the per-download notifications. Updates at most about once a second. */
    @SuppressLint("MissingPermission") // checked in canNotify()
    fun render(items: List<EngineItem>) {
        val now = System.currentTimeMillis()
        val signature = items.map { it.download.id to it.download.state }
        if (signature == lastSignature && now - lastRenderAt < MIN_INTERVAL_MS) return
        lastSignature = signature
        lastRenderAt = now

        val manager = NotificationManagerCompat.from(app)
        val wanted = HashSet<Long>()
        for (item in items) {
            val d = item.download
            if (d.state.isTerminal) continue
            if (!canNotify()) continue
            wanted += d.id
            manager.notify(TAG_PROGRESS, notificationId(d.id), progressNotification(item))
        }
        (shown - wanted).forEach { manager.cancel(TAG_PROGRESS, notificationId(it)) }
        shown.clear()
        shown.addAll(wanted)
    }

    @SuppressLint("MissingPermission") // checked in canNotify()
    fun notifyFinished(download: EngineDownload) {
        val manager = NotificationManagerCompat.from(app)
        manager.cancel(TAG_PROGRESS, notificationId(download.id))
        if (!canNotify()) return
        val completed = download.state == EngineState.Completed
        val text = if (completed) {
            app.getString(R.string.notif_completed_text)
        } else {
            download.errorMessage ?: app.getString(R.string.notif_failed_text)
        }
        val notification = NotificationCompat.Builder(app, AndroidDownloadNotifier.CHANNEL_ID)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(
                app.getString(if (completed) R.string.notif_completed_title else R.string.notif_failed_title, download.fileName),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDownloads(notificationId(download.id)))
            .setAutoCancel(true)
            .build()
        manager.notify(TAG_DONE, notificationId(download.id), notification)
    }

    private fun progressNotification(item: EngineItem): Notification {
        val d = item.download
        val known = d.totalBytes > 0
        val percent = if (known) ((d.downloadedBytes * 100) / d.totalBytes).toInt().coerceIn(0, 100) else 0
        val live = d.state == EngineState.Connecting || d.state == EngineState.Running
        val text = when (d.state) {
            EngineState.Queued -> app.getString(R.string.engine_notif_queued)
            EngineState.Paused -> app.getString(R.string.engine_notif_paused) + if (known) " · $percent%" else ""
            EngineState.WaitingForNetwork, EngineState.WaitingToRetry -> d.errorMessage ?: app.getString(R.string.engine_notif_queued)
            EngineState.Verifying -> app.getString(R.string.engine_notif_finishing)
            else -> buildList {
                add(if (known) "$percent%" else ByteFormat.format(d.downloadedBytes))
                item.speed?.bytesPerSecond?.let { add(ByteFormat.speed(it)) }
                item.speed?.etaSeconds?.let { add(DurationFormat.remaining(it)) }
            }.joinToString(" · ")
        }
        val builder = NotificationCompat.Builder(app, CHANNEL_PROGRESS)
            .setSmallIcon(if (d.state == EngineState.Paused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setContentTitle(d.fileName)
            .setContentText(text)
            .setContentIntent(openDownloads(notificationId(d.id)))
            .setOngoing(d.state != EngineState.Paused)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (live || d.state == EngineState.Verifying) builder.setProgress(100, percent, !known)
        if (d.state != EngineState.Verifying) {
            if (d.state == EngineState.Paused) {
                builder.addAction(0, app.getString(R.string.engine_notif_resume), action(ACTION_RESUME, d.id))
            } else {
                builder.addAction(0, app.getString(R.string.engine_notif_pause), action(ACTION_PAUSE, d.id))
            }
            builder.addAction(0, app.getString(R.string.engine_notif_cancel), action(ACTION_CANCEL, d.id))
        }
        return builder.build()
    }

    private fun action(name: String, id: Long): PendingIntent {
        val intent = Intent(app, EngineActionReceiver::class.java)
            .setAction(name)
            .setData(Uri.parse("almus-engine://$name/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openDownloads(requestCode: Int): PendingIntent {
        val open = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(app, requestCode, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun notificationId(downloadId: Long): Int = (downloadId % 1_000_000).toInt() + ID_BASE

    companion object {
        const val CHANNEL_PROGRESS = "engine_progress"
        const val SUMMARY_ID = 7001
        const val ACTION_PAUSE = "com.almus.ytdownloader.engine.PAUSE"
        const val ACTION_RESUME = "com.almus.ytdownloader.engine.RESUME"
        const val ACTION_CANCEL = "com.almus.ytdownloader.engine.CANCEL"
        const val EXTRA_ID = "download_id"
        private const val TAG_PROGRESS = "almus-engine"
        private const val TAG_DONE = "almus-engine-done"
        private const val ID_BASE = 10_000
        private const val MIN_INTERVAL_MS = 1_000L
    }
}
