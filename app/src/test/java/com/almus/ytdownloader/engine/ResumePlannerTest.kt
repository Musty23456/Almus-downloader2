package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePlannerTest {
    private fun download(
        total: Long = 1000,
        etag: String? = "\"v1\"",
        lastModified: String? = null,
        ranges: Boolean? = true,
    ) = EngineDownload(
        id = 1, url = "https://example.com/a.bin", urlKey = "k", fileName = "a.bin", mimeType = null, subfolder = null,
        state = EngineState.Queued, downloadedBytes = 0, totalBytes = total, etag = etag, lastModified = lastModified,
        acceptsRanges = ranges, partName = "dl-1.part", finalUri = null, errorMessage = null, retryCount = 0,
        nextRetryAtMs = 0, insecure = false, notified = false, createdAtMs = 0, updatedAtMs = 0,
    )

    @Test fun noPartialFileMeansStartFresh() {
        assertEquals(ResumePlan.Fresh(null), ResumePlanner.plan(0, download()))
    }

    @Test fun resumesWithTheStrongEtag() {
        assertEquals(ResumePlan.Resume(400, "\"v1\""), ResumePlanner.plan(400, download()))
    }

    @Test fun aWeakEtagFallsBackToLastModified() {
        val d = download(etag = "W/\"x\"", lastModified = "Wed, 01 Jan 2026 00:00:00 GMT")
        assertEquals(ResumePlan.Resume(400, "Wed, 01 Jan 2026 00:00:00 GMT"), ResumePlanner.plan(400, d))
    }

    @Test fun serversThatRefuseRangesAreNotAsked() {
        val plan = ResumePlanner.plan(400, download(ranges = false))
        assertTrue(plan is ResumePlan.Fresh && plan.reason != null)
    }

    @Test fun unknownRangeSupportIsTried() {
        assertTrue(ResumePlanner.plan(400, download(ranges = null)) is ResumePlan.Resume)
    }

    @Test fun withoutAnyValidatorAndSizeItStartsAgain() {
        val plan = ResumePlanner.plan(400, download(total = -1, etag = null, lastModified = null))
        assertTrue(plan is ResumePlan.Fresh && plan.reason != null)
    }

    @Test fun aKnownSizeAllowsResumeWithoutValidator() {
        assertEquals(ResumePlan.Resume(400, null), ResumePlanner.plan(400, download(etag = null)))
    }

    @Test fun aFullPartialFileIsComplete() {
        assertEquals(ResumePlan.Complete, ResumePlanner.plan(1000, download()))
    }

    @Test fun aTooLargePartialFileStartsAgain() {
        assertTrue(ResumePlanner.plan(1001, download()) is ResumePlan.Fresh)
    }
}
