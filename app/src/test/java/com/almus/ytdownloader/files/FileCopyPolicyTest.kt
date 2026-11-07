package com.almus.ytdownloader.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCopyPolicyTest {
    private fun entry(
        status: FileStatus = FileStatus.Available,
        source: FileSource = FileSource.Download(1, 5),
        name: String = "clip.mp4",
        kind: FileKind = FileKind.Videos,
    ) = FileEntry(
        key = "k",
        source = source,
        name = name,
        sizeBytes = 10,
        modifiedMs = 1,
        mimeType = null,
        kind = kind,
        status = status,
    )

    @Test fun availableFilesCanBeCopiedWhereverTheyCameFrom() {
        assertEquals(true, FileActionPolicy.canSaveCopy(entry()))
        assertEquals(true, FileActionPolicy.canSaveCopy(entry(source = FileSource.Folder("content://x", canDelete = false))))
        assertEquals(true, FileActionPolicy.canSaveCopy(entry(name = "a.apk", kind = FileKind.Apks)))
    }

    @Test fun missingOrUnreadableFilesCannotBeCopied() {
        listOf(FileStatus.Missing, FileStatus.NoAccess, FileStatus.Error).forEach {
            assertEquals(it.name, false, FileActionPolicy.canSaveCopy(entry(status = it)))
        }
    }
}
