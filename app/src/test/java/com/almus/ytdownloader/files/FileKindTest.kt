package com.almus.ytdownloader.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindTest {
    private fun kind(name: String, mime: String? = null) = FileKind.classify(name, mime)

    @Test fun videos() {
        assertEquals(FileKind.Videos, kind("movie.mp4"))
        assertEquals(FileKind.Videos, kind("movie.MKV"))
        assertEquals(FileKind.Videos, kind("clip.3gp"))
        assertEquals(FileKind.Videos, kind("download", "video/mp4"))
    }

    @Test fun music() {
        assertEquals(FileKind.Music, kind("song.mp3"))
        assertEquals(FileKind.Music, kind("song.flac"))
        assertEquals(FileKind.Music, kind("song.mp3", "application/octet-stream"))
    }

    @Test fun images() {
        assertEquals(FileKind.Images, kind("pic.jpg"))
        assertEquals(FileKind.Images, kind("pic.PNG"))
        assertEquals(FileKind.Images, kind("logo.svg"))
    }

    @Test fun documents() {
        listOf("report.pdf", "notes.txt", "sheet.xlsx", "slides.pptx", "letter.docx", "data.json", "book.epub", "table.csv")
            .forEach { assertEquals(it, FileKind.Documents, kind(it)) }
    }

    @Test fun apksAreRecognisedByExtensionOrType() {
        assertEquals(FileKind.Apks, kind("app.apk"))
        assertEquals(FileKind.Apks, kind("APP.APK"))
        assertEquals(FileKind.Apks, kind("app", "application/vnd.android.package-archive"))
        assertEquals("extension wins over a misleading type", FileKind.Apks, kind("app.apk", "text/plain"))
    }

    @Test fun archives() {
        listOf("a.zip", "a.rar", "a.7z", "a.gz", "a.tar", "a.tgz", "a.bz2", "a.xz", "backup.tar.gz")
            .forEach { assertEquals(it, FileKind.Archives, kind(it)) }
        assertEquals(FileKind.Archives, kind("download", "application/zip"))
    }

    @Test fun extensionBeatsAMisleadingServerType() {
        assertEquals("a .docx served as zip is still a document", FileKind.Documents, kind("a.docx", "application/zip"))
        assertEquals(FileKind.Images, kind("pic.jpg", "text/html"))
        assertEquals(FileKind.Archives, kind("archive.zip", "text/plain"))
    }

    @Test fun everythingElseIsOther() {
        assertEquals(FileKind.Others, kind("data.bin"))
        assertEquals(FileKind.Others, kind("noextension"))
        assertEquals(FileKind.Others, kind("x.unknown", "application/octet-stream"))
        assertEquals(FileKind.Others, kind("download", null))
    }

    @Test fun allSevenRequestedCategoriesExistInOrder() {
        assertEquals(
            listOf("Videos", "Music", "Images", "Documents", "Apks", "Archives", "Others"),
            FileKind.entries.map { it.name },
        )
        assertTrue(FileKind.entries.size == 7)
    }

    @Test fun folderLabels() {
        assertEquals("Download/ALMUS", FolderLabel.of("primary:Download/ALMUS"))
        assertEquals("Movies", FolderLabel.of("1234-ABCD:Movies/"))
        assertEquals("Storage root", FolderLabel.of("primary:"))
        assertEquals("raw", FolderLabel.of("raw"))
    }

    @Test fun naturalOrderSortsNumbersByValueAndIgnoresCase() {
        assertTrue(NaturalOrder.compare("file2", "file10") < 0)
        assertTrue(NaturalOrder.compare("img9.png", "img10.png") < 0)
        assertTrue(NaturalOrder.compare("b", "a") > 0)
        assertTrue(NaturalOrder.compare("a", "a1") < 0)
        assertEquals(0, NaturalOrder.compare("File1", "file1"))
        assertEquals(0, NaturalOrder.compare("a01", "a1"))
        assertEquals(listOf("a1", "a2", "a10", "a20"), listOf("a10", "a2", "a20", "a1").sortedWith(NaturalOrder))
    }
}
