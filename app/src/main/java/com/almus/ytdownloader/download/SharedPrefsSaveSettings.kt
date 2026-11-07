package com.almus.ytdownloader.download

import android.content.Context

/** Stores the folder layout choice in SharedPreferences (one small value). */
class SharedPrefsSaveSettings(context: Context) : SaveSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var layout: SaveLayout
        get() = prefs.getString(KEY_LAYOUT, null)
            ?.let { stored -> SaveLayout.entries.firstOrNull { it.name == stored } }
            ?: SaveLayout.Single
        set(value) {
            prefs.edit().putString(KEY_LAYOUT, value.name).apply()
        }

    private companion object {
        const val PREFS_NAME = "almus_settings"
        const val KEY_LAYOUT = "save_layout"
    }
}
