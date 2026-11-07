package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameResolverTest {
    // ---- Content-Disposition ----

    @Test fun readsQuotedFilename() {
        assertEquals(
            "report 2024.pdf",
            FileNameResolver.fromContentDisposition("attachment; filename=\"report 2024.pdf\""),
        )
    }

    @Test fun readsUnquotedFilename() {
        assertEquals("a.zip", FileNameResolver.fromContentDisposition("attachment; filename=a.zip; size=10"))
    }

    @Test fun prefersExtendedFilenameAndDecodesIt() {
        val header = "attachment; filename=\"fallback.txt\"; filename*=UTF-8''na%C3%AFve%20file.txt"
        assertEquals("naïve file.txt", FileNameResolver.fromContentDisposition(header))
    }

    @Test fun plusInHeaderValueIsLiteral() {
        assertEquals("a+b.txt", FileNameResolver.fromContentDisposition("attachment; filename*=UTF-8''a+b.txt"))
    }

    @Test fun ignoresHeadersWithoutFilename() {
        assertNull(FileNameResolver.fromContentDisposition("inline"))
        assertNull(FileNameResolver.fromContentDisposition(null))
        assertNull(FileNameResolver.fromContentDisposition(""))
    }

    // ---- URL ----

    @Test fun takesLastPathSegmentFromUrl() {
        assertEquals("song.mp3", FileNameResolver.fromUrl("https://example.com/music/song.mp3?x=1#y"))
        assertEquals("my file.pdf", FileNameResolver.fromUrl("https://example.com/my%20file.pdf"))
    }

    @Test fun noFileNameInBareHostOrTrailingSlash() {
        assertNull(FileNameResolver.fromUrl("https://example.com"))
        assertNull(FileNameResolver.fromUrl("https://example.com/"))
        assertNull(FileNameResolver.fromUrl("https://example.com/folder/"))
    }

    // ---- sanitize: path traversal and unsafe characters ----

    @Test fun stripsPathTraversal() {
        assertEquals("passwd", FileNameResolver.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", FileNameResolver.sanitize("..\\..\\windows\\evil.sh"))
        assertEquals("x.txt", FileNameResolver.sanitize("/absolute/path/x.txt"))
    }

    @Test fun percentEncodedTraversalInUrlEndsUpHarmless() {
        val name = FileNameResolver.resolve(null, null, "https://example.com/..%2F..%2Fsecret.txt")
        assertEquals("secret.txt", name)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }

    @Test fun rejectsNamesThatAreOnlyDots() {
        assertNull(FileNameResolver.sanitize(".."))
        assertNull(FileNameResolver.sanitize("."))
        assertNull(FileNameResolver.sanitize("..."))
        assertNull(FileNameResolver.sanitize("   "))
        assertNull(FileNameResolver.sanitize(null))
    }

    @Test fun removesControlCharactersAndReservedCharacters() {
        assertEquals("abc.txt", FileNameResolver.sanitize("a\u0000b\u0007c.txt"))
        // Forbidden characters are removed, not replaced.
        assertEquals("abcdef.txt", FileNameResolver.sanitize("a<b>c:d\"e|f?.txt"))
    }

    @Test fun removesBidiOverrideTricks() {
        // U+202E would make "invoice\u202Egpj.exe" display as "invoiceexe.jpg"
        assertEquals("invoicegpj.exe", FileNameResolver.sanitize("invoice\u202Egpj.exe"))
    }

    @Test fun trimsLeadingDotsAndTrailingSpaces() {
        assertEquals("hidden.txt", FileNameResolver.sanitize(".hidden.txt"))
        assertEquals("file.txt", FileNameResolver.sanitize("  file.txt . "))
    }

    @Test fun collapsesWhitespace() {
        assertEquals("a b.txt", FileNameResolver.sanitize("a   \t b.txt"))
    }

    @Test fun limitsLengthButKeepsExtension() {
        val raw = "a".repeat(500) + ".pdf"
        val name = FileNameResolver.sanitize(raw)!!
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun limitsLengthForMultiByteCharacters() {
        val name = FileNameResolver.sanitize("é".repeat(300) + ".txt")!!
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".txt"))
    }

    // ---- resolve: order, fallback and extensions ----

    @Test fun headerBeatsUrl() {
        val name = FileNameResolver.resolve(
            contentDisposition = "attachment; filename=\"real-name.pdf\"",
            mimeType = "application/pdf",
            url = "https://example.com/download?id=7",
        )
        assertEquals("real-name.pdf", name)
    }

    @Test fun usesUrlWhenHeaderMissing() {
        assertEquals("photo.jpg", FileNameResolver.resolve(null, "image/jpeg", "https://example.com/img/photo.jpg"))
    }

    @Test fun fallsBackWhenNothingUsable() {
        assertEquals("download", FileNameResolver.resolve(null, null, "https://example.com/"))
        assertEquals("download", FileNameResolver.resolve("attachment; filename=\"..\"", "application/octet-stream", "https://example.com"))
    }

    @Test fun fallbackGetsExtensionFromSpecificMimeType() {
        assertEquals("download.pdf", FileNameResolver.resolve(null, "application/pdf", "https://example.com/"))
        assertEquals("get.zip", FileNameResolver.resolve(null, "application/zip", "https://example.com/get"))
        assertEquals("track.mp3", FileNameResolver.resolve(null, "audio/mpeg; charset=binary", "https://example.com/track"))
    }

    @Test fun genericMimeTypeAddsNoExtension() {
        assertEquals("blob", FileNameResolver.resolve(null, "application/octet-stream", "https://example.com/blob"))
    }

    @Test fun existingExtensionIsKept() {
        assertEquals("a.txt", FileNameResolver.ensureExtension("a.txt", "application/pdf"))
    }

    @Test fun commonTypesRoundTrip() {
        listOf("pdf", "zip", "apk", "jpg", "png", "mp3", "mp4", "txt").forEach { ext ->
            val mime = MimeTypes.forExtension(ext)
            assertEquals("mime for $ext", true, mime != null)
            assertEquals(ext, MimeTypes.extensionFor(mime))
        }
    }

    // ---- uniqueness ----

    @Test fun makeUniqueKeepsFreeNames() {
        assertEquals("a.pdf", FileNameResolver.makeUnique("a.pdf", setOf("b.pdf")))
    }

    @Test fun makeUniqueAddsCounterBeforeExtensionIgnoringCase() {
        assertEquals("a (1).pdf", FileNameResolver.makeUnique("a.pdf", setOf("A.PDF")))
        assertEquals("a (2).pdf", FileNameResolver.makeUnique("a.pdf", setOf("a.pdf", "a (1).pdf")))
        assertEquals("noext (1)", FileNameResolver.makeUnique("noext", setOf("noext")))
    }
}
