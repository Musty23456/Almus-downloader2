package com.almus.ytdownloader.engine

/** How often and how fast the engine tries again after a connection problem. Pure Kotlin, unit tested. */
data class RetryPolicy(
    val maxRetries: Int = 5,
    val baseDelayMs: Long = 2_000,
    val maxDelayMs: Long = 60_000,
    /** A server's Retry-After is honoured up to this long. */
    val maxRetryAfterMs: Long = 5 * 60_000,
) {
    /** Delay before retry number [attempt] (1 for the first retry): doubling, capped, never below Retry-After. */
    fun delayMs(attempt: Int, retryAfterMs: Long? = null): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val backoff = (baseDelayMs * (1L shl shift)).coerceAtMost(maxDelayMs)
        val server = retryAfterMs?.coerceAtMost(maxRetryAfterMs) ?: 0L
        return maxOf(backoff, server)
    }

    /** HTTP answers that may go away by themselves. Everything else (403, 404 ...) will not. */
    fun isRetryableStatus(code: Int): Boolean = code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
}
