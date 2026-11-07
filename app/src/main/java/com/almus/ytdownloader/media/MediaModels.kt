package com.almus.ytdownloader.media

import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.download.LinkInfo

enum class VariantKind { Video, Audio }

/**
 * ONE real file that an authorized source offers for download. Every field that the source did not state stays null:
 * nothing is guessed, rounded up or filled in. [sizeBytes] is the size the source stated; [bitrateKbps] is only used,
 * together with the duration, to give an estimate when no size was stated.
 */
data class MediaVariant(
    val id: String,
    val kind: VariantKind,
    /** Readable container or format name such as "MP4", "WebM", "MP3". */
    val container: String,
    val mimeType: String?,
    /** The direct address of the file. It is checked again (redirects, https, size) before anything is downloaded. */
    val url: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val bitrateKbps: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val sizeBytes: Long? = null,
    /** True/false when the source said whether the file has sound, null when it did not. */
    val hasAudio: Boolean? = null,
    /** True for the file the owner uploaded (as opposed to a version the source made from it). */
    val isOriginal: Boolean = false,
)

/**
 * What an authorized source offers for one item. [basis] says in plain words why the source is considered authorized
 * (it publishes these files for download itself). [conversionAllowed] is true only when the source's own terms
 * (an open license) allow processing the files; it gates the conversion choices and is false for ordinary links.
 * [verifiedInfo] is set when the single file was already checked by the link check, so it is not checked twice.
 */
data class MediaCatalog(
    val sourceId: String,
    val sourceLabel: String,
    val basis: String,
    val title: String?,
    val durationSeconds: Int?,
    val pageUrl: String?,
    val variants: List<MediaVariant>,
    val notices: List<String> = emptyList(),
    val conversionAllowed: Boolean = false,
    val verifiedInfo: LinkInfo? = null,
)

/** A size and whether it is exact (the source stated it) or an estimate (bitrate times duration). */
data class EstimatedSize(val bytes: Long, val exact: Boolean) {
    val text: String get() = if (exact) ByteFormat.format(bytes) else "about " + ByteFormat.format(bytes) + " (estimate)"
}

object SizeEstimator {
    /** The stated size when there is one, else bitrate x duration when both are known, else null (never a guess). */
    fun of(variant: MediaVariant, durationSeconds: Int?): EstimatedSize? {
        val stated = variant.sizeBytes
        if (stated != null && stated > 0) return EstimatedSize(stated, exact = true)
        val rate = variant.bitrateKbps
        if (rate != null && rate > 0 && durationSeconds != null && durationSeconds > 0) {
            return EstimatedSize(rate * 1000L / 8L * durationSeconds, exact = false)
        }
        return null
    }

    fun text(size: EstimatedSize?): String = size?.text ?: "Size not stated"
}

object DurationFormat {
    /** "3:25" or "1:02:03"; null when there is no usable length. */
    fun of(seconds: Int?): String? {
        if (seconds == null || seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val mm = if (h > 0) m.toString().padStart(2, '0') else m.toString()
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
    }
}
