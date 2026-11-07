package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkWarningsTest {
    @Test fun plainHttpsFileHasNoWarnings() {
        assertTrue(LinkWarnings.of(linkInfo("a.pdf", 1000, "application/pdf")).isEmpty())
    }

    @Test fun httpIsFlaggedInsecure() {
        assertEquals(listOf(LinkWarning.Insecure), LinkWarnings.of(linkInfo().copy(insecure = true)))
    }

    @Test fun unknownSizeIsFlagged() {
        assertTrue(LinkWarnings.of(linkInfo(length = -1)).contains(LinkWarning.UnknownSize))
    }

    @Test fun largeFileIsFlaggedAtTheThreshold() {
        assertTrue(LinkWarnings.of(linkInfo(length = LinkWarnings.LARGE_FILE_BYTES)).contains(LinkWarning.LargeFile))
        assertTrue(!LinkWarnings.of(linkInfo(length = LinkWarnings.LARGE_FILE_BYTES - 1)).contains(LinkWarning.LargeFile))
    }

    @Test fun apkIsFlaggedByNameOrType() {
        assertTrue(LinkWarnings.of(linkInfo("app.apk", mime = null)).contains(LinkWarning.Apk))
        assertTrue(LinkWarnings.of(linkInfo("app", mime = "application/vnd.android.package-archive")).contains(LinkWarning.Apk))
    }
}
