package com.almus.ytdownloader.media

import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.SaveFolders
import com.almus.ytdownloader.download.SaveLayout
import com.almus.ytdownloader.download.StorageTarget

enum class MediaMode { Video, Audio }

/** Where a file goes. All choices are folders below Download/ALMUS: that is the only place the download engines write. */
enum class DestinationChoice { Automatic, MainFolder, TypeFolder }

object Destinations {
    /**
     * The choices that really work. A folder can be picked per download only with the new download engine; without
     * it the file goes where Settings say, so only [DestinationChoice.Automatic] is offered.
     */
    fun options(perDownloadFolders: Boolean): List<DestinationChoice> =
        if (perDownloadFolders) listOf(DestinationChoice.Automatic, DestinationChoice.MainFolder, DestinationChoice.TypeFolder)
        else listOf(DestinationChoice.Automatic)

    fun label(choice: DestinationChoice, kind: VariantKind): String = when (choice) {
        DestinationChoice.Automatic -> "Automatic"
        DestinationChoice.MainFolder -> "Main folder"
        DestinationChoice.TypeFolder -> if (kind == VariantKind.Video) "Video folder" else "Audio folder"
    }

    /** The folder below Download/ALMUS, or null for the main folder. */
    fun subfolder(choice: DestinationChoice, kind: VariantKind, layout: SaveLayout, fileName: String, mimeType: String?): String? =
        when (choice) {
            DestinationChoice.Automatic -> SaveFolders.subfolderFor(layout, fileName, mimeType)
            DestinationChoice.MainFolder -> null
            DestinationChoice.TypeFolder -> if (kind == VariantKind.Video) SaveFolders.VIDEO else SaveFolders.AUDIO
        }
}

/** What the person has picked so far. */
data class MediaSelection(
    val mode: MediaMode,
    val choiceId: String,
    val destination: DestinationChoice = DestinationChoice.Automatic,
)

/** The chosen choice, in one shape for both modes. */
data class ResolvedChoice(
    val variant: MediaVariant,
    val qualityLabel: String,
    val formatLabel: String,
    val size: EstimatedSize?,
    val conversion: ConversionPlan?,
)

object SelectionLogic {
    /** The starting point: video first (best quality first) when there is video, else the first audio choice. Null when empty. */
    fun initial(options: MediaOptions): MediaSelection? {
        options.video.firstOrNull()?.let { return MediaSelection(MediaMode.Video, it.id) }
        options.audio.firstOrNull()?.let { return MediaSelection(MediaMode.Audio, it.id) }
        return null
    }

    fun withMode(options: MediaOptions, selection: MediaSelection, mode: MediaMode): MediaSelection {
        if (mode == selection.mode) return selection
        val id = when (mode) {
            MediaMode.Video -> options.video.firstOrNull()?.id
            MediaMode.Audio -> options.audio.firstOrNull()?.id
        } ?: return selection
        return selection.copy(mode = mode, choiceId = id)
    }

    fun withChoice(selection: MediaSelection, id: String): MediaSelection = selection.copy(choiceId = id)

    fun withDestination(selection: MediaSelection, destination: DestinationChoice): MediaSelection =
        selection.copy(destination = destination)

    /** The selection as a concrete choice, or null when the id is not one of the options. */
    fun resolve(options: MediaOptions, selection: MediaSelection): ResolvedChoice? = when (selection.mode) {
        MediaMode.Video -> options.video.firstOrNull { it.id == selection.choiceId }?.let {
            ResolvedChoice(it.variant, it.qualityLabel, it.variant.container, it.size, null)
        }
        MediaMode.Audio -> options.audio.firstOrNull { it.id == selection.choiceId }?.let {
            ResolvedChoice(
                variant = it.variant,
                qualityLabel = if (it.conversion != null) "Converted audio" else if (it.variant.isOriginal) "Original audio" else "Audio",
                formatLabel = it.conversion?.target?.label ?: it.variant.container,
                size = it.size,
                conversion = it.conversion,
            )
        }
    }
}

/** Everything the confirmation screen shows. Built only after the chosen file was checked. */
data class DownloadPlan(
    val title: String?,
    val sourceLabel: String,
    val basis: String,
    val kind: VariantKind,
    val qualityLabel: String,
    val formatLabel: String,
    val durationSeconds: Int?,
    val size: EstimatedSize?,
    val info: LinkInfo,
    val destination: DestinationChoice,
    val subfolder: String?,
    val displayPath: String,
    val conversion: ConversionPlan?,
)

object PlanBuilder {
    /** The size of the checked file wins over the catalog's figure, because the server has just stated it. */
    fun build(
        catalog: MediaCatalog,
        resolved: ResolvedChoice,
        info: LinkInfo,
        destination: DestinationChoice,
        layout: SaveLayout,
        target: StorageTarget,
    ): DownloadPlan {
        val subfolder = Destinations.subfolder(destination, resolved.variant.kind, layout, info.fileName, info.mimeType)
        val size = if (info.contentLength > 0) EstimatedSize(info.contentLength, exact = true) else resolved.size
        return DownloadPlan(
            title = catalog.title,
            sourceLabel = catalog.sourceLabel,
            basis = catalog.basis,
            kind = resolved.variant.kind,
            qualityLabel = resolved.qualityLabel,
            formatLabel = resolved.formatLabel,
            durationSeconds = catalog.durationSeconds,
            size = size,
            info = info,
            destination = destination,
            subfolder = subfolder,
            displayPath = SaveFolders.displayPath(target, subfolder),
            conversion = resolved.conversion,
        )
    }
}
