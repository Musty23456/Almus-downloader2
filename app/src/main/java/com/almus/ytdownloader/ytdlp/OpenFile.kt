package com.almus.ytdownloader.ytdlp

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a saved file in another app. Returns false when no app can open it. Only ever called from a tap. */
fun openSavedFile(context: Context, uri: String): Boolean {
    val parsed = Uri.parse(uri)
    val type = context.contentResolver.getType(parsed) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(parsed, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
