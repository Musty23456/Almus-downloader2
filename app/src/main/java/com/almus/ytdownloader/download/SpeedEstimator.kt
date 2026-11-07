package com.almus.ytdownloader.download

/**
 * Estimates transfer speed and remaining time from progress samples over a short sliding window.
 * It prefers saying nothing over guessing: speed needs several samples spread over a few seconds,
 * and the remaining time needs even more data. A stalled transfer shows neither.
 * Pure Kotlin (time is passed in), unit tested.
 */
class SpeedEstimator {
    private class Sample(val timeMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastProgressAtMs = -1L

    fun record(bytes: Long, nowMs: Long) {
        val last = samples.lastOrNull()
        if (last != null && (bytes < last.bytes || nowMs < last.timeMs)) {
            // The transfer restarted (or the clock moved back): old samples are meaningless.
            samples.clear()
            lastProgressAtMs = -1L
        }
        val previous = samples.lastOrNull()
        if (previous == null || bytes > previous.bytes) lastProgressAtMs = nowMs
        samples.addLast(Sample(nowMs, bytes))
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    /** Bytes per second, or null when there is not enough reliable data. */
    fun bytesPerSecond(nowMs: Long): Long? = measure(nowMs, MIN_SAMPLES, MIN_SPAN_MS, MIN_BYTES_FOR_SPEED)?.bytesPerSecond

    /** Seconds left, or null unless the data is clearly sufficient (and the result sensible). */
    fun etaSeconds(remainingBytes: Long, nowMs: Long): Long? {
        if (remainingBytes <= 0) return null
        val m = measure(nowMs, ETA_MIN_SAMPLES, ETA_MIN_SPAN_MS, ETA_MIN_BYTES) ?: return null
        val seconds = (remainingBytes + m.bytesPerSecond - 1) / m.bytesPerSecond
        return if (seconds in 1..MAX_ETA_SECONDS) seconds else null
    }

    private class Measurement(val bytesPerSecond: Long)

    private fun measure(nowMs: Long, minSamples: Int, minSpanMs: Long, minBytes: Long): Measurement? {
        if (samples.size < minSamples) return null
        if (nowMs - lastProgressAtMs > STALL_MS) return null
        val first = samples.first()
        val last = samples.last()
        val span = last.timeMs - first.timeMs
        val delta = last.bytes - first.bytes
        if (span < minSpanMs || delta < minBytes || delta <= 0) return null
        val bps = delta * 1000 / span
        return if (bps > 0) Measurement(bps) else null
    }

    companion object {
        const val WINDOW_MS = 15_000L
        const val STALL_MS = 5_000L
        const val MIN_SAMPLES = 3
        const val MIN_SPAN_MS = 2_000L
        const val MIN_BYTES_FOR_SPEED = 1L
        const val ETA_MIN_SAMPLES = 5
        const val ETA_MIN_SPAN_MS = 5_000L
        const val ETA_MIN_BYTES = 32L * 1024
        const val MAX_ETA_SECONDS = 48L * 3600
    }
}

/** Speed and remaining time for one running download. Null means "not reliable yet". */
data class SpeedInfo(val bytesPerSecond: Long?, val etaSeconds: Long?)
