package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationPolicyTest {
    private val big = 50L * SegmentationConfig.MIB
    private val v = Validator("\"a\"", null)

    private fun eval(
        enabled: Boolean = true, code: Int = 200, total: Long = big, ranges: String? = "bytes", encoding: String? = null,
        validator: Validator = v, connections: Int = 4,
    ) = SegmentationPolicy.evaluate(enabled, code, total, ranges, encoding, validator, connections)

    private fun skipped(d: SegmentationPolicy.Decision) = d is SegmentationPolicy.Decision.Skip

    @Test fun usedWhenEverythingIsThere() = assertEquals(SegmentationPolicy.Decision.Use, eval())

    @Test fun neverWhenSwitchedOff() = assertTrue(skipped(eval(enabled = false)))

    @Test fun onlyForAPlain200() {
        assertTrue(skipped(eval(code = 206)))
        assertTrue(skipped(eval(code = 404)))
    }

    @Test fun neverWithoutAKnownBigEnoughSize() {
        assertTrue(skipped(eval(total = -1)))
        assertTrue(skipped(eval(total = 0)))
        assertTrue(skipped(eval(total = 3L * SegmentationConfig.MIB)))
    }

    @Test fun neverUnlessTheServerAnnouncesBytesRanges() {
        assertTrue(skipped(eval(ranges = null)))
        assertTrue(skipped(eval(ranges = "none")))
        assertTrue(skipped(eval(ranges = "")))
        assertTrue(skipped(eval(ranges = "units")))
        assertEquals(SegmentationPolicy.Decision.Use, eval(ranges = "Bytes"))
    }

    @Test fun neverWithAContentCoding() {
        assertTrue(skipped(eval(encoding = "gzip")))
        assertTrue(skipped(eval(encoding = "br")))
        assertEquals(SegmentationPolicy.Decision.Use, eval(encoding = "identity"))
    }

    @Test fun neverWithoutAValidatorThatCanGoIntoIfRange() {
        assertTrue(skipped(eval(validator = Validator(null, null))))
        assertTrue(skipped(eval(validator = Validator("W/\"weak\"", null))))
        assertEquals(SegmentationPolicy.Decision.Use, eval(validator = Validator("W/\"weak\"", "Wed, 21 Oct 2015 07:28:00 GMT")))
    }

    @Test fun neverWhenOnlyOneConnectionIsAllowed() = assertTrue(skipped(eval(connections = 1)))
}
