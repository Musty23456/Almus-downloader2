package com.almus.ytdownloader.browser

import android.content.Context

/**
 * Keeps two small things in SharedPreferences: the sites with JavaScript turned on, and whether
 * the user has read the privacy notice. No browsing history, no addresses and no cookies are stored here.
 */
class SharedPrefsBrowserStore(context: Context) : SiteJsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY_JS_HOSTS, emptySet())?.toSet() ?: emptySet()

    override fun save(hosts: Set<String>) {
        // A new set every time: the set returned by getStringSet must never be modified in place.
        prefs.edit().putStringSet(KEY_JS_HOSTS, HashSet(hosts)).apply()
    }

    var privacyNoticeAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "almus_browser"
        const val KEY_JS_HOSTS = "javascript_hosts"
        const val KEY_PRIVACY_ACCEPTED = "privacy_notice_accepted"
    }
}
