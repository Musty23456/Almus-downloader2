package com.almus.ytdownloader.download

import java.util.Locale

/** Formats byte counts for display (1024-based units). Pure Kotlin. */
object ByteFormat {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** "1.2 MB/s" */
    fun speed(bytesPerSecond: Long): String = format(bytesPerSecond) + "/s"

    /** "3.2 MB of 10.0 MB", or "3.2 MB" when the total is unknown. */
    fun progressLabel(downloaded: Long, total: Long): String =
        if (total > 0) "${format(downloaded)} of ${format(total)}" else format(downloaded)
}

/** Formats a remaining time for display. Always "about", because it is an estimate. */
object DurationFormat {
    fun remaining(seconds: Long): String {
        require(seconds >= 0)
        return when {
            seconds < 60 -> "about $seconds s left"
            seconds < 3600 -> "about ${(seconds + 59) / 60} min left"
            else -> {
                var hours = seconds / 3600
                var minutes = (seconds % 3600 + 59) / 60
                if (minutes == 60L) {
                    hours += 1
                    minutes = 0
                }
                if (minutes == 0L) "about $hours h left" else "about $hours h $minutes min left"
            }
        }
    }
}
