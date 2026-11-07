package com.almus.ytdownloader.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCopierTest {
    private fun randomBytes(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }

    @Test fun copyIsByteForByteIdenticalAcrossSeveralBuffers() {
        val data = randomBytes(FileCopier.BUFFER_SIZE * 3 + 123)
        val out = ByteArrayOutputStream()
        val result = FileCopier.copy(ByteArrayInputStream(data), out, expectedBytes = data.size.toLong())
        assertEquals(CopyResult.Done(data.size.toLong()), result)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun emptyFileCopiesAsEmpty() {
        val out = ByteArrayOutputStream()
        assertEquals(CopyResult.Done(0), FileCopier.copy(ByteArrayInputStream(ByteArray(0)), out, 0))
        assertEquals(0, out.size())
    }

    @Test fun unknownExpectedSizeIsNotChecked() {
        val data = randomBytes(1000)
        assertEquals(CopyResult.Done(1000), FileCopier.copy(ByteArrayInputStream(data), ByteArrayOutputStream()))
    }

    @Test fun aCopyOfTheWrongLengthFails() {
        val result = FileCopier.copy(ByteArrayInputStream(randomBytes(10)), ByteArrayOutputStream(), expectedBytes = 11)
        assertTrue("was $result", result is CopyResult.Failed)
        val failed = result as CopyResult.Failed
        assertEquals(10L, failed.bytes)
        assertTrue(failed.message, failed.message.contains("10") && failed.message.contains("11"))
    }

    @Test fun readErrorStopsTheCopyAndReportsHowFarItGot() {
        val broken = object : InputStream() {
            private var served = 0

            override fun read(): Int {
                if (served >= 100_000) throw IOException("disk error")
                served++
                return 7
            }
        }
        val result = FileCopier.copy(broken, ByteArrayOutputStream())
        assertEquals(CopyResult.Failed("disk error", 100_000L), result)
    }

    @Test fun writeErrorStopsTheCopy() {
        val full = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("no space left")
            }
        }
        val result = FileCopier.copy(ByteArrayInputStream(randomBytes(500)), full)
        assertEquals(CopyResult.Failed("no space left", 0L), result)
    }
}
