package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {
    private val cfg = SegmentationConfig()
    private val mib = SegmentationConfig.MIB

    @Test fun segmentsTileTheFileExactly() {
        for (total in listOf(1L, 99L, 1_000_000L, 5 * mib, 100 * mib + 17, 3_000_000_001L)) {
            val size = SegmentPlanner.segmentSize(total, 6, cfg)
            val plan = SegmentPlanner.plan(total, size)
            assertEquals(0L, plan.first().start)
            assertEquals(total - 1, plan.last().endInclusive)
            plan.zipWithNext { a, b -> assertEquals(a.endInclusive + 1, b.start) }
            assertEquals(total, plan.sumOf { it.length })
            assertEquals(plan.indices.toList(), plan.map { it.index })
            assertTrue(plan.dropLast(1).all { it.length == size })
        }
    }

    @Test fun onlyTheLastSegmentMayBeShort() {
        val plan = SegmentPlanner.plan(10, 4)
        assertEquals(listOf(4L, 4L, 2L), plan.map { it.length })
    }

    @Test fun sizeAimsAtSeveralSegmentsPerConnection() {
        val size = SegmentPlanner.segmentSize(64 * mib, 4, cfg)
        assertEquals(4 * mib, size) // 64 MiB / (4 connections * 4)
        assertTrue(SegmentPlanner.plan(64 * mib, size).size >= 16)
    }

    @Test fun sizeIsClampedToTheLimits() {
        assertEquals(mib, SegmentPlanner.segmentSize(5 * mib, 8, cfg))
        assertEquals(16 * mib, SegmentPlanner.segmentSize(10_000 * mib, 4, cfg))
        val huge = SegmentPlanner.segmentSize(1_000_000 * mib, 4, cfg)
        assertTrue(SegmentPlanner.plan(1_000_000 * mib, huge).size <= cfg.maxSegments)
    }

    @Test fun sizeIsAligned() {
        val size = SegmentPlanner.segmentSize(123_456_789L, 5, cfg)
        assertEquals(0L, size % cfg.alignBytes)
    }

    @Test fun segmentCountIsLimitedForHugeFiles() {
        val total = 2_000_000L * mib
        val plan = SegmentPlanner.plan(total, SegmentPlanner.segmentSize(total, 8, cfg))
        assertTrue(plan.size <= cfg.maxSegments)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyFiles() {
        SegmentPlanner.plan(0, 10)
    }
}
