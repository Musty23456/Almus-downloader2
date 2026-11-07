package com.almus.ytdownloader.download

import android.content.Context

/** Stores the concurrency limit in SharedPreferences (it is a single small number). */
class SharedPrefsQueueSettings(context: Context) : QueueSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var maxConcurrent: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT, QueueSettings.DEFAULT)
            .coerceIn(QueueSettings.MIN, QueueSettings.MAX)
        set(value) {
            prefs.edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(QueueSettings.MIN, QueueSettings.MAX)).apply()
        }

    private companion object {
        const val PREFS_NAME = "almus_settings"
        const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
    }
}
