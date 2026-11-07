package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.InspectResult
import com.almus.ytdownloader.download.LinkInfo

sealed interface IntakeResult {
    /** Stop here and show [message]. */
    data class Rejected(val message: String) : IntakeResult

    /** A known platform page, a restricted link, or similar: show the explanation. Nothing is downloaded. */
    data class Detected(val detection: LinkDetection) : IntakeResult

    /** The server was asked and the link can be offered to the user for approval. */
    data class Ready(val info: LinkInfo, val detection: LinkDetection) : IntakeResult
}

/**
 * The steps between "the user entered a link" and "the user is asked to approve a download":
 * detect, resolve short links safely, stop at pages that are not files, check duplicates, ask the server.
 * Nothing here saves a file. Steps are passed in as functions so the whole flow runs in plain JVM tests.
 */
class LinkIntake(
    private val detector: SmartLinkDetector,
    private val resolve: suspend (String) -> RedirectTrace,
    private val inspect: suspend (String) -> InspectResult,
    private val isDuplicate: suspend (String) -> Boolean,
) {
    /**
     * @param skipPlatformCheck set when the user asked to check a recognised platform page as an ordinary link
     * anyway (for example to see what the server returns). It never lifts a [LinkVerdict.Restricted] result.
     */
    suspend fun check(raw: String, skipPlatformCheck: Boolean = false): IntakeResult {
        var detection = detector.detect(raw)

        if (detection.verdict == LinkVerdict.ShortLink && !skipPlatformCheck) {
            val start = detection.url ?: return IntakeResult.Rejected(detection.message)
            when (val trace = resolve(start)) {
                is RedirectTrace.Failed -> return IntakeResult.Rejected(trace.message)
                is RedirectTrace.Resolved -> {
                    detection = detector.detect(trace.finalUrl, ObservedResponse(trace.hops, trace.status))
                    if (detection.verdict == LinkVerdict.ShortLink) {
                        return IntakeResult.Rejected("The short link leads to another short link. Open it in the Browser tab to see where it goes.")
                    }
                }
            }
        }

        when (detection.verdict) {
            LinkVerdict.Invalid, LinkVerdict.Blocked -> return IntakeResult.Rejected(detection.message)
            LinkVerdict.Restricted -> return IntakeResult.Detected(detection)
            LinkVerdict.PageNotDownloadable -> if (!skipPlatformCheck) return IntakeResult.Detected(detection)
            LinkVerdict.ShortLink, LinkVerdict.DirectFile, LinkVerdict.ServerCheck -> Unit
        }

        val target = detection.url ?: return IntakeResult.Rejected(detection.message)
        if (isDuplicate(target)) {
            return IntakeResult.Rejected("This link is already in your queue or downloading.")
        }
        return when (val result = inspect(target)) {
            is InspectResult.Success -> IntakeResult.Ready(result.info, detection)
            is InspectResult.Failure -> IntakeResult.Rejected(result.message)
        }
    }
}
