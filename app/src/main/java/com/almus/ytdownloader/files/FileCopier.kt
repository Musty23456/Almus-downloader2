package com.almus.ytdownloader.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed interface CopyResult {
    data class Done(val bytes: Long) : CopyResult

    /** [bytes] is how much had been written when it stopped. The caller removes the partial copy. */
    data class Failed(val message: String, val bytes: Long) : CopyResult
}

/**
 * Copies a file byte for byte. It never changes, re-encodes or re-packs anything, so the copy is the
 * original file in another place. Pure JVM streams, unit tested. It does not close the streams.
 */
object FileCopier {
    const val BUFFER_SIZE = 64 * 1024

    /** When [expectedBytes] is not negative, a copy of a different length counts as failed. */
    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long = -1L): CopyResult {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
        } catch (e: IOException) {
            return CopyResult.Failed(e.message ?: "The copy was interrupted.", total)
        }
        if (expectedBytes >= 0 && total != expectedBytes) {
            return CopyResult.Failed(
                "The copy is $total bytes but the original is $expectedBytes bytes, so it was discarded.",
                total,
            )
        }
        return CopyResult.Done(total)
    }
}
