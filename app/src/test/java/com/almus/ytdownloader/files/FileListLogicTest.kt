package com.almus.ytdownloader.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListLogicTest {
    private var counter = 0L

    private fun entry(
        name: String,
        size: Long = 100,
        modified: Long = 1000,
        mime: String? = null,
        status: FileStatus = FileStatus.Available,
        source: FileSource = FileSource.Download(++counter, 500 + counter),
    ) = FileEntry(
        key = "k${counter}-$name",
        source = source,
        name = name,
        sizeBytes = size,
        modifiedMs = modified,
        mimeType = mime,
        kind = FileKind.classify(name, mime),
        status = status,
    )

    private fun names(list: List<FileEntry>) = list.map { it.name }

    // ---- search ----

    @Test fun blankSearchKeepsEverything() {
        val all = listOf(entry("a.pdf"), entry("b.mp3"))
        assertEquals(all, FileListLogic.search(all, ""))
        assertEquals(all, FileListLogic.search(all, "   "))
    }

    @Test fun searchIsCaseInsensitiveAndTrimmed() {
        val all = listOf(entry("Holiday Photo.JPG"), entry("report.pdf"))
        assertEquals(listOf("Holiday Photo.JPG"), names(FileListLogic.search(all, "  photo ")))
        assertEquals(listOf("report.pdf"), names(FileListLogic.search(all, "PDF")))
    }

    @Test fun searchAlsoMatchesTheMimeType() {
        val all = listOf(entry("a", mime = "audio/mpeg"), entry("b", mime = "video/mp4"))
        assertEquals(listOf("a"), names(FileListLogic.search(all, "audio")))
    }

    @Test fun searchWithNoMatchGivesEmptyList() {
        assertTrue(FileListLogic.search(listOf(entry("a.pdf")), "zzz").isEmpty())
    }

    // ---- category ----

    @Test fun categoryFilter() {
        val all = listOf(entry("a.pdf"), entry("b.mp3"), entry("c.zip"), entry("d.apk"))
        assertEquals(all, FileListLogic.byKind(all, null))
        assertEquals(listOf("b.mp3"), names(FileListLogic.byKind(all, FileKind.Music)))
        assertEquals(listOf("d.apk"), names(FileListLogic.byKind(all, FileKind.Apks)))
        assertEquals(listOf("c.zip"), names(FileListLogic.byKind(all, FileKind.Archives)))
    }

    @Test fun countsIncludeEmptyCategories() {
        val counts = FileListLogic.counts(listOf(entry("a.pdf"), entry("b.pdf"), entry("c.mp4")))
        assertEquals(FileKind.entries.toSet(), counts.keys)
        assertEquals(2, counts[FileKind.Documents])
        assertEquals(1, counts[FileKind.Videos])
        assertEquals(0, counts[FileKind.Archives])
    }

    // ---- sorting ----

    @Test fun sortByNameIsNaturalAndCaseInsensitive() {
        val all = listOf(entry("file10.zip"), entry("file2.zip"), entry("File1.zip"))
        assertEquals(
            listOf("File1.zip", "file2.zip", "file10.zip"),
            names(FileListLogic.sort(all, SortSpec(SortField.Name, true))),
        )
        assertEquals(
            listOf("file10.zip", "file2.zip", "File1.zip"),
            names(FileListLogic.sort(all, SortSpec(SortField.Name, false))),
        )
    }

    @Test fun sortByDateNewestFirstAndUnknownDatesAlwaysLast() {
        val all = listOf(entry("a", modified = 100), entry("b", modified = 300), entry("c", modified = 0), entry("d", modified = 200))
        assertEquals(listOf("b", "d", "a", "c"), names(FileListLogic.sort(all, SortSpec(SortField.Date, false))))
        assertEquals(listOf("a", "d", "b", "c"), names(FileListLogic.sort(all, SortSpec(SortField.Date, true))))
    }

    @Test fun sortBySizeAndUnknownSizesAlwaysLast() {
        val all = listOf(entry("a", size = 5), entry("b", size = -1), entry("c", size = 50), entry("d", size = 20))
        assertEquals(listOf("c", "d", "a", "b"), names(FileListLogic.sort(all, SortSpec(SortField.Size, false))))
        assertEquals(listOf("a", "d", "c", "b"), names(FileListLogic.sort(all, SortSpec(SortField.Size, true))))
    }

    @Test fun sortByTypeGroupsByCategoryThenExtensionThenName() {
        val all = listOf(entry("b.mp3"), entry("a.mp4"), entry("c.pdf"), entry("d.mp4"), entry("e.jpg"), entry("f.mkv"))
        assertEquals(
            listOf("f.mkv", "a.mp4", "d.mp4", "b.mp3", "e.jpg", "c.pdf"),
            names(FileListLogic.sort(all, SortSpec(SortField.Type, true))),
        )
        // Descending reverses the category and extension order; ties inside stay in name order.
        assertEquals(
            listOf("c.pdf", "e.jpg", "b.mp3", "a.mp4", "d.mp4", "f.mkv"),
            names(FileListLogic.sort(all, SortSpec(SortField.Type, false))),
        )
    }

    @Test fun equalValuesFallBackToNameSoOrderIsDeterministic() {
        val all = listOf(entry("b", size = 10), entry("a", size = 10), entry("c", size = 10))
        assertEquals(listOf("a", "b", "c"), names(FileListLogic.sort(all, SortSpec(SortField.Size, true))))
        assertEquals(listOf("a", "b", "c"), names(FileListLogic.sort(all, SortSpec(SortField.Size, false))))
    }

    @Test fun sortingDoesNotLoseOrDuplicateEntries() {
        val all = (1..20).map { entry("f$it.bin", size = (it % 4).toLong(), modified = (it % 3).toLong()) }
        SortField.entries.forEach { field ->
            listOf(true, false).forEach { asc ->
                assertEquals(all.map { it.key }.toSet(), FileListLogic.sort(all, SortSpec(field, asc)).map { it.key }.toSet())
                assertEquals(all.size, FileListLogic.sort(all, SortSpec(field, asc)).size)
            }
        }
    }

    // ---- missing records ----

    @Test fun missingDownloadIdsOnlyContainMissingDownloadRecords() {
        val missing = entry("gone.pdf", status = FileStatus.Missing)
        val noAccess = entry("locked.pdf", status = FileStatus.NoAccess)
        val ok = entry("ok.pdf")
        val folderMissing = entry("x.pdf", status = FileStatus.Missing, source = FileSource.Folder("content://x", true))
        val ids = FileListLogic.missingDownloadIds(listOf(missing, noAccess, ok, folderMissing))
        assertEquals(listOf((missing.source as FileSource.Download).downloadId), ids)
    }

    // ---- the combined view ----

    private fun snapshot(vararg entries: FileEntry, folder: FolderState = FolderState.None) =
        FileLibrary.Snapshot(entries.toList(), folder)

    @Test fun noSnapshotMeansEmptyViewNotAnError() {
        val view = FilesView.build(null, "", null, SortSpec(SortField.Date, false))
        assertTrue(view.libraryEmpty)
        assertTrue(view.visible.isEmpty())
    }

    @Test fun viewAppliesSearchCategoryAndSort() {
        val snap = snapshot(entry("b.mp3"), entry("a.mp3"), entry("c.mp4"))
        val view = FilesView.build(snap, "", FileKind.Music, SortSpec(SortField.Name, true))
        assertEquals(listOf("a.mp3", "b.mp3"), names(view.visible))
        assertEquals("counts ignore the category filter so the chips stay useful", 1, view.counts[FileKind.Videos])
        assertEquals(3, view.libraryCount)
    }

    @Test fun categoryCountsFollowTheSearch() {
        val snap = snapshot(entry("holiday.mp4"), entry("holiday.jpg"), entry("other.mp4"))
        val view = FilesView.build(snap, "holiday", null, SortSpec(SortField.Name, true))
        assertEquals(1, view.counts[FileKind.Videos])
        assertEquals(1, view.counts[FileKind.Images])
    }

    @Test fun noMatchesIsDifferentFromAnEmptyLibrary() {
        val snap = snapshot(entry("a.pdf"))
        val none = FilesView.build(snap, "zzz", null, SortSpec(SortField.Name, true))
        assertTrue(none.noMatches)
        assertFalse(none.libraryEmpty)
        val empty = FilesView.build(snapshot(), "zzz", null, SortSpec(SortField.Name, true))
        assertTrue(empty.libraryEmpty)
        assertFalse(empty.noMatches)
    }

    @Test fun storageDeniedOnlyWhenEveryFileIsInaccessible() {
        val allDenied = snapshot(entry("a", status = FileStatus.NoAccess), entry("b", status = FileStatus.NoAccess))
        assertEquals(AccessProblem.StorageDenied, FilesView.build(allDenied, "", null, SortSpec(SortField.Name, true)).access)
        val mixed = snapshot(entry("a", status = FileStatus.NoAccess), entry("b"))
        assertEquals(AccessProblem.None, FilesView.build(mixed, "", null, SortSpec(SortField.Name, true)).access)
        assertEquals(AccessProblem.None, FilesView.build(snapshot(), "", null, SortSpec(SortField.Name, true)).access)
    }

    @Test fun lostFolderPermissionIsReported() {
        val snap = snapshot(entry("a"), folder = FolderState.Denied)
        assertEquals(AccessProblem.FolderDenied, FilesView.build(snap, "", null, SortSpec(SortField.Name, true)).access)
    }

    @Test fun missingFilesAreCountedAndStillListed() {
        val snap = snapshot(entry("gone.pdf", status = FileStatus.Missing), entry("ok.pdf"))
        val view = FilesView.build(snap, "", null, SortSpec(SortField.Name, true))
        assertEquals(1, view.missingCount)
        assertEquals(2, view.visible.size)
    }

    // ---- which actions are allowed ----

    @Test fun availableDownloadAllowsEverything() {
        val e = entry("a.pdf")
        assertTrue(FileActionPolicy.canOpen(e))
        assertTrue(FileActionPolicy.canShare(e))
        assertTrue(FileActionPolicy.canDelete(e))
        assertTrue(FileActionPolicy.canRemoveFromList(e))
        assertFalse(FileActionPolicy.opensInSystemDownloads(e))
    }

    @Test fun apksAreNeverOpenedByTheApp() {
        val apk = entry("tool.apk")
        assertTrue(FileActionPolicy.opensInSystemDownloads(apk))
        assertTrue("sharing is the user's own explicit choice", FileActionPolicy.canShare(apk))
    }

    @Test fun missingOrInaccessibleFilesOnlyOfferRemovingTheRecord() {
        listOf(FileStatus.Missing, FileStatus.NoAccess, FileStatus.Error).forEach { status ->
            val e = entry("a.pdf", status = status)
            assertFalse("$status", FileActionPolicy.canOpen(e))
            assertFalse("$status", FileActionPolicy.canShare(e))
            assertFalse("$status", FileActionPolicy.canDelete(e))
            assertTrue("$status", FileActionPolicy.canRemoveFromList(e))
        }
    }

    @Test fun downloadWithoutSystemIdCannotBeDeletedFromHere() {
        val e = entry("a.pdf", source = FileSource.Download(1, null))
        assertFalse(FileActionPolicy.canDelete(e))
    }

    @Test fun folderFilesFollowTheFolderPermissionAndHaveNoHistoryRecord() {
        val deletable = entry("a.pdf", source = FileSource.Folder("content://doc/1", canDelete = true))
        val readOnly = entry("b.pdf", source = FileSource.Folder("content://doc/2", canDelete = false))
        assertTrue(FileActionPolicy.canDelete(deletable))
        assertFalse(FileActionPolicy.canDelete(readOnly))
        assertFalse(FileActionPolicy.canRemoveFromList(deletable))
        assertTrue(FileActionPolicy.canOpen(readOnly))
        assertNull((deletable.source as? FileSource.Download))
    }
}
