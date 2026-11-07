package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
    private val mb = 1024L * 1024

    /** Feeds one sample per second at a constant [bytesPerSecond]; returns the time of the last sample. */
    private fun feed(e: SpeedEstimator, seconds: Int, bytesPerSecond: Long, startMs: Long = 0, startBytes: Long = 0): Long {
        var t = startMs
        for (i in 0..seconds) {
            e.record(startBytes + i * bytesPerSecond, startMs + i * 1000L)
            t = startMs + i * 1000L
        }
        return t
    }

    @Test fun noDataMeansNoSpeedAndNoEta() {
        val e = SpeedEstimator()
        assertNull(e.bytesPerSecond(0))
        assertNull(e.etaSeconds(1000, 0))
    }

    @Test fun oneOrTwoSamplesAreNotEnough() {
        val e = SpeedEstimator()
        e.record(0, 0)
        e.record(1000, 1000)
        assertNull(e.bytesPerSecond(1000))
    }

    @Test fun samplesCloseTogetherAreNotEnough() {
        val e = SpeedEstimator()
        e.record(0, 0)
        e.record(100_000, 300)
        e.record(200_000, 600)
        assertNull("span of 0.6 s is too short to trust", e.bytesPerSecond(600))
    }

    @Test fun speedAppearsAfterAFewSecondsAndIsAccurateForASteadyTransfer() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 3, bytesPerSecond = mb)
        assertEquals(mb, e.bytesPerSecond(now))
    }

    @Test fun etaNeedsMoreDataThanSpeed() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 3, bytesPerSecond = mb)
        assertNotNull(e.bytesPerSecond(now))
        assertNull("only 3 s of data: no ETA yet", e.etaSeconds(100 * mb, now))
    }

    @Test fun etaIsGivenOnceEnoughDataExists() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 6, bytesPerSecond = mb)
        assertEquals(100L, e.etaSeconds(100 * mb, now))
    }

    @Test fun etaIsRoundedUp() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 6, bytesPerSecond = mb)
        assertEquals(2L, e.etaSeconds(mb + 1, now))
    }

    @Test fun noEtaWhenNothingIsLeftOrSizeUnknown() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 6, bytesPerSecond = mb)
        assertNull(e.etaSeconds(0, now))
        assertNull(e.etaSeconds(-1, now))
    }

    @Test fun tinyTransfersGiveNoEta() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 8, bytesPerSecond = 1000) // 8 KB in total, below the 32 KB floor
        assertNull(e.etaSeconds(10 * mb, now))
    }

    @Test fun absurdEtasAreHidden() {
        val e = SpeedEstimator()
        val now = feed(e, seconds = 8, bytesPerSecond = 10 * 1024) // 10 KB/s
        assertNotNull(e.etaSeconds(100 * 1024L, now))
        assertNull("more than 48 h", e.etaSeconds(10L * 1024 * 1024 * 1024, now))
    }

    @Test fun stalledTransferShowsNothing() {
        val e = SpeedEstimator()
        var now = feed(e, seconds = 6, bytesPerSecond = mb)
        val bytes = 6 * mb
        for (i in 1..6) {
            now += 1000
            e.record(bytes, now) // no progress for 6 s
        }
        assertNull(e.bytesPerSecond(now))
        assertNull(e.etaSeconds(50 * mb, now))
    }

    @Test fun transferThatRestartsFromZeroForgetsOldSamples() {
        val e = SpeedEstimator()
        var now = feed(e, seconds = 6, bytesPerSecond = mb)
        now += 1000
        e.record(0, now) // restarted
        assertNull(e.bytesPerSecond(now))
    }

    @Test fun oldSamplesFallOutOfTheWindow() {
        val e = SpeedEstimator()
        feed(e, seconds = 5, bytesPerSecond = 10 * mb) // fast start
        // then a long slow phase of 1 MB/s; the window is 15 s so the fast part is forgotten
        val now = feed(e, seconds = 30, bytesPerSecond = mb, startMs = 5_000, startBytes = 50 * mb)
        val speed = e.bytesPerSecond(now)!!
        assertTrue("speed $speed should be about 1 MB/s", speed in (mb * 9 / 10)..(mb * 11 / 10))
    }

    @Test fun sampleTakenLongAfterTheOthersDoesNotInventASpeed() {
        val e = SpeedEstimator()
        e.record(0, 0)
        e.record(mb, 1000)
        e.record(2 * mb, 2000)
        // The app was in the background for a minute; the next sample is far away.
        e.record(40 * mb, 62_000)
        assertNull("old samples are dropped, one sample remains", e.bytesPerSecond(62_000))
    }
}
