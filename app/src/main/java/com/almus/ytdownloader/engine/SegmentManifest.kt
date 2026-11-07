package com.almus.ytdownloader.engine

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * What is known about one segment. [doneBytes] bytes from the start of the segment are on disk, flushed, and have
 * the CRC-32 [crc]; nothing beyond that is trusted after a restart.
 */
data class SegmentState(val segment: Segment, val doneBytes: Long, val crc: Long) {
    val complete: Boolean get() = doneBytes == segment.length

    init {
        require(doneBytes in 0..segment.length) { "doneBytes out of range for segment ${segment.index}" }
    }
}

/**
 * The saved plan and progress of a segmented download. It is written next to the segment files so that pause,
 * a crash or a restart continue from the last checkpoint. A manifest that cannot be read back exactly
 * (torn write, edited, wrong version) is treated as absent: the download then starts again, never from guesses.
 */
data class SegmentManifest(
    val total: Long,
    val segmentSize: Long,
    val validator: Validator,
    val segments: List<SegmentState>,
    /** How many leading segments are already merged into the merge file, and how many bytes that file must have. */
    val mergedCount: Int = 0,
    val mergedBytes: Long = 0,
) {
    val downloadedBytes: Long
        get() = if (mergedCount > 0) mergedBytes + segments.drop(mergedCount).sumOf { it.doneBytes } else segments.sumOf { it.doneBytes }

    val allComplete: Boolean get() = segments.all { it.complete }

    fun withSegment(state: SegmentState): SegmentManifest =
        copy(segments = segments.toMutableList().also { it[state.segment.index] = state })

    /** True when this manifest describes the file the download record points to. */
    fun compatibleWith(totalBytes: Long, etag: String?, lastModified: String?): Boolean {
        if (totalBytes > 0 && totalBytes != total) return false
        if (etag != null && validator.etag != etag) return false
        if (lastModified != null && validator.lastModified != lastModified) return false
        return validator.usable
    }

    fun encode(): String {
        val body = StringBuilder()
        body.append(MAGIC).append(' ').append(VERSION).append('\n')
        body.append("total ").append(total).append('\n')
        body.append("size ").append(segmentSize).append('\n')
        body.append("etag ").append(escape(validator.etag)).append('\n')
        body.append("lastmodified ").append(escape(validator.lastModified)).append('\n')
        body.append("merged ").append(mergedCount).append(' ').append(mergedBytes).append('\n')
        for (s in segments) {
            body.append("seg ").append(s.segment.index).append(' ').append(s.segment.start).append(' ').append(s.segment.endInclusive)
                .append(' ').append(s.doneBytes).append(' ').append(java.lang.Long.toHexString(s.crc)).append('\n')
        }
        val crc = CRC32().apply { update(body.toString().toByteArray(Charsets.UTF_8)) }.value
        body.append("check ").append(java.lang.Long.toHexString(crc)).append('\n')
        return body.toString()
    }

    companion object {
        private const val MAGIC = "ALMUS-SEGMENTS"
        private const val VERSION = 1

        /** Null for anything that is not a complete, self-consistent manifest written by [encode]. */
        fun decode(text: String): SegmentManifest? = try {
            parse(text)
        } catch (_: RuntimeException) {
            null
        }

        private fun parse(text: String): SegmentManifest? {
            val checkAt = text.lastIndexOf("check ")
            if (checkAt <= 0 || !text.endsWith("\n")) return null
            val body = text.substring(0, checkAt)
            val expected = text.substring(checkAt + 6).trim().toLongOrNull(16) ?: return null
            if (CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value != expected) return null

            val lines = body.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "$MAGIC $VERSION") return null
            var total = -1L
            var size = -1L
            var etag: String? = null
            var lastModified: String? = null
            var mergedCount = 0
            var mergedBytes = 0L
            val states = ArrayList<SegmentState>()
            for (line in lines.drop(1)) {
                val p = line.split(' ')
                when (p[0]) {
                    "total" -> total = p[1].toLong()
                    "size" -> size = p[1].toLong()
                    "etag" -> etag = unescape(p.getOrNull(1))
                    "lastmodified" -> lastModified = unescape(p.getOrNull(1))
                    "merged" -> { mergedCount = p[1].toInt(); mergedBytes = p[2].toLong() }
                    "seg" -> {
                        val seg = Segment(p[1].toInt(), p[2].toLong(), p[3].toLong())
                        states += SegmentState(seg, p[4].toLong(), p[5].toLong(16))
                    }
                    else -> return null
                }
            }
            if (total <= 0 || size <= 0 || states.isEmpty()) return null
            // The segments must tile the file exactly.
            var next = 0L
            states.forEachIndexed { i, s ->
                if (s.segment.index != i || s.segment.start != next) return null
                next = s.segment.endInclusive + 1
            }
            if (next != total) return null
            if (mergedCount !in 0..states.size) return null
            if (mergedCount > 0 && (mergedBytes != states.take(mergedCount).sumOf { it.segment.length } || states.take(mergedCount).any { !it.complete })) return null
            if (mergedCount == 0 && mergedBytes != 0L) return null
            return SegmentManifest(total, size, Validator(etag, lastModified), states, mergedCount, mergedBytes)
        }

        fun fresh(total: Long, segmentSize: Long, validator: Validator): SegmentManifest =
            SegmentManifest(total, segmentSize, validator, SegmentPlanner.plan(total, segmentSize).map { SegmentState(it, 0, 0) })

        // Header values never contain a newline, but a space or a leading "-" must survive the line format.
        private fun escape(v: String?): String = if (v == null) "-" else "=" + java.net.URLEncoder.encode(v, "UTF-8")

        private fun unescape(v: String?): String? = when {
            v == null || v == "-" -> null
            v.startsWith("=") -> java.net.URLDecoder.decode(v.substring(1), "UTF-8")
            else -> throw IllegalArgumentException("bad value")
        }
    }
}

/** Reads and writes the manifest file. A write replaces the old file atomically, so a reader sees the old or the new one, never a mix. */
object SegmentManifestFile {
    fun read(file: File): SegmentManifest? = try {
        if (file.isFile) SegmentManifest.decode(file.readText(Charsets.UTF_8)) else null
    } catch (_: IOException) {
        null
    }

    /**
     * The manifest is not flushed to disk on every save: after a power failure an older manifest may come back, which
     * only claims less than the (flushed) segment data holds, or names a segment that was merged and deleted, which is
     * then simply downloaded again. It can never claim more than is on disk, because data is flushed BEFORE it is recorded.
     */
    fun write(file: File, manifest: SegmentManifest) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(manifest.encode().toByteArray(Charsets.UTF_8))
        atomicReplace(tmp, file)
    }

    /** Moves [from] over [to] in one step where the file system can, and still replaces it where it cannot. */
    fun atomicReplace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
