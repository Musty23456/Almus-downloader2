package com.almus.ytdownloader.files

import com.almus.ytdownloader.data.DownloadEntity
import com.almus.ytdownloader.download.DownloadState
import com.almus.ytdownloader.download.entityOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProbe(
    private val results: Map<Long, ProbeResult> = emptyMap(),
    private val failures: Map<Long, Exception> = emptyMap(),
) : FileProbe {
    override fun probe(entity: DownloadEntity): ProbeResult {
        failures[entity.id]?.let { throw it }
        return results[entity.id] ?: ProbeResult.Missing
    }
}

private class FakeFolders(
    var listing: FolderListing = FolderListing.Files("Download/ALMUS", emptyList()),
    var existing: Set<String> = emptySet(),
    var listFailure: Exception? = null,
) : FolderLister {
    override fun list(treeUri: String): FolderListing {
        listFailure?.let { throw it }
        return listing
    }

    override fun exists(documentUri: String) = documentUri in existing

    override fun delete(documentUri: String) = documentUri in existing
}

class FileLibraryTest {
    private fun record(id: Long, name: String, state: DownloadState = DownloadState.Completed) =
        entityOf(id = id, name = name, state = state, systemId = 100 + id).copy(
            url = "https://example.com/$name",
            mimeType = null,
            totalBytes = 4_000,
            updatedAt = 777,
        )

    private fun library(
        probe: FileProbe = FakeProbe(),
        folders: FakeFolders = FakeFolders(),
    ) = FileLibrary(probe, folders)

    private fun available(size: Long = 2_048, modified: Long = 5_000, mime: String? = null) =
        ProbeResult.Available(size, modified, mime)

    // ---- downloads ----

    @Test fun onlyCompletedDownloadsAreListed() {
        val lib = library(FakeProbe(mapOf(1L to available(), 2L to available(), 3L to available())))
        val snap = lib.scan(
            listOf(
                record(1, "done.pdf"),
                record(2, "running.zip", DownloadState.Running),
                record(3, "failed.zip", DownloadState.Failed),
            ),
            null,
        )
        assertEquals(listOf("done.pdf"), snap.entries.map { it.name })
        assertEquals(FolderState.None, snap.folder)
    }

    @Test fun availableFileTakesSizeDateAndTypeFromTheRealFile() {
        val lib = library(FakeProbe(mapOf(1L to available(size = 2_048, modified = 5_000, mime = "application/pdf"))))
        val e = lib.scan(listOf(record(1, "report.pdf")), null).entries.single()
        assertEquals(FileStatus.Available, e.status)
        assertEquals(2_048L, e.sizeBytes)
        assertEquals(5_000L, e.modifiedMs)
        assertEquals("application/pdf", e.mimeType)
        assertEquals(FileKind.Documents, e.kind)
        assertEquals("d:1", e.key)
        assertEquals(FileSource.Download(1, 101), e.source)
        assertTrue(e.isInHistory)
    }

    @Test fun unknownSizeAndDateFallBackToTheRecord() {
        val lib = library(FakeProbe(mapOf(1L to available(size = -1, modified = 0))))
        val e = lib.scan(listOf(record(1, "a.zip")), null).entries.single()
        assertEquals(4_000L, e.sizeBytes)
        assertEquals(777L, e.modifiedMs)
    }

    @Test fun genericTypesAreIgnoredInFavourOfTheRecordThenTheExtension() {
        val withRecordMime = record(1, "song").copy(mimeType = "audio/mpeg")
        val lib = library(FakeProbe(mapOf(1L to available(mime = "application/octet-stream"), 2L to available(mime = "application/octet-stream"))))
        assertEquals("audio/mpeg", lib.scan(listOf(withRecordMime), null).entries.single().mimeType)
        assertEquals("video/mp4", lib.scan(listOf(record(2, "clip.mp4")), null).entries.single().mimeType)
    }

    @Test fun fileWithNoKnownTypeHasNoMimeType() {
        val lib = library(FakeProbe(mapOf(1L to available())))
        assertNull(lib.scan(listOf(record(1, "data.bin")), null).entries.single().mimeType)
    }

    // ---- files moved or deleted outside the app, permission problems ----

    @Test fun fileDeletedOutsideTheAppIsKeptAsMissingWithRecordedInfo() {
        val lib = library(FakeProbe(mapOf(1L to ProbeResult.Missing)))
        val e = lib.scan(listOf(record(1, "gone.pdf")), null).entries.single()
        assertEquals(FileStatus.Missing, e.status)
        assertEquals(4_000L, e.sizeBytes)
        assertEquals(777L, e.modifiedMs)
        assertEquals("gone.pdf", e.name)
    }

    @Test fun revokedAccessIsReportedAsNoAccess() {
        val lib = library(FakeProbe(mapOf(1L to ProbeResult.NoAccess)))
        assertEquals(FileStatus.NoAccess, lib.scan(listOf(record(1, "a.pdf")), null).entries.single().status)
    }

    @Test fun aProbeThatThrowsNeverBreaksTheWholeScan() {
        val lib = library(
            FakeProbe(
                results = mapOf(3L to available()),
                failures = mapOf(1L to SecurityException("denied"), 2L to IllegalStateException("boom")),
            ),
        )
        val entries = lib.scan(listOf(record(1, "a.pdf"), record(2, "b.pdf"), record(3, "c.pdf")), null).entries.associateBy { it.name }
        assertEquals(FileStatus.NoAccess, entries.getValue("a.pdf").status)
        assertEquals(FileStatus.Error, entries.getValue("b.pdf").status)
        assertEquals(FileStatus.Available, entries.getValue("c.pdf").status)
    }

