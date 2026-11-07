package com.almus.ytdownloader.download

/**
 * Where finished downloads go inside the app's public folder (Download/ALMUS).
 * Android's DownloadManager can only write below the Downloads folder, so these are the locations
 * it can honour. Any other place is reached with "Save a copy to…" in the file manager.
 */
enum class SaveLayout {
    /** Everything in Download/ALMUS (the behaviour of earlier versions). */
    Single,

    /** Video in Download/ALMUS/Video, audio in Download/ALMUS/Audio, the rest in Download/ALMUS. */
    ByMedia,
}

/** User setting: how downloads are sorted into folders. */
interface SaveSettings {
    var layout: SaveLayout
}

/** Fixed value, used by tests and as the default when no setting store is given. */
class FixedSaveSettings(override var layout: SaveLayout = SaveLayout.Single) : SaveSettings

object SaveFolders {
    const val VIDEO = "Video"
    const val AUDIO = "Audio"

    /** The sub-folder for a file, or null for the main folder. Only ever one of the fixed names above. */
    fun subfolderFor(layout: SaveLayout, fileName: String, mimeType: String?): String? {
        if (layout == SaveLayout.Single) return null
        return when (MediaFormats.describe(mimeType, fileName)?.kind) {
            MediaKind.Video -> VIDEO
            MediaKind.Audio -> AUDIO
            null -> null
        }
    }

    /** "Video/clip.mp4" or just "clip.mp4" when there is no sub-folder. */
    fun relativePath(subfolder: String?, fileName: String): String =
        if (subfolder == null) fileName else "$subfolder/$fileName"

    /** Human readable folder for the confirmation dialog. */
    fun displayPath(target: StorageTarget, subfolder: String?): String {
        val root = when (target) {
            StorageTarget.PublicDownloads -> "Download/ALMUS"
            StorageTarget.AppPrivate -> "App folder"
        }
        return if (subfolder == null) root else "$root/$subfolder"
    }
}
