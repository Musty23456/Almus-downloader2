package com.almus.ytdownloader.engine.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.almus.ytdownloader.AlmusApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the process alive while the new engine works.
 * It is only started from something the user does (the app on screen, or a notification button).
 * It stops itself a few seconds after nothing is busy. When Android ends a dataSync service after its
 * time limit (Android 15), the engine puts running downloads back in the queue and a work request
 * continues them ([ResumeScheduler]).
 */
class DownloadService : Service() {
    private val host get() = (application as AlmusApplication).container.engineHost
    private var job: Job? = null

    @Volatile
    private var lastBusyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = host.notifications.summary(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(EngineNotifications.SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(EngineNotifications.SUMMARY_ID, notification)
        }
        if (job == null) {
            lastBusyAt = System.currentTimeMillis()
            job = host.scope.launch {
                host.ensureStarted()
                host.engine.suspendWork(false)
                launch {
                    while (true) {
                        delay(CHECK_MS)
                        if (System.currentTimeMillis() - lastBusyAt > IDLE_MS) {
                            stopSelf()
                            break
                        }
                    }
                }
                host.engine.items.collect { items ->
                    if (items.any { it.download.state.isBusy }) lastBusyAt = System.currentTimeMillis()
                    host.notifications.updateSummary(items)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15: the time limit of a dataSync service was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        host.scope.launch { host.engine.suspendWork(true) }
        ResumeScheduler.schedule(applicationContext)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        val h = host
        if (h.supported) {
            h.scope.launch { if (h.hasUnfinishedWork()) ResumeScheduler.schedule(applicationContext) }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHECK_MS = 1_000L
        private const val IDLE_MS = 4_000L

        /** Starts the service. Falls back to a work request when Android does not allow it right now. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
            } catch (_: IllegalStateException) {
                ResumeScheduler.schedule(app)
            }
        }
    }
}
