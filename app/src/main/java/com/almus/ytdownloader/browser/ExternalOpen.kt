package com.almus.ytdownloader.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands an address to another app. Only ever called from a tap on a menu item or on a mailto:/tel: link. */
object ExternalOpen {
    private val allowed = setOf("http", "https", "mailto", "tel", "sms", "smsto")

    /** Returns true when an app was started. Never throws. */
    fun open(context: Context, url: String?): Boolean {
        val text = url?.trim().orEmpty()
        val scheme = text.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in allowed) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
