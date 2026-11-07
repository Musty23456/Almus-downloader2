package com.almus.ytdownloader.files

enum class SortField { Name, Date, Size, Type }

data class SortSpec(val field: SortField, val ascending: Boolean)

/** Search, filter, sort and count. Pure Kotlin, unit tested. */
object FileListLogic {
    private val nameOrder = Comparator<FileEntry> { x, y ->
        val natural = NaturalOrder.compare(x.name, y.name)
        if (natural != 0) {
            natural
        } else {
            val raw = x.name.compareTo(y.name)
            if (raw != 0) raw else x.key.compareTo(y.key)
        }
    }

    /** Case-insensitive match on file name or MIME type. A blank query keeps everything. */
    fun search(entries: List<FileEntry>, query: String): List<FileEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { entry ->
            entry.name.lowercase().contains(q) || entry.mimeType?.lowercase()?.contains(q) == true
        }
    }

    fun byKind(entries: List<FileEntry>, kind: FileKind?): List<FileEntry> =
        if (kind == null) entries else entries.filter { it.kind == kind }

    fun counts(entries: List<FileEntry>): Map<FileKind, Int> =
        FileKind.entries.associateWith { kind -> entries.count { it.kind == kind } }

    /**
     * Sorts by the chosen field. Unknown sizes and dates always come last, in either direction.
     * Ties fall back to natural name order, so the result is deterministic.
     */
    fun sort(entries: List<FileEntry>, spec: SortSpec): List<FileEntry> {
        fun Comparator<FileEntry>.directed() = if (spec.ascending) this else this.reversed()
        return when (spec.field) {
            SortField.Name -> entries.sortedWith(nameOrder.directed())
            SortField.Type -> entries.sortedWith(
                compareBy<FileEntry>({ it.kind.ordinal }, { it.extension ?: "" }).directed().then(nameOrder),
            )
            SortField.Date -> {
                val (known, unknown) = entries.partition { it.modifiedMs > 0 }
                known.sortedWith(compareBy<FileEntry> { it.modifiedMs }.directed().then(nameOrder)) +
                    unknown.sortedWith(nameOrder)
            }
            SortField.Size -> {
                val (known, unknown) = entries.partition { it.sizeBytes >= 0 }
                known.sortedWith(compareBy<FileEntry> { it.sizeBytes }.directed().then(nameOrder)) +
                    unknown.sortedWith(nameOrder)
            }
        }
    }

    /** Ids of download records whose file is gone. Removing them from the list never touches files. */
    fun missingDownloadIds(entries: List<FileEntry>): List<Long> = entries
        .filter { it.status == FileStatus.Missing }
        .mapNotNull { (it.source as? FileSource.Download)?.downloadId }
}

enum class AccessProblem { None, StorageDenied, FolderDenied }

/** Everything the screen needs, derived from one scan plus the user's search, filter and sort. */
data class FilesView(
    val visible: List<FileEntry>,
    /** Per category, after the search but before the category filter. */
    val counts: Map<FileKind, Int>,
    val libraryCount: Int,
    val missingCount: Int,
    val access: AccessProblem,
    val libraryEmpty: Boolean,
    val noMatches: Boolean,
) {
    companion object {
        val Empty = FilesView(emptyList(), FileListLogic.counts(emptyList()), 0, 0, AccessProblem.None, true, false)

        fun build(snapshot: FileLibrary.Snapshot?, query: String, kind: FileKind?, sort: SortSpec): FilesView {
            if (snapshot == null) return Empty
            val all = snapshot.entries
            val searched = FileListLogic.search(all, query)
            val visible = FileListLogic.sort(FileListLogic.byKind(searched, kind), sort)
            val access = when {
                all.isNotEmpty() && all.all { it.status == FileStatus.NoAccess } -> AccessProblem.StorageDenied
                snapshot.folder is FolderState.Denied -> AccessProblem.FolderDenied
                else -> AccessProblem.None
            }
            return FilesView(
                visible = visible,
                counts = FileListLogic.counts(searched),
                libraryCount = all.size,
                missingCount = all.count { it.status == FileStatus.Missing },
                access = access,
                libraryEmpty = all.isEmpty(),
                noMatches = all.isNotEmpty() && visible.isEmpty(),
            )
        }
    }
}

/** Which actions make sense for a file. One table for the view model and the UI. */
object FileActionPolicy {
    fun canOpen(entry: FileEntry) = entry.status == FileStatus.Available

    /** APKs are never opened by this app: the user is sent to the system Downloads instead. */
    fun opensInSystemDownloads(entry: FileEntry) = entry.kind == FileKind.Apks

    fun canShare(entry: FileEntry) = entry.status == FileStatus.Available

    /** Copying changes nothing about the original, so it is allowed for every available file (APKs too). */
    fun canSaveCopy(entry: FileEntry) = entry.status == FileStatus.Available

    fun canDelete(entry: FileEntry): Boolean {
        if (entry.status != FileStatus.Available) return false
        return when (val source = entry.source) {
            is FileSource.Download -> source.systemId != null
            is FileSource.Folder -> source.canDelete
        }
    }

    /** Removing a record from history is only possible for downloads and never deletes the file. */
    fun canRemoveFromList(entry: FileEntry) = entry.source is FileSource.Download
}