    // ---- the chosen folder (Storage Access Framework) ----

    private fun folderFile(name: String, uri: String = "content://tree/doc/$name", mime: String? = null, canDelete: Boolean = true) =
        FolderFile(uri, name, sizeBytes = 10, modifiedMs = 9_000, mimeType = mime, canDelete = canDelete)

    @Test fun folderFilesAreAddedAndMarkedAsNotInHistory() {
        val folders = FakeFolders(FolderListing.Files("Download/ALMUS", listOf(folderFile("old.mp3", mime = "audio/mpeg"), folderFile("note.txt", canDelete = false))))
        val snap = library(folders = folders).scan(emptyList(), "content://tree")
        assertEquals(FolderState.Granted("Download/ALMUS", 2), snap.folder)
        val mp3 = snap.entries.first { it.name == "old.mp3" }
        assertEquals("s:content://tree/doc/old.mp3", mp3.key)
        assertEquals(FileSource.Folder("content://tree/doc/old.mp3", true), mp3.source)
        assertEquals(FileKind.Music, mp3.kind)
        assertEquals(FileStatus.Available, mp3.status)
        assertFalse(mp3.isInHistory)
        assertEquals(FileSource.Folder("content://tree/doc/note.txt", false), snap.entries.first { it.name == "note.txt" }.source)
    }

    @Test fun aFolderFileWithTheSameNameAsAnAvailableDownloadIsNotListedTwice() {
        val folders = FakeFolders(FolderListing.Files("f", listOf(folderFile("report.pdf"), folderFile("other.pdf"))))
        val snap = library(FakeProbe(mapOf(1L to available())), folders).scan(listOf(record(1, "report.pdf")), "content://tree")
        assertEquals(listOf("report.pdf", "other.pdf"), snap.entries.map { it.name })
        assertTrue(snap.entries.first().isInHistory)
    }

    @Test fun aMissingDownloadDoesNotHideAFolderFileWithTheSameName() {
        val folders = FakeFolders(FolderListing.Files("f", listOf(folderFile("report.pdf"))))
        val snap = library(FakeProbe(mapOf(1L to ProbeResult.Missing)), folders).scan(listOf(record(1, "report.pdf")), "content://tree")
        assertEquals(2, snap.entries.size)
        assertEquals(setOf(FileStatus.Missing, FileStatus.Available), snap.entries.map { it.status }.toSet())
    }

    @Test fun folderProblemsAreReportedAndDownloadsStillShow() {
        val records = listOf(record(1, "a.pdf"))
        val probe = FakeProbe(mapOf(1L to available()))
        listOf(
            FolderListing.Denied to FolderState.Denied,
            FolderListing.Gone to FolderState.Gone,
            FolderListing.Failed("provider down") to FolderState.Failed("provider down"),
        ).forEach { (listing, expected) ->
            val snap = library(probe, FakeFolders(listing)).scan(records, "content://tree")
            assertEquals(expected, snap.folder)
            assertEquals(listOf("a.pdf"), snap.entries.map { it.name })
        }
    }

    @Test fun anExceptionFromTheFolderListerBecomesAFailedState() {
        val snap = library(FakeProbe(mapOf(1L to available())), FakeFolders(listFailure = IllegalStateException("oops")))
            .scan(listOf(record(1, "a.pdf")), "content://tree")
        assertEquals(FolderState.Failed("oops"), snap.folder)
        assertEquals(1, snap.entries.size)
    }

    @Test fun noFolderChosenMeansNoFolderAccessIsAttempted() {
        val folders = FakeFolders(listFailure = IllegalStateException("must not be called"))
        val snap = library(folders = folders).scan(emptyList(), null)
        assertEquals(FolderState.None, snap.folder)
    }

    // ---- re-checking right before an action ----

    @Test fun isStillThereChecksTheRealFileAgain() {
        val entity = record(1, "a.pdf")
        val entry = library(FakeProbe(mapOf(1L to available()))).scan(listOf(entity), null).entries.single()

        assertTrue(library(FakeProbe(mapOf(1L to available()))).isStillThere(entry, entity))
        assertFalse("deleted outside the app since the list was built", library(FakeProbe(mapOf(1L to ProbeResult.Missing))).isStillThere(entry, entity))
        assertFalse("record removed meanwhile", library(FakeProbe(mapOf(1L to available()))).isStillThere(entry, null))
        assertFalse(library(FakeProbe(failures = mapOf(1L to SecurityException("no")))).isStillThere(entry, entity))
    }

    @Test fun isStillThereForFolderFilesAsksTheFolder() {
        val entry = FileEntry("s:u", FileSource.Folder("content://doc/1", true), "a", 1, 1, null, FileKind.Others, FileStatus.Available)
        assertTrue(library(folders = FakeFolders(existing = setOf("content://doc/1"))).isStillThere(entry, null))
        assertFalse(library(folders = FakeFolders(existing = emptySet())).isStillThere(entry, null))
    }

    // ---- type picking ----

    @Test fun pickMime() {
        assertEquals("application/pdf", FileLibrary.pickMime("application/octet-stream", "application/pdf", "x"))
        assertEquals("audio/mpeg", FileLibrary.pickMime(null, null, "a.mp3"))
        assertNull(FileLibrary.pickMime(null, null, "a.bin"))
        assertEquals("text/html", FileLibrary.pickMime("text/html; charset=utf-8", null, "page"))
    }
}
