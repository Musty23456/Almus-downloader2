package com.almus.ytdownloader.engine

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentMergerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val data = RangeFileServer.bytes(10_000)
    private val files by lazy { TempFiles(tmp.root, 1, "dl-1.part") }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    /** Writes every segment file and returns a manifest saying they are all complete. */
    private fun prepared(segmentSize: Long = 3_000): SegmentManifest {
        var m = SegmentManifest.fresh(data.size.toLong(), segmentSize, Validator("\"a\"", null))
        for (s in m.segments) {
            val part = data.copyOfRange(s.segment.start.toInt(), s.segment.endInclusive.toInt() + 1)
            files.segment(s.segment.index).writeBytes(part)
            m = m.withSegment(SegmentState(s.segment, s.segment.length, crc(part)))
        }
        return m
    }

    private fun save(m: SegmentManifest) = SegmentManifestFile.write(files.manifest, m)

    @Test fun mergesAllSegmentsIntoThePartFile() {
        val m = prepared()
        assertEquals(MergeResult.Merged, SegmentMerger.merge(files, m, ::save))
        assertArrayEquals(data, files.part.readBytes())
        assertFalse(files.merge.exists())
        assertEquals(emptyList<String>(), tmp.root.list()!!.filter { it.contains(".seg") && !it.endsWith(".segs") })
    }

    @Test fun aDamagedSegmentIsReportedAndNothingDamagedIsKept() {
        val m = prepared()
        val f = files.segment(2)
        f.writeBytes(f.readBytes().also { it[10] = (it[10] + 1).toByte() })
        val r = SegmentMerger.merge(files, m, ::save)
        assertTrue(r is MergeResult.Corrupt && r.index == 2)
        assertFalse(files.part.exists())
        // The merge file holds exactly the verified bytes before the damaged segment.
        assertEquals(6_000L, files.merge.length())
        assertArrayEquals(data.copyOfRange(0, 6_000), files.merge.readBytes())
    }

    @Test fun aSegmentWithTheWrongSizeIsReported() {
        val m = prepared()
        files.segment(1).let { it.writeBytes(it.readBytes().copyOf(2_999)) }
        val r = SegmentMerger.merge(files, m, ::save)
        assertTrue(r is MergeResult.Corrupt && r.index == 1)
    }

    @Test fun aMissingFirstSegmentIsReported() {
        val m = prepared()
        files.segment(0).delete()
        assertTrue((SegmentMerger.merge(files, m, ::save) as MergeResult.Corrupt).index == 0)
    }

    @Test fun aDamagedFirstSegmentIsReported() {
        val m = prepared()
        files.segment(0).let { it.writeBytes(it.readBytes().also { b -> b[0] = (b[0] + 1).toByte() }) }
        assertTrue((SegmentMerger.merge(files, m, ::save) as MergeResult.Corrupt).index == 0)
    }

    @Test fun afterRepairingTheDamagedSegmentTheMergeContinuesWhereItStopped() {
        var m = prepared()
        val good = files.segment(2).readBytes()
        files.segment(2).writeBytes(good.copyOf().also { it[5] = (it[5] + 1).toByte() })
        var journal = m
        val first = SegmentMerger.merge(files, m, { journal = it; save(it) }, syncEveryBytes = 1)
        assertTrue(first is MergeResult.Corrupt)
        assertEquals(2, journal.mergedCount)
        files.segment(2).writeBytes(good) // the engine downloads the segment again
        assertEquals(MergeResult.Merged, SegmentMerger.merge(files, journal, ::save))
        assertArrayEquals(data, files.part.readBytes())
    }

    @Test fun aCrashAfterAnAppendButBeforeTheJournalIsRepaired() {
        val m = prepared()
        var journal = m
        // Run the merge but stop after segment 1 was appended and journaled.
        var calls = 0
        try {
            SegmentMerger.merge(files, m, { journal = it; save(it); if (++calls == 2) throw IllegalStateException("crash") }, syncEveryBytes = 1)
        } catch (_: IllegalStateException) {
        }
        assertEquals(2, journal.mergedCount)
        // Garbage left behind by an append that the journal never recorded.
        files.merge.appendBytes(ByteArray(777) { 9 })
        assertEquals(MergeResult.Merged, SegmentMerger.merge(files, journal, ::save))
        assertArrayEquals(data, files.part.readBytes())
    }

    @Test fun aMissingMergeFileCannotBeContinued() {
        val m = prepared()
        var journal = m
        var calls = 0
        try {
            SegmentMerger.merge(files, m, { journal = it; save(it); if (++calls == 2) throw IllegalStateException("crash") }, syncEveryBytes = 1)
        } catch (_: IllegalStateException) {
        }
        files.merge.delete()
        assertTrue(SegmentMerger.merge(files, journal, ::save) is MergeResult.Unrecoverable)
    }

    @Test fun anIncompleteManifestIsRefused() {
        val m = SegmentManifest.fresh(10_000, 3_000, Validator("\"a\"", null))
        assertTrue(SegmentMerger.merge(files, m, ::save) is MergeResult.Unrecoverable)
    }

    @Test fun aPauseDuringTheMergeLeavesAConsistentState() {
        val m = prepared()
        var journal = m
        var polls = 0
        try {
            SegmentMerger.merge(files, m, { journal = it; save(it) }, { ++polls < 4 }, syncEveryBytes = 1)
        } catch (_: SegmentMerger.MergeInterrupted) {
        }
        assertFalse(files.part.exists())
        assertEquals(MergeResult.Merged, SegmentMerger.merge(files, journal, ::save))
        assertArrayEquals(data, files.part.readBytes())
    }

    @Test fun aSingleSegmentFileMerges() {
        val m = prepared(segmentSize = 20_000)
        assertEquals(1, m.segments.size)
        assertEquals(MergeResult.Merged, SegmentMerger.merge(files, m, ::save))
        assertArrayEquals(data, files.part.readBytes())
    }
}
