package com.almus.ytdownloader.engine

/** One byte range of the file, both ends included (the way HTTP Range counts). */
data class Segment(val index: Int, val start: Long, val endInclusive: Long) {
    init {
        require(index >= 0 && start >= 0 && endInclusive >= start) { "invalid segment $index: $start-$endInclusive" }
    }

    val length: Long get() = endInclusive - start + 1
}

/** Tunable limits of segmented downloading. The defaults are what the app uses; tests shrink them. */
data class SegmentationConfig(
    /** Files smaller than this are always downloaded over one connection (a probe and a merge would cost more than they save). */
    val minFileBytes: Long = 4L * MIB,
    /** A segment is never smaller than this (except the last one). */
    val minSegmentBytes: Long = 1L * MIB,
    val maxSegmentBytes: Long = 16L * MIB,
    /** Segment sizes are rounded up to a multiple of this. */
    val alignBytes: Long = 64L * 1024,
    /** Hard limit on the number of segments, so the manifest stays small for huge files. */
    val maxSegments: Int = 1024,
    /** Data inside a running segment is flushed to disk and recorded after this many bytes. */
    val checkpointBytes: Long = 4L * MIB,
    /** Tries of one segment without receiving a single new byte before the engine-level retry takes over. */
    val maxSegmentAttempts: Int = 5,
    /** How often one segment may be found damaged and downloaded again before the download fails. */
    val maxSegmentRecoveries: Int = 3,
) {
    companion object {
        const val MIB = 1024L * 1024L
    }
}

/** Splits a file into segments. Pure Kotlin, unit tested. */
object SegmentPlanner {
    /**
     * Segment size for [total] bytes and up to [connections] connections. Aims at several segments per connection
     * (so a slow connection never holds the whole download back and a failed segment is cheap to repeat),
     * clamped to the configured minimum and maximum and rounded up to the alignment.
     */
    fun segmentSize(total: Long, connections: Int, config: SegmentationConfig = SegmentationConfig()): Long {
        require(total > 0) { "total must be positive" }
        val ideal = total / (connections.coerceAtLeast(1) * SEGMENTS_PER_CONNECTION)
        var size = ideal.coerceIn(config.minSegmentBytes, maxOf(config.minSegmentBytes, config.maxSegmentBytes))
        val forCountLimit = (total + config.maxSegments - 1) / config.maxSegments
        if (size < forCountLimit) size = forCountLimit
        return alignUp(size, config.alignBytes)
    }

    /** Contiguous segments covering exactly 0 until [total]; only the last one may be shorter than [segmentSize]. */
    fun plan(total: Long, segmentSize: Long): List<Segment> {
        require(total > 0 && segmentSize > 0) { "total and segmentSize must be positive" }
        val out = ArrayList<Segment>(((total + segmentSize - 1) / segmentSize).toInt())
        var start = 0L
        var index = 0
        while (start < total) {
            val end = minOf(start + segmentSize, total) - 1
            out += Segment(index++, start, end)
            start = end + 1
        }
        return out
    }

    private fun alignUp(value: Long, align: Long): Long = if (align <= 1) value else (value + align - 1) / align * align

    private const val SEGMENTS_PER_CONNECTION = 4
}

/** Decides whether a download may be split at all. Pure Kotlin, unit tested. */
object SegmentationPolicy {
    sealed interface Decision {
        data object Use : Decision

        data class Skip(val reason: String) : Decision
    }

    /**
     * Segmented downloading needs ALL of: a plain 200 answer with a known size that is big enough, an explicit
     * "Accept-Ranges: bytes", no content coding (byte positions must mean file positions), a validator that can go
     * into If-Range (so every segment is guaranteed to come from the same version of the file), and room for more
     * than one connection. Anything less and the file is downloaded over one connection, exactly as before.
     * The server's real behaviour is checked again for every segment ([RangeValidator]).
     */
    fun evaluate(
        enabled: Boolean,
        code: Int,
        total: Long,
        acceptRanges: String?,
        contentEncoding: String?,
        validator: Validator,
        connections: Int,
        config: SegmentationConfig = SegmentationConfig(),
    ): Decision {
        if (!enabled) return Decision.Skip("segmented downloads are switched off")
        if (code != 200) return Decision.Skip("the server did not answer with a plain 200")
        if (total <= 0) return Decision.Skip("the size is unknown")
        if (total < config.minFileBytes || total < 2 * config.minSegmentBytes) return Decision.Skip("the file is small")
        if (HttpHeaders.acceptsRanges(acceptRanges) != true) return Decision.Skip("the server does not announce byte ranges")
        val encoding = contentEncoding?.trim()?.lowercase()
        if (!encoding.isNullOrEmpty() && encoding != "identity") return Decision.Skip("the content is encoded")
        if (!validator.usable) return Decision.Skip("the server gives no way to check that the file stays the same")
        if (connections < 2) return Decision.Skip("only one connection is allowed right now")
        return Decision.Use
    }
}
