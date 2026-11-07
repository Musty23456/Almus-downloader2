package com.almus.ytdownloader.engine

/** What to do with a partial file when a download starts again. */
sealed interface ResumePlan {
    /** Start from zero. [reason] explains why a partial file was thrown away, or is null when there was none. */
    data class Fresh(val reason: String?) : ResumePlan

    /** Ask the server for the rest, starting at [offset]. [validator] goes into If-Range (null when none is safe). */
    data class Resume(val offset: Long, val validator: String?) : ResumePlan

    /** The partial file already has every byte. */
    data object Complete : ResumePlan
}

/**
 * Decides, before any request, whether a partial file may be continued.
 * The server's answer is checked again afterwards (206 and Content-Range); this only avoids hopeless requests.
 * Pure Kotlin, unit tested.
 */
object ResumePlanner {
    fun plan(partLength: Long, d: EngineDownload): ResumePlan {
        if (partLength <= 0) return ResumePlan.Fresh(null)
        if (d.acceptsRanges == false) {
            return ResumePlan.Fresh("The server does not support resuming, so the download starts again.")
        }
        if (d.totalBytes > 0) {
            if (partLength == d.totalBytes) return ResumePlan.Complete
            if (partLength > d.totalBytes) return ResumePlan.Fresh("The partial file is larger than expected, so the download starts again.")
        }
        val etag = d.etag
        val validator = if (etag != null && HttpHeaders.isStrongEtag(etag)) etag else d.lastModified
        if (validator == null && d.totalBytes <= 0) {
            return ResumePlan.Fresh("There is no way to check that the file did not change, so the download starts again.")
        }
        return ResumePlan.Resume(partLength, validator)
    }
}
