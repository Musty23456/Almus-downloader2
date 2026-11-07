package com.almus.ytdownloader.files

import android.content.Context

/** Stores the folder chosen with the Storage Access Framework. */
class SharedPrefsFolderSettings(context: Context) : FolderSettings {
    private val prefs = context.applicationContext.getSharedPreferences("almus_settings", Context.MODE_PRIVATE)

    override var treeUri: String?
        get() = prefs.getString(KEY, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "files_tree_uri"
    }
}
