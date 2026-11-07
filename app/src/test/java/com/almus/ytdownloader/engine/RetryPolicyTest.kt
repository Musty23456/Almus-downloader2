package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy(maxRetries = 5, baseDelayMs = 2_000, maxDelayMs = 60_000, maxRetryAfterMs = 120_000)

    @Test fun delaysDoubleAndAreCapped() {
        assertEquals(2_000L, policy.delayMs(1))
        assertEquals(4_000L, policy.delayMs(2))
        assertEquals(8_000L, policy.delayMs(3))
        assertEquals(60_000L, policy.delayMs(10))
        assertEquals(60_000L, policy.delayMs(500))
    }

    @Test fun retryAfterRaisesTheDelayButIsCapped() {
        assertEquals(30_000L, policy.delayMs(1, 30_000))
        assertEquals(2_000L, policy.delayMs(1, 500))
        assertEquals(120_000L, policy.delayMs(1, 9_999_999))
    }

    @Test fun onlyTemporaryStatusesAreRetried() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue("$it", policy.isRetryableStatus(it)) }
        listOf(200, 301, 400, 401, 403, 404, 410, 416, 451, 501).forEach { assertFalse("$it", policy.isRetryableStatus(it)) }
    }
}
