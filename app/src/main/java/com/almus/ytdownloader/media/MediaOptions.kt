package com.almus.ytdownloader.media

/** The video quality ladder the interface knows by name. A rung is only ever shown when a real file has exactly that height. */
enum class VideoRung(val height: Int) {
    P144(144), P240(240), P360(360), P480(480), P720(720), P1080(1080), P1440(1440), P2160(2160),
    ;

    val label: String get() = "${height}p"
}

object VideoQualities {
    /** The shorter side of the frame, which is what "720p" names (so portrait video is labelled correctly too). */
    fun shortSide(variant: MediaVariant): Int? {
        val w = variant.widthPx?.takeIf { it > 0 }
        val h = variant.heightPx?.takeIf { it > 0 }
        return when {
            w != null && h != null -> minOf(w, h)
            h != null -> h
            else -> null
        }
    }

    fun rungOf(variant: MediaVariant): VideoRung? = shortSide(variant)?.let { side -> VideoRung.entries.firstOrNull { it.height == side } }

    /** "1080p" for a rung, the real height for anything else (for example "800p"), and "Original quality" when unstated. */
    fun label(variant: MediaVariant): String =
        rungOf(variant)?.label ?: shortSide(variant)?.let { "${it}p" } ?: "Original quality"
}

enum class AudioTarget(val label: String) { Mp3("MP3"), M4a("M4A") }

/**
 * An on-device converter. NONE ships in this version (Android has no MP3 encoder and none could be added), so the
 * conversion choices never appear today. The seam exists so a converter can be added without touching the interface.
 */
interface AudioConverter {
    val target: AudioTarget

    fun supports(variant: MediaVariant): Boolean
}

data class ConversionPlan(val target: AudioTarget, val sourceVariantId: String)

data class VideoChoice(
    val variant: MediaVariant,
    val qualityLabel: String,
    val rung: VideoRung?,
    val resolutionKnown: Boolean,
    val size: EstimatedSize?,
) {
    val id: String get() = variant.id
}

data class AudioChoice(
    val id: String,
    val variant: MediaVariant,
    val title: String,
    val subtitle: String,
    val size: EstimatedSize?,
    val conversion: ConversionPlan? = null,
)

data class MediaOptions(
    val video: List<VideoChoice>,
    val audio: List<AudioChoice>,
    val durationSeconds: Int?,
) {
    val isEmpty: Boolean get() = video.isEmpty() && audio.isEmpty()
}

object MediaOptionsBuilder {
    /** Turns a catalog into choices. Every choice is a real variant of the catalog; nothing is added or filled in. */
    fun build(catalog: MediaCatalog, converters: List<AudioConverter> = emptyList()): MediaOptions {
        val duration = catalog.durationSeconds

        val video = catalog.variants
            .filter { it.kind == VariantKind.Video }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<MediaVariant> { VideoQualities.shortSide(it) ?: -1 }
                    .thenBy { it.container }
                    .thenByDescending { it.bitrateKbps ?: 0 },
            )
            .map { v ->
                VideoChoice(
                    variant = v,
                    qualityLabel = VideoQualities.label(v),
                    rung = VideoQualities.rungOf(v),
                    resolutionKnown = VideoQualities.shortSide(v) != null,
                    size = SizeEstimator.of(v, duration),
                )
            }

        val audioVariants = catalog.variants
            .filter { it.kind == VariantKind.Audio }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<MediaVariant> { it.isOriginal }
                    .thenByDescending { it.bitrateKbps ?: 0 }
                    .thenBy { it.container },
            )
        val audio = audioVariants.map { v ->
            if (v.isOriginal) {
                AudioChoice(
                    id = v.id,
                    variant = v,
                    title = "Original audio · " + v.container,
                    subtitle = "Exactly as the source provides it. No conversion.",
                    size = SizeEstimator.of(v, duration),
                )
            } else {
                AudioChoice(
                    id = v.id,
                    variant = v,
                    title = v.container + " audio",
                    subtitle = "A " + v.container + " file the source provides itself. No conversion.",
                    size = SizeEstimator.of(v, duration),
                )
            }
        }.toMutableList()

        if (catalog.conversionAllowed) {
            for (converter in converters) {
                val source = audioVariants.firstOrNull { converter.supports(it) } ?: continue
                if (audioVariants.any { it.container.equals(converter.target.label, ignoreCase = true) }) continue
                audio += AudioChoice(
                    id = "convert:" + converter.target.name + ":" + source.id,
                    variant = source,
                    title = "Convert to " + converter.target.label,
                    subtitle = "Made on this device from the " + source.container + " file. Only for files you may process.",
                    size = null,
                    conversion = ConversionPlan(converter.target, source.id),
                )
            }
        }
        return MediaOptions(video, audio, duration)
    }
}

/** The "Format details" rows for one variant. Only what is known is listed. */
object FormatDetails {
    fun rows(variant: MediaVariant, durationSeconds: Int?, size: EstimatedSize?): List<Pair<String, String>> = buildList {
        add("Type" to if (variant.kind == VariantKind.Video) "Video" else "Audio")
        add("Format" to variant.container)
        variant.mimeType?.let { add("MIME type" to it) }
        val w = variant.widthPx
        val h = variant.heightPx
        if (w != null && h != null && w > 0 && h > 0) add("Frame size" to "$w × $h")
        variant.videoCodec?.let { add("Video codec" to it) }
        variant.audioCodec?.let { add("Audio codec" to it) }
        variant.bitrateKbps?.takeIf { it > 0 }?.let { add("Bitrate" to "$it kbps") }
        variant.hasAudio?.let { add("Sound" to if (it) "Has sound" else "No sound") }
        DurationFormat.of(durationSeconds)?.let { add("Duration" to it) }
        add("File size" to SizeEstimator.text(size))
        if (variant.isOriginal) add("Version" to "Original upload")
    }

    /** "MP4 · H.264 · 245 MB" style one-liner for a card. */
    fun summary(variant: MediaVariant, size: EstimatedSize?): String = buildList {
        add(variant.container)
        (variant.videoCodec ?: variant.audioCodec)?.let { add(it) }
        variant.bitrateKbps?.takeIf { it > 0 && variant.kind == VariantKind.Audio }?.let { add("$it kbps") }
        add(SizeEstimator.text(size))
    }.joinToString(" · ")
}
