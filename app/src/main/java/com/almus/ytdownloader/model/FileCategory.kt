package com.almus.ytdownloader.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.almus.ytdownloader.R
import com.almus.ytdownloader.files.FileKind

/** File groups shown in the UI. The classification itself lives in [FileKind] (pure Kotlin). */
enum class FileCategory(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val kind: FileKind,
) {
    Videos(R.string.cat_videos, Icons.Filled.VideoLibrary, FileKind.Videos),
    Music(R.string.cat_music, Icons.Filled.MusicNote, FileKind.Music),
    Images(R.string.cat_images, Icons.Filled.Image, FileKind.Images),
    Documents(R.string.cat_documents, Icons.Filled.Description, FileKind.Documents),
    Apks(R.string.cat_apks, Icons.Filled.Android, FileKind.Apks),
    Archives(R.string.cat_archives, Icons.Filled.Archive, FileKind.Archives),
    Others(R.string.cat_others, Icons.Filled.Category, FileKind.Others),
    ;

    companion object {
        fun of(kind: FileKind): FileCategory = entries.first { it.kind == kind }

        /** Groups a file by name and (optional) MIME type. */
        fun from(fileName: String, mimeType: String?): FileCategory =
            of(FileKind.classify(fileName, mimeType))
    }
}
