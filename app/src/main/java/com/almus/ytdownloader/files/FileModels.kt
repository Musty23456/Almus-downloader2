package com.almus.ytdownloader.files

import com.almus.ytdownloader.data.DownloadEntity
import com.almus.ytdownloader.download.FileNameResolver

enum class FileStatus {
    Available,

    /** The file was moved or deleted outside the app. */
    Missing,

    /** Android refused access (permission revoked). */
    NoAccess,

    /** The file could not be checked for another reason. */
    Error,
}

/** Where a file comes from, which decides how it can be opened, shared and deleted. */
sealed interface FileSource {
    /** A download recorded in Room. [systemId] is the DownloadManager id used to reach the file. */
    data class Download(val downloadId: Long, val systemId: Long?) : FileSource

    /** A file found in a folder the user chose with the Storage Access Framework. */
    data class Folder(val documentUri: String, val canDelete: Boolean) : FileSource
}

data class FileEntry(
    val key: String,
    val source: FileSource,
    val name: String,
    /** -1 when unknown. */
    val sizeBytes: Long,
    /** 0 when unknown. */
    val modifiedMs: Long,
    val mimeType: String?,
    val kind: FileKind,
    val status: FileStatus,
) {
    val extension: String? get() = FileNameResolver.extensionOf(name)?.lowercase()
    val isInHistory: Boolean get() = source is FileSource.Download
}

sealed interface ProbeResult {
    data class Available(val sizeBytes: Long, val modifiedMs: Long, val mimeType: String?) : ProbeResult

    data object Missing : ProbeResult

    data object NoAccess : ProbeResult

    data class Error(val message: String) : ProbeResult
}

/** Looks at the real file behind a download record. Implementations must not throw. */
interface FileProbe {
    fun probe(entity: DownloadEntity): ProbeResult
}

/** A file inside a user-selected folder. */
data class FolderFile(
    val documentUri: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val mimeType: String?,
    val canDelete: Boolean,
)

sealed interface FolderListing {
    data class Files(val label: String, val files: List<FolderFile>) : FolderListing

    /** The permission for the chosen folder was lost. */
    data object Denied : FolderListing

    /** The folder no longer exists. */
    data object Gone : FolderListing

    data class Failed(val message: String) : FolderListing
}

/** Access to a folder the user picked with the Storage Access Framework. */
interface FolderLister {
    fun list(treeUri: String): FolderListing

    fun exists(documentUri: String): Boolean

    /** Deletes one document. Returns false when it could not be deleted. */
    fun delete(documentUri: String): Boolean
}

sealed interface FolderState {
    data object None : FolderState

    data class Granted(val label: String, val fileCount: Int) : FolderState

    data object Denied : FolderState

    data object Gone : FolderState

    data class Failed(val message: String) : FolderState
}

/** Remembers the folder the user chose. */
interface FolderSettings {
    var treeUri: String?
}

/** Readable name for a Storage Access Framework tree id such as "primary:Download/ALMUS". */
object FolderLabel {
    fun of(treeDocumentId: String): String {
        val path = treeDocumentId.substringAfter(':', treeDocumentId).trim('/')
        return path.ifBlank { "Storage root" }
    }
}
