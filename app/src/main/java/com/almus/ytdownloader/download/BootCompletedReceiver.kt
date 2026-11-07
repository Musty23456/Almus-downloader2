package com.almus.ytdownloader.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.almus.ytdownloader.AlmusApplication
import com.almus.ytdownloader.engine.android.ResumeScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After a restart, Android resumes its own downloads. This receiver reconciles the app's records
 * with the system and starts queued downloads that were still waiting. It only runs short work and
 * never starts a foreground service. (Android sends this broadcast only once the app has been
 * opened at least once since install, and not after the user force-stops the app.)
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as AlmusApplication).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                withTimeoutOrNull(RECEIVER_TIMEOUT_MS) {
                    container.repository.reconcile()
                    // New engine: do not start work from here (Android 15 forbids starting a dataSync
                    // service at boot). Ask WorkManager to continue the downloads once a network exists.
                    val host = container.engineHost
                    if (host.supported && host.hasUnfinishedWork()) ResumeScheduler.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RECEIVER_TIMEOUT_MS = 8_000L
    }
}
