package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeValidatorTest {
    private val v = Validator("\"abc\"", "Wed, 21 Oct 2015 07:28:00 GMT")

    private fun check(code: Int, headers: Map<String, String>, start: Long = 100, end: Long = 199, total: Long = 1000, validator: Validator = v): RangeCheck =
        RangeValidator.check(code, { name -> headers.entries.firstOrNull { it.key.equals(name, true) }?.value }, start, end, total, validator)

    private val good = mapOf("Content-Range" to "bytes 100-199/1000", "Content-Length" to "100", "ETag" to "\"abc\"")

    @Test fun acceptsACorrectPartialAnswer() = assertEquals(RangeCheck.Ok, check(206, good))

    @Test fun acceptsWhenOptionalHeadersAreMissing() = assertEquals(RangeCheck.Ok, check(206, mapOf("Content-Range" to "bytes 100-199/1000")))

    @Test fun rejectsAMissingOrMalformedContentRange() {
        assertTrue(check(206, mapOf("Content-Length" to "100")) is RangeCheck.Unsupported)
        assertTrue(check(206, mapOf("Content-Range" to "bytes 100-199")) is RangeCheck.Unsupported)
        assertTrue(check(206, mapOf("Content-Range" to "bytes 199-100/1000")) is RangeCheck.Unsupported)
    }

    @Test fun rejectsADifferentRangeThanAsked() {
        assertTrue(check(206, good + ("Content-Range" to "bytes 101-199/1000")) is RangeCheck.Unsupported)
        assertTrue(check(206, good + ("Content-Range" to "bytes 100-200/1000")) is RangeCheck.Unsupported)
        assertTrue(check(206, good + ("Content-Range" to "bytes 0-99/1000")) is RangeCheck.Unsupported)
    }

    @Test fun anUnknownTotalIsNotGoodEnough() = assertTrue(check(206, good + ("Content-Range" to "bytes 100-199/*")) is RangeCheck.Unsupported)

    @Test fun aDifferentTotalMeansTheFileChanged() = assertTrue(check(206, good + ("Content-Range" to "bytes 100-199/2000")) is RangeCheck.Changed)

    @Test fun aWrongContentLengthIsRejected() {
        assertTrue(check(206, good + ("Content-Length" to "99")) is RangeCheck.Unsupported)
        assertTrue(check(206, good + ("Content-Length" to "abc")) is RangeCheck.Unsupported)
    }

    @Test fun aDifferentETagMeansTheFileChanged() = assertTrue(check(206, good + ("ETag" to "\"other\"")) is RangeCheck.Changed)

    @Test fun lastModifiedIsComparedWhenOnlyItIsKnown() {
        val lm = Validator(null, "Wed, 21 Oct 2015 07:28:00 GMT")
        assertEquals(RangeCheck.Ok, check(206, good - "ETag" + ("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT"), validator = lm))
        assertTrue(check(206, good - "ETag" + ("Last-Modified" to "Thu, 22 Oct 2015 07:28:00 GMT"), validator = lm) is RangeCheck.Changed)
    }

    @Test fun contentCodingAndMultipartAreRejected() {
        assertTrue(check(206, good + ("Content-Encoding" to "gzip")) is RangeCheck.Unsupported)
        assertEquals(RangeCheck.Ok, check(206, good + ("Content-Encoding" to "identity")))
        assertTrue(check(206, good + ("Content-Type" to "multipart/byteranges; boundary=x")) is RangeCheck.Unsupported)
    }

    @Test fun aFullAnswerToARangedRequestIsClassifiedByWhatItSays() {
        assertTrue(check(200, mapOf("Content-Length" to "1000", "ETag" to "\"abc\"")) is RangeCheck.Unsupported)
        assertTrue(check(200, mapOf("Content-Length" to "1000", "ETag" to "\"new\"")) is RangeCheck.Changed)
        assertTrue(check(200, mapOf("Content-Length" to "1500", "ETag" to "\"abc\"")) is RangeCheck.Changed)
    }

    @Test fun status416MeansChanged() = assertTrue(check(416, emptyMap()) is RangeCheck.Changed)

    @Test fun busyServersAreRetriedAndThrottlingIsFlagged() {
        val r429 = check(429, mapOf("Retry-After" to "3")) as RangeCheck.Retry
        assertTrue(r429.throttled)
        assertEquals(3000L, r429.retryAfterMs)
        assertTrue((check(503, emptyMap()) as RangeCheck.Retry).throttled)
        assertFalse((check(500, emptyMap()) as RangeCheck.Retry).throttled)
        assertTrue(check(504, emptyMap()) is RangeCheck.Retry)
    }

    @Test fun otherErrorsAreFatal() {
        assertTrue(check(404, emptyMap()) is RangeCheck.Fatal)
        assertTrue(check(403, emptyMap()) is RangeCheck.Fatal)
    }

    @Test fun validatorPrefersStrongETagAndRefusesWeakOnes() {
        assertEquals("\"a\"", Validator("\"a\"", "date").ifRange)
        assertEquals("date", Validator("W/\"a\"", "date").ifRange)
        assertFalse(Validator("W/\"a\"", null).usable)
        assertFalse(Validator(null, null).usable)
        assertTrue(Validator(null, "date").usable)
    }
}
