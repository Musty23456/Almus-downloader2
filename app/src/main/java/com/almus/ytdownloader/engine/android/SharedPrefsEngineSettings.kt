package com.almus.ytdownloader.engine.android

import android.content.Context
import com.almus.ytdownloader.download.QueueSettings
import com.almus.ytdownloader.engine.EngineSettings

/**
 * Engine settings. The number of simultaneous downloads is the app's existing setting, so one control
 * serves both engines. [useNewEngine] is off by default: the DownloadManager-based engine stays the
 * default until the new one has been tested on devices.
 */
class SharedPrefsEngineSettings(context: Context, private val queue: QueueSettings) : EngineSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val maxConcurrent: Int get() = queue.maxConcurrent

    override var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        }

    var useNewEngine: Boolean
        get() = prefs.getBoolean(KEY_USE_NEW_ENGINE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_NEW_ENGINE, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "almus_engine"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_USE_NEW_ENGINE = "use_new_engine"
    }
}
