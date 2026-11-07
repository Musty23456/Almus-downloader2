package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLocationTest {
    private fun sub(layout: SaveLayout, name: String, mime: String? = null) =
        SaveFolders.subfolderFor(layout, name, mime)

    @Test fun singleLayoutKeepsEverythingInTheMainFolder() {
        listOf("clip.mp4", "song.mp3", "a.pdf", "a.zip").forEach {
            assertNull(it, sub(SaveLayout.Single, it))
        }
    }

    @Test fun byMediaLayoutSortsVideoAndAudio() {
        assertEquals("Video", sub(SaveLayout.ByMedia, "clip.mp4", "video/mp4"))
        assertEquals("Video", sub(SaveLayout.ByMedia, "clip.webm"))
        assertEquals("Audio", sub(SaveLayout.ByMedia, "song.mp3", "audio/mpeg"))
        assertEquals("Audio", sub(SaveLayout.ByMedia, "track.m4a"))
    }

    @Test fun byMediaLayoutLeavesOtherFilesInTheMainFolder() {
        assertNull(sub(SaveLayout.ByMedia, "a.pdf", "application/pdf"))
        assertNull(sub(SaveLayout.ByMedia, "a.zip"))
        assertNull(sub(SaveLayout.ByMedia, "page.mp4", "text/html"))
        assertNull(sub(SaveLayout.ByMedia, "noextension"))
    }

    @Test fun theServerTypeDecidesWhenTheNameHasNoExtension() {
        assertEquals("Video", sub(SaveLayout.ByMedia, "download", "video/webm"))
        assertEquals("Audio", sub(SaveLayout.ByMedia, "download", "audio/ogg"))
        assertEquals("Video", sub(SaveLayout.ByMedia, "clip.mp4", "application/octet-stream"))
    }

    @Test fun folderNamesAreFixedPlainNames() {
        listOf(SaveFolders.VIDEO, SaveFolders.AUDIO).forEach { name ->
            assertTrue(name, name.all { it.isLetter() })
        }
    }

    @Test fun relativePathAddsTheFolderOnlyWhenThereIsOne() {
        assertEquals("clip.mp4", SaveFolders.relativePath(null, "clip.mp4"))
        assertEquals("Video/clip.mp4", SaveFolders.relativePath("Video", "clip.mp4"))
    }

    @Test fun displayPathNamesTheRealFolder() {
        assertEquals("Download/ALMUS", SaveFolders.displayPath(StorageTarget.PublicDownloads, null))
        assertEquals("Download/ALMUS/Audio", SaveFolders.displayPath(StorageTarget.PublicDownloads, "Audio"))
        assertEquals("App folder/Video", SaveFolders.displayPath(StorageTarget.AppPrivate, "Video"))
    }

    @Test fun fixedSettingsHoldTheirValue() {
        val settings = FixedSaveSettings()
        assertEquals(SaveLayout.Single, settings.layout)
        settings.layout = SaveLayout.ByMedia
        assertEquals(SaveLayout.ByMedia, settings.layout)
    }
}
