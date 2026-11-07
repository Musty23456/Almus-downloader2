package com.almus.ytdownloader.engine

import com.almus.ytdownloader.download.StatusMapper

/**
 * What identifies one version of the file. Every segment request carries it in If-Range, so a file that changed
 * on the server is never stitched together from two versions. A strong ETag is preferred; a weak one must not be
 * used (see [HttpHeaders.isStrongEtag]); otherwise Last-Modified is used. With neither there is no safe way to
 * split the download, and [usable] is false.
 */
data class Validator(val etag: String?, val lastModified: String?) {
    /** The value for the If-Range header, or null when no safe validator exists. */
    val ifRange: String? get() = etag?.takeIf { it.isNotBlank() && HttpHeaders.isStrongEtag(it) } ?: lastModified?.takeIf { it.isNotBlank() }

    val usable: Boolean get() = ifRange != null

    /** True when a response's validators contradict ours. Missing headers are not a contradiction. */
    fun contradictedBy(responseEtag: String?, responseLastModified: String?): Boolean {
        val strong = etag?.takeIf { HttpHeaders.isStrongEtag(it) }
        if (strong != null && !responseEtag.isNullOrBlank() && responseEtag.trim() != strong.trim()) return true
        if (strong == null && lastModified != null && !responseLastModified.isNullOrBlank() && responseLastModified.trim() != lastModified.trim()) return true
        return false
    }
}

/** Verdict on the answer to one ranged request. */
sealed interface RangeCheck {
    /** A correct 206 for exactly the range asked: the body can be trusted. */
    data object Ok : RangeCheck

    /** Temporary problem (busy server, timeout): try again, honouring [retryAfterMs]. [throttled] means the server asked us to slow down. */
    data class Retry(val message: String, val retryAfterMs: Long? = null, val throttled: Boolean = false) : RangeCheck

    /** The file on the server is no longer the one we started with. Everything downloaded so far is useless. */
    data class Changed(val reason: String) : RangeCheck

    /** The server does not really support ranged downloads (it ignored Range, or answered it wrongly). Use one connection instead. */
    data class Unsupported(val reason: String) : RangeCheck

    /** Retrying cannot help (not found, forbidden ...). */
    data class Fatal(val message: String) : RangeCheck
}

/**
 * Checks, header by header, that a server answered a "Range: bytes=start-end" request the way RFC 9110 requires,
 * before a single byte of the body is written. Anything doubtful is a reason not to trust the segment.
 * Pure Kotlin, unit tested.
 */
object RangeValidator {
    fun check(
        code: Int,
        header: (String) -> String?,
        start: Long,
        endInclusive: Long,
        total: Long,
        validator: Validator,
        retry: RetryPolicy = RetryPolicy(),
    ): RangeCheck {
        val length = endInclusive - start + 1
        return when {
            code == 206 -> check206(header, start, endInclusive, length, total, validator)
            code == 200 -> {
                // We always send If-Range. A full answer means either "the file changed" or "Range is ignored".
                val changed = validator.contradictedBy(header("ETag"), header("Last-Modified")) ||
                    HttpHeaders.contentLength(header("Content-Length"))?.let { it != total } == true
                if (changed) RangeCheck.Changed("The file changed on the server while it was being downloaded.")
                else RangeCheck.Unsupported("The server ignored the byte range.")
            }
            code == 416 -> RangeCheck.Changed("The server no longer has the byte range that was requested.")
            retry.isRetryableStatus(code) -> RangeCheck.Retry(
                StatusMapper.httpMessage(code), HttpHeaders.retryAfterMs(header("Retry-After")), throttled = code == 429 || code == 503,
            )
            else -> RangeCheck.Fatal(StatusMapper.httpMessage(code))
        }
    }

    private fun check206(header: (String) -> String?, start: Long, end: Long, length: Long, total: Long, validator: Validator): RangeCheck {
        val contentType = header("Content-Type")?.lowercase().orEmpty()
        if (contentType.contains("multipart/byteranges")) return RangeCheck.Unsupported("The server answered with several ranges.")
        val encoding = header("Content-Encoding")?.trim()?.lowercase()
        if (!encoding.isNullOrEmpty() && encoding != "identity") return RangeCheck.Unsupported("The server compressed a partial answer.")
        val range = HttpHeaders.parseContentRange(header("Content-Range"))
            ?: return RangeCheck.Unsupported("The server's byte range answer is missing or malformed.")
        if (range.start != start || range.end != end) return RangeCheck.Unsupported("The server returned a different byte range than requested.")
        if (range.total == null) return RangeCheck.Unsupported("The server did not say how big the file is.")
        if (range.total != total) return RangeCheck.Changed("The size of the file changed on the server.")
        val announced = header("Content-Length")?.let { HttpHeaders.contentLength(it) ?: return RangeCheck.Unsupported("The server's length is not valid.") }
        if (announced != null && announced != length) return RangeCheck.Unsupported("The server's length does not match the byte range.")
        if (validator.contradictedBy(header("ETag"), header("Last-Modified"))) {
            return RangeCheck.Changed("The file changed on the server while it was being downloaded.")
        }
        return RangeCheck.Ok
    }
}
