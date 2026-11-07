package com.almus.ytdownloader.engine.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.almus.ytdownloader.AlmusApplication

/**
 * Continues unfinished downloads when the foreground service could not (after a restart, after the system
 * ended the service, or when Android did not allow starting it). It runs as expedited work with a
 * foreground notification, only with a network connection, and ends when nothing is left to do.
 */
class ResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val host get() = (applicationContext as AlmusApplication).container.engineHost

    override suspend fun doWork(): Result {
        if (!host.supported) return Result.success()
        host.ensureStarted()
        host.engine.suspendWork(false)
        host.engine.awaitIdle()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = host.notifications.foregroundInfo()
}

object ResumeScheduler {
    private const val UNIQUE_NAME = "almus-resume-downloads"

    /** Asks WorkManager to continue the downloads. Does nothing if such work is already waiting or running. */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<ResumeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
