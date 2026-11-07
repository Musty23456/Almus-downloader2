package com.almus.ytdownloader.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.almus.ytdownloader.AlmusApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the system's "download finished" broadcast, even when the app is closed, updates the
 * stored record and posts the notification. Ids that do not belong to this app's table are ignored.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (systemId < 0) return

        val container = (context.applicationContext as AlmusApplication).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                withTimeoutOrNull(RECEIVER_TIMEOUT_MS) {
                    container.repository.onSystemDownloadComplete(systemId)
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
