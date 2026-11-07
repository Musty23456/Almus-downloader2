package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveConnectionsTest {
    /**
     * Simulates a link: [rate] gives the total bytes per second the transfer gets for a number of connections.
     * Feeds the tuner every 250 ms of simulated time and returns the target it ends on and the largest one it ever used.
     */
    private fun simulate(
        tuner: AdaptiveConnections,
        seconds: Int,
        onTick: (Long) -> Unit = {},
        rate: (Int) -> Double,
    ): Pair<Int, Int> {
        var now = 0L
        var bytes = 0.0
        var peak = tuner.target
        while (now < seconds * 1000L) {
            now += 250
            bytes += rate(tuner.target) * 0.25
            tuner.onProgress(bytes.toLong(), now)
            onTick(now)
            peak = maxOf(peak, tuner.target)
        }
        return tuner.target to peak
    }

    @Test fun addsConnectionsWhileEachOneAddsSpeed() {
        // Per-connection speed limit: the total grows with every connection.
        val tuner = AdaptiveConnections(initial = 1, ceiling = 6)
        val (end, _) = simulate(tuner, 60) { n -> n * 1_000_000.0 }
        assertEquals(6, end)
    }

    @Test fun stopsWhenTheTotalSpeedIsCapped() {
        // The link is the limit: 4 MB/s whatever the number of connections.
        val tuner = AdaptiveConnections(initial = 1, ceiling = 8)
        val (end, peak) = simulate(tuner, 120) { n -> if (n == 1) 1_500_000.0 else 4_000_000.0 }
        assertTrue("ended on $end", end <= 3)
        assertTrue("peaked at $peak", peak <= 3)
    }

    @Test fun takesBackAConnectionThatDoesNotHelp() {
        val tuner = AdaptiveConnections(initial = 2, ceiling = 8)
        // 1 MB/s per connection up to 3 connections, then flat.
        val (end, _) = simulate(tuner, 90) { n -> minOf(n, 3) * 1_000_000.0 }
        assertEquals(3, end)
    }

    @Test fun neverExceedsTheCeiling() {
        val tuner = AdaptiveConnections(initial = 1, ceiling = 3)
        val (end, peak) = simulate(tuner, 60) { n -> n * 1_000_000.0 }
        assertEquals(3, end)
        assertEquals(3, peak)
    }

    @Test fun loweringTheCeilingTakesEffectAtOnce() {
        val tuner = AdaptiveConnections(initial = 4, ceiling = 8)
        tuner.setCeiling(2)
        assertEquals(2, tuner.target)
        tuner.setCeiling(1)
        assertEquals(1, tuner.target)
    }

    @Test fun throttlingHalvesTheConnectionsAndHoldsThemDown() {
        val tuner = AdaptiveConnections(initial = 6, ceiling = 8, throttleHoldMs = 30_000)
        tuner.onFailure(AdaptiveConnections.Failure.Throttled, 1_000)
        assertEquals(3, tuner.target)
        // No growth during the hold, even though every added connection would help.
        val (end, _) = simulate(tuner, 20) { n -> n * 1_000_000.0 }
        assertEquals(3, end)
    }

    @Test fun aBrokenConnectionCostsOneConnection() {
        val tuner = AdaptiveConnections(initial = 4, ceiling = 8)
        tuner.onFailure(AdaptiveConnections.Failure.Connection, 5_000)
        assertEquals(3, tuner.target)
    }

    @Test fun neverGoesBelowTheMinimum() {
        val tuner = AdaptiveConnections(minConnections = 1, initial = 1, ceiling = 4)
        repeat(5) { tuner.onFailure(AdaptiveConnections.Failure.Throttled, it * 1000L) }
        assertEquals(1, tuner.target)
    }

    @Test fun triesOneMoreConnectionAfterTheHoldEnds() {
        val tuner = AdaptiveConnections(initial = 2, ceiling = 8, windowMs = 1_000, holdWindows = 4)
        var capLifted = false
        val (_, peak) = simulate(tuner, 120, onTick = { now -> if (now > 30_000) capLifted = true }) { n -> if (capLifted) n * 1_000_000.0 else 2_000_000.0 }
        assertTrue("peak $peak", peak >= 4)
    }
}
