package com.almus.ytdownloader.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Result of [SegmentMerger.merge]. */
sealed interface MergeResult {
    /** The whole file is in [SegmentMerger]'s target, complete and checked. */
    data object Merged : MergeResult

    /** Segment [index] on disk does not match what was recorded (missing, wrong size, wrong CRC-32). It must be downloaded again. */
    data class Corrupt(val index: Int, val reason: String) : MergeResult

    /** Merging cannot continue and the downloaded data cannot be reused (the merge file was damaged). */
    data class Unrecoverable(val reason: String) : MergeResult
}

/**
 * Puts the finished segments together into one file, safely:
 *
 * - Every segment is checked (size, then CRC-32 while it is copied) BEFORE it becomes part of the result; a damaged
 *   segment is reported, the merge file is cut back to the last good byte, and nothing damaged is ever kept.
 * - Segment 0 becomes the start of the merge file by a rename (no copy). The others are appended in order.
 * - The merge file is flushed in batches, the manifest then records how far the merge got, and only then are the
 *   segment files of that batch deleted. A crash at any point therefore leaves either the segment or its merged copy, never neither,
 *   and a restart continues the merge where it stopped. The disk never needs more than one segment of extra space.
 * - The finished merge file is flushed and renamed to [TempFiles.part] in one atomic step, so a half-merged file can
 *   never be mistaken for a finished download.
 *
 * Blocking; call it from an I/O thread. [isActive] is polled so a pause or cancel interrupts it quickly.
 */
object SegmentMerger {
    private const val BUFFER = 256 * 1024

    /** The merge file is flushed (and finished segments deleted) after this many appended bytes. */
    const val SYNC_EVERY = 32L * 1024 * 1024

    fun merge(
        files: TempFiles,
        manifestIn: SegmentManifest,
        saveManifest: (SegmentManifest) -> Unit,
        isActive: () -> Boolean = { true },
        syncEveryBytes: Long = SYNC_EVERY,
    ): MergeResult {
        var manifest = manifestIn
        if (!manifest.allComplete) return MergeResult.Unrecoverable("Not every segment is complete.")
        val merge = files.merge

        // Where the merge file must stand according to the journal; anything beyond that is an unfinished append.
        if (manifest.mergedCount > 0) {
            if (!merge.isFile || merge.length() < manifest.mergedBytes) {
                return MergeResult.Unrecoverable("The partly merged file is missing or too short.")
            }
            RandomAccessFile(merge, "rw").use { it.setLength(manifest.mergedBytes) }
        } else {
            merge.delete()
            val first = manifest.segments[0]
            val head = files.segment(0)
            if (!head.isFile || head.length() != first.segment.length) return MergeResult.Corrupt(0, "Segment 0 has the wrong size.")
            checkCrc(head, first)?.let { return MergeResult.Corrupt(0, it) }
            SegmentManifestFile.atomicReplace(head, merge)
            manifest = manifest.copy(mergedCount = 1, mergedBytes = first.segment.length)
            saveManifest(manifest)
        }

        // Appended segments are flushed in batches. A segment file is only deleted, and the journal only advanced on disk,
        // after the flush that made its merged copy durable.
        val pendingDelete = ArrayList<File>()
        var unsynced = 0L
        RandomAccessFile(merge, "rw").use { out ->
            fun commit() {
                out.fd.sync()
                saveManifest(manifest)
                pendingDelete.forEach { it.delete() }
                pendingDelete.clear()
                unsynced = 0
            }
            for (k in manifest.mergedCount until manifest.segments.size) {
                val state = manifest.segments[k]
                val file = files.segment(k)
                if (!isActive()) {
                    out.setLength(manifest.mergedBytes)
                    commit()
                    throw MergeInterrupted()
                }
                if (!file.isFile || file.length() != state.segment.length) {
                    out.setLength(manifest.mergedBytes)
                    commit()
                    return MergeResult.Corrupt(k, "Segment $k has the wrong size.")
                }
                val crc = CRC32()
                out.seek(manifest.mergedBytes)
                var interrupted = false
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (!isActive()) {
                            interrupted = true
                            break
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
                if (interrupted) {
                    out.setLength(manifest.mergedBytes)
                    commit()
                    throw MergeInterrupted()
                }
                if (crc.value != state.crc) {
                    out.setLength(manifest.mergedBytes) // cut back to the last good byte
                    commit()
                    return MergeResult.Corrupt(k, "Segment $k does not match its checksum.")
                }
                manifest = manifest.copy(mergedCount = k + 1, mergedBytes = manifest.mergedBytes + state.segment.length)
                pendingDelete += file
                unsynced += state.segment.length
                if (unsynced >= syncEveryBytes) commit()
            }
            commit()
        }

        if (merge.length() != manifest.total) return MergeResult.Unrecoverable("The merged file has the wrong size.")
        SegmentManifestFile.atomicReplace(merge, files.part)
        return MergeResult.Merged
    }

    private fun checkCrc(file: File, state: SegmentState): String? {
        val crc = CRC32()
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            return "Segment ${state.segment.index} cannot be read."
        }
        return if (crc.value == state.crc) null else "Segment ${state.segment.index} does not match its checksum."
    }

    /** Thrown when [isActive] turned false in the middle of a merge. The merge file was already cut back to a consistent state. */
    class MergeInterrupted : RuntimeException("merge interrupted")
}
