package com.almus.ytdownloader.engine

/**
 * Chooses how many connections one download should use, from what it measures: it adds a connection only while the
 * added connection really raises the total speed, takes it back when it does not help, and backs off at once when
 * the server complains. A server (or a link) that caps the total speed therefore ends up with few connections instead
 * of many slow ones. Time is passed in, so it is deterministic and unit tested.
 *
 * Measuring works in windows of [windowMs]. After every change the next window is ignored (connections need time to
 * ramp up), then the speed of one whole window is compared with the speed before the change.
 */
class AdaptiveConnections(
    private val minConnections: Int = 1,
    initial: Int = 2,
    ceiling: Int = 8,
    private val windowMs: Long = 1_500,
    /** An added connection must raise the speed by at least this factor to be kept. */
    private val minGain: Double = 1.15,
    /** After a plateau is found, wait this many windows before trying one more connection. */
    private val holdWindows: Int = 8,
    /** After the server asked us to slow down, do not add connections for this long. */
    private val throttleHoldMs: Long = 30_000,
) {
    enum class Failure {
        /** HTTP 429/503 or a Retry-After: the server is overloaded or limiting us. */
        Throttled,

        /** A connection broke or timed out. */
        Connection,
    }

    private enum class Last { None, Increased, Held }

    @Volatile var ceiling: Int = ceiling.coerceAtLeast(minConnections)
        private set

    @Volatile var target: Int = initial.coerceIn(minConnections, this.ceiling)
        private set

    private var windowStartMs = -1L
    private var windowStartBytes = 0L
    private var settleUntilMs = Long.MIN_VALUE
    private var baselineRate = -1.0
    private var last = Last.None
    private var holdUntilMs = Long.MIN_VALUE

    /** The ceiling comes from the network and the user's setting; it can change while the download runs. */
    @Synchronized
    fun setCeiling(value: Int) {
        ceiling = value.coerceAtLeast(minConnections)
        if (target > ceiling) {
            target = ceiling
            resetWindow()
        }
    }

    /** Feed the total number of bytes received so far (all connections together) now and then. */
    @Synchronized
    fun onProgress(totalBytes: Long, nowMs: Long) {
        if (nowMs < settleUntilMs) return
        if (windowStartMs < 0) {
            windowStartMs = nowMs
            windowStartBytes = totalBytes
            return
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return
        val rate = (totalBytes - windowStartBytes) * 1000.0 / elapsed
        windowStartMs = nowMs
        windowStartBytes = totalBytes
        evaluate(rate, nowMs)
    }

    @Synchronized
    fun onFailure(kind: Failure, nowMs: Long) {
        when (kind) {
            Failure.Throttled -> {
                target = (target / 2).coerceAtLeast(minConnections)
                holdUntilMs = nowMs + throttleHoldMs
            }
            Failure.Connection -> {
                target = (target - 1).coerceAtLeast(minConnections)
                holdUntilMs = nowMs + holdWindows * windowMs
            }
        }
        last = Last.Held
        baselineRate = -1.0
        resetWindow()
        settleUntilMs = nowMs + windowMs
    }

    private fun evaluate(rate: Double, nowMs: Long) {
        when (last) {
            Last.Increased -> {
                if (rate >= baselineRate * minGain) {
                    baselineRate = rate
                    increaseOrHold(nowMs)
                } else {
                    // The extra connection did not pay: give it back and stay here for a while.
                    target = (target - 1).coerceAtLeast(minConnections)
                    last = Last.Held
                    baselineRate = -1.0
                    holdUntilMs = nowMs + holdWindows * windowMs
                    changed(nowMs)
                }
            }
            Last.None, Last.Held -> {
                baselineRate = rate
                if (nowMs >= holdUntilMs) increaseOrHold(nowMs) else last = Last.Held
            }
        }
    }

    private fun increaseOrHold(nowMs: Long) {
        if (target < ceiling) {
            target++
            last = Last.Increased
            changed(nowMs)
        } else {
            last = Last.Held
        }
    }

    private fun changed(nowMs: Long) {
        resetWindow()
        settleUntilMs = nowMs + windowMs
    }

    private fun resetWindow() {
        windowStartMs = -1L
    }
}

/** Where the data connection comes from. Used to decide how hard a download may press on it. */
enum class NetworkKind { Unknown, Wifi, Ethernet, Cellular, Other }

/**
 * Network-aware limits. Pure Kotlin, unit tested. The numbers are deliberately modest: more connections stop helping
 * quickly, cost battery and radio time on mobile data, and are impolite to servers.
 */
object ConnectionPolicy {
    const val ABSOLUTE_MAX = 16

    /**
     * The most connections one download may use right now.
     * @param userMax the user's own limit, 0 for automatic
     * @param activeDownloads how many downloads currently run, because they share the same link
     */
    fun ceiling(status: NetworkStatus, userMax: Int, activeDownloads: Int = 1): Int {
        if (!status.online) return 1
        val share = activeDownloads.coerceAtLeast(1)
        if (userMax > 0) return (userMax.coerceAtMost(ABSOLUTE_MAX) / share).coerceAtLeast(1).coerceAtLeast(minOf(userMax, 2))
        var auto = when {
            status.kind == NetworkKind.Ethernet -> 8
            status.kind == NetworkKind.Wifi && !status.metered -> 6
            status.metered || status.kind == NetworkKind.Cellular -> 3
            else -> 4
        }
        // A slow link gains nothing from many connections.
        if (status.downstreamKbps in 1..3_000) auto = minOf(auto, 2)
        else if (status.downstreamKbps in 3_001..10_000) auto = minOf(auto, 3)
        return (auto / share).coerceAtLeast(2).coerceAtMost(auto)
    }

    /** Where the tuner starts: modest on mobile data, a little more elsewhere. */
    fun initial(ceiling: Int): Int = minOf(ceiling, 2)
}
