package com.almus.ytdownloader.engine.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.almus.ytdownloader.AlmusApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles the Pause / Resume / Cancel buttons of the progress notifications.
 * Not exported: only the app's own notification buttons can reach it. Unknown actions and bad ids are ignored.
 */
class EngineActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EngineNotifications.EXTRA_ID, -1L)
        if (id <= 0) return
        val action = intent.action ?: return
        if (action != EngineNotifications.ACTION_PAUSE && action != EngineNotifications.ACTION_RESUME &&
            action != EngineNotifications.ACTION_CANCEL
        ) {
            return
        }
        val host = (context.applicationContext as AlmusApplication).container.engineHost
        if (!host.supported) return
        val pending = goAsync()
        host.scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    when (action) {
                        EngineNotifications.ACTION_PAUSE -> host.pause(id)
                        EngineNotifications.ACTION_RESUME -> host.resume(id)
                        EngineNotifications.ACTION_CANCEL -> host.cancel(id)
                        else -> Unit
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
