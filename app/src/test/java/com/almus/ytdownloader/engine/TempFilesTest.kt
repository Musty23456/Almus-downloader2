package com.almus.ytdownloader.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TempFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun touch(name: String) = File(tmp.root, name).apply { writeText("x") }
    private fun names() = tmp.root.list()!!.sorted()

    @Test fun deleteSegmentArtifactsKeepsThePartFile() {
        val files = TempFiles(tmp.root, 7, "dl-7.part")
        touch("dl-7.part"); touch("dl-7.seg0"); touch("dl-7.seg12"); touch("dl-7.segs"); touch("dl-7.segs.tmp"); touch("dl-7.merge")
        touch("dl-70.seg1"); touch("dl-8.part")
        assertTrue(files.hasSegmentState())
        files.deleteSegmentArtifacts()
        assertEquals(listOf("dl-7.part", "dl-70.seg1", "dl-8.part"), names())
        assertFalse(files.hasSegmentState())
    }

    @Test fun deleteAllRemovesEverythingOfOneDownloadOnly() {
        val files = TempFiles(tmp.root, 7, "dl-7.part")
        touch("dl-7.part"); touch("dl-7.seg3"); touch("dl-7.segs"); touch("dl-71.seg3"); touch("notes.txt")
        files.deleteAll()
        assertEquals(listOf("dl-71.seg3", "notes.txt"), names())
    }

    @Test fun sweepRemovesOnlyOrphans() {
        touch("dl-1.part"); touch("dl-1.seg0"); touch("dl-1.segs")
        touch("dl-2.part"); touch("dl-2.seg4"); touch("dl-2.segs"); touch("dl-2.segs.tmp"); touch("dl-2.merge")
        touch("dl-3.part")
        touch("old.part")
        touch("photo.jpg"); touch("dl-9.txt")
        TempFiles.sweep(tmp.root, keepIds = setOf(1L), keepPartNames = setOf("dl-1.part"))
        assertEquals(listOf("dl-1.part", "dl-1.seg0", "dl-1.segs", "dl-9.txt", "photo.jpg"), names())
    }

    @Test fun sweepIgnoresFoldersAndAMissingDirectory() {
        File(tmp.root, "dl-5.seg0").mkdir()
        TempFiles.sweep(tmp.root, emptySet(), emptySet())
        assertTrue(File(tmp.root, "dl-5.seg0").isDirectory)
        TempFiles.sweep(File(tmp.root, "nope"), emptySet(), emptySet())
    }
}
