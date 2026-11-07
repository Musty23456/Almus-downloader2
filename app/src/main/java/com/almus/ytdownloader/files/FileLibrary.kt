package com.almus.ytdownloader.files

import com.almus.ytdownloader.data.DownloadEntity
import com.almus.ytdownloader.download.DownloadState
import com.almus.ytdownloader.download.MimeTypes
import com.almus.ytdownloader.download.FileNameResolver

/**
 * Builds the file list: completed downloads from Room, each checked against the real file,
 * plus (optionally) the files of a folder the user chose through the Storage Access Framework.
 * Pure Kotlin; the Android parts are behind [FileProbe] and [FolderLister].
 */
class FileLibrary(
    private val probe: FileProbe,
    private val folders: FolderLister,
) {
    data class Snapshot(val entries: List<FileEntry>, val folder: FolderState)

    fun scan(records: List<DownloadEntity>, folderTree: String?): Snapshot {
        val downloads = records
            .filter { it.state == DownloadState.Completed.name }
            .map { toEntry(it, safeProbe(it)) }
        if (folderTree == null) return Snapshot(downloads, FolderState.None)

        val listing = try {
            folders.list(folderTree)
        } catch (e: Exception) {
            FolderListing.Failed(e.message ?: "The folder could not be read.")
        }
        return when (listing) {
            is FolderListing.Files -> Snapshot(
                FileMerger.merge(downloads, listing.files),
                FolderState.Granted(listing.label, listing.files.size),
            )
            FolderListing.Denied -> Snapshot(downloads, FolderState.Denied)
            FolderListing.Gone -> Snapshot(downloads, FolderState.Gone)
            is FolderListing.Failed -> Snapshot(downloads, FolderState.Failed(listing.message))
        }
    }

    /** Re-checks one file right before it is opened, shared or deleted. */
    fun isStillThere(entry: FileEntry, record: DownloadEntity?): Boolean = when (entry.source) {
        is FileSource.Download -> record != null && safeProbe(record) is ProbeResult.Available
        is FileSource.Folder -> runCatching { folders.exists(entry.source.documentUri) }.getOrDefault(false)
    }

    private fun safeProbe(record: DownloadEntity): ProbeResult =
        try {
            probe.probe(record)
        } catch (e: SecurityException) {
            ProbeResult.NoAccess
        } catch (e: Exception) {
            ProbeResult.Error(e.message ?: "The file could not be checked.")
        }

    companion object {
        fun toEntry(record: DownloadEntity, result: ProbeResult): FileEntry {
            val recordedSize = when {
                record.totalBytes > 0 -> record.totalBytes
                record.downloadedBytes > 0 -> record.downloadedBytes
                else -> -1L
            }
            val probeMime = (result as? ProbeResult.Available)?.mimeType
            val mime = pickMime(probeMime, record.mimeType, record.fileName)
            val size: Long
            val modified: Long
            val status: FileStatus
            when (result) {
                is ProbeResult.Available -> {
                    size = if (result.sizeBytes >= 0) result.sizeBytes else recordedSize
                    modified = if (result.modifiedMs > 0) result.modifiedMs else record.updatedAt
                    status = FileStatus.Available
                }
                ProbeResult.Missing -> {
                    size = recordedSize; modified = record.updatedAt; status = FileStatus.Missing
                }
                ProbeResult.NoAccess -> {
                    size = recordedSize; modified = record.updatedAt; status = FileStatus.NoAccess
                }
                is ProbeResult.Error -> {
                    size = recordedSize; modified = record.updatedAt; status = FileStatus.Error
                }
            }
            return FileEntry(
                key = "d:${record.id}",
                source = FileSource.Download(record.id, record.systemId),
                name = record.fileName,
                sizeBytes = size,
                modifiedMs = modified,
                mimeType = mime,
                kind = FileKind.classify(record.fileName, mime),
                status = status,
            )
        }

        /** First specific type among the candidates, else the type implied by the extension. */
        fun pickMime(first: String?, second: String?, fileName: String): String? {
            listOf(first, second).forEach { candidate ->
                val normalized = MimeTypes.normalize(candidate)
                if (normalized != null && !MimeTypes.isGeneric(normalized)) return normalized
            }
            return MimeTypes.forExtension(FileNameResolver.extensionOf(fileName))
        }
    }
}

/** Combines download entries with files found in a user-selected folder, without duplicates. */
object FileMerger {
    /**
     * A folder file is hidden when an available download entry has the same name, because it is
     * the same file. A download whose file is missing does not hide a folder file of that name.
     */
    fun merge(downloads: List<FileEntry>, folderFiles: List<FolderFile>): List<FileEntry> {
        val knownNames = downloads.filter { it.status == FileStatus.Available }.map { it.name }.toSet()
        val extra = folderFiles
            .filter { it.name !in knownNames }
            .map { file ->
                val mime = FileLibrary.pickMime(file.mimeType, null, file.name)
                FileEntry(
                    key = "s:${file.documentUri}",
                    source = FileSource.Folder(file.documentUri, file.canDelete),
                    name = file.name,
                    sizeBytes = if (file.sizeBytes >= 0) file.sizeBytes else -1L,
                    modifiedMs = if (file.modifiedMs > 0) file.modifiedMs else 0L,
                    mimeType = mime,
                    kind = FileKind.classify(file.name, mime),
                    status = FileStatus.Available,
                )
            }
        return downloads + extra
    }
}
