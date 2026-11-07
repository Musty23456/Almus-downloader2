package com.almus.ytdownloader.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentManifestTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(): SegmentManifest {
        val m = SegmentManifest.fresh(1000, 300, Validator("\"e t\\ag\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        return m.withSegment(SegmentState(m.segments[0].segment, 300, 0xDEADBEEFL)).withSegment(SegmentState(m.segments[1].segment, 120, 0x1234L))
    }

    @Test fun roundTrips() {
        val m = sample()
        val back = SegmentManifest.decode(m.encode())
        assertEquals(m, back)
        assertEquals(4, back!!.segments.size)
        assertEquals(420L, back.downloadedBytes)
        assertFalse(back.allComplete)
    }

    @Test fun nullValidatorsRoundTrip() {
        val m = SegmentManifest.fresh(10, 4, Validator(null, "x"))
        assertEquals(m, SegmentManifest.decode(m.encode()))
    }

    @Test fun anyChangedByteIsDetected() {
        val text = sample().encode()
        for (i in text.indices step 7) {
            val c = if (text[i] == 'Z') 'Y' else 'Z'
            assertNull("byte $i", SegmentManifest.decode(text.substring(0, i) + c + text.substring(i + 1)))
        }
    }

    @Test fun truncatedAndGarbageTextIsRejected() {
        val text = sample().encode()
        assertNull(SegmentManifest.decode(text.dropLast(5)))
        assertNull(SegmentManifest.decode(text.substring(0, text.length / 2)))
        assertNull(SegmentManifest.decode(""))
        assertNull(SegmentManifest.decode("hello"))
    }

    @Test fun aManifestThatDoesNotTileTheFileIsRejected() {
        val text = sample().encode()
        val body = text.substring(0, text.lastIndexOf("check ")).replace("seg 2 600 899", "seg 2 601 899")
        val crc = java.util.zip.CRC32().apply { update(body.toByteArray()) }.value
        assertNull(SegmentManifest.decode(body + "check " + java.lang.Long.toHexString(crc) + "\n"))
    }

    @Test fun mergeJournalIsValidated() {
        val m = SegmentManifest.fresh(600, 200, Validator("\"a\"", null))
        val done = m.segments.fold(m) { acc, s -> acc.withSegment(SegmentState(s.segment, s.segment.length, 5)) }
        val merging = done.copy(mergedCount = 2, mergedBytes = 400)
        assertEquals(merging, SegmentManifest.decode(merging.encode()))
        assertEquals(600L, merging.downloadedBytes)
        assertNull(SegmentManifest.decode(done.copy(mergedCount = 2, mergedBytes = 399).encode()))
        assertNull(SegmentManifest.decode(m.copy(mergedCount = 1, mergedBytes = 200).encode())) // segment 0 is not complete
    }

    @Test fun compatibilityChecksSizeAndValidators() {
        val m = SegmentManifest.fresh(1000, 300, Validator("\"a\"", "date"))
        assertTrue(m.compatibleWith(1000, "\"a\"", "date"))
        assertTrue(m.compatibleWith(-1, null, null))
        assertFalse(m.compatibleWith(999, "\"a\"", "date"))
        assertFalse(m.compatibleWith(1000, "\"b\"", "date"))
        assertFalse(m.compatibleWith(1000, "\"a\"", "other"))
        assertFalse(SegmentManifest.fresh(1000, 300, Validator("W/\"a\"", null)).compatibleWith(1000, null, null))
    }

    @Test fun fileWriteReplacesAtomicallyAndLeavesNoTempFile() {
        val f = File(tmp.root, "dl-1.segs")
        SegmentManifestFile.write(f, sample())
        SegmentManifestFile.write(f, sample().withSegment(SegmentState(sample().segments[2].segment, 5, 1)))
        assertNotNull(SegmentManifestFile.read(f))
        assertEquals(listOf("dl-1.segs"), tmp.root.list()!!.toList())
        f.writeText("garbage")
        assertNull(SegmentManifestFile.read(f))
        assertNull(SegmentManifestFile.read(File(tmp.root, "missing")))
    }
}
