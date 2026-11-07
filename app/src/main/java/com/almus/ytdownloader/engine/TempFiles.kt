package com.almus.ytdownloader.engine

import java.io.File

/**
 * Every temporary file one download can own, and the one place that knows how to remove them.
 *
 * - `dl-ID.part`   the single-connection partial file, and the finished merged file waiting to be published
 * - `dl-ID.segN`   segment N of a segmented download
 * - `dl-ID.segs`   the manifest (plus `.segs.tmp` while it is being replaced)
 * - `dl-ID.merge`  the file the segments are merged into
 */
class TempFiles(private val dir: File, val id: Long, partName: String) {
    val part: File = File(dir, partName)
    val manifest: File = File(dir, "dl-$id.segs")
    val merge: File = File(dir, "dl-$id.merge")

    fun segment(index: Int): File = File(dir, "dl-$id.seg$index")

    /** True when a segmented download left something behind. */
    fun hasSegmentState(): Boolean = manifest.exists() || merge.exists() || segmentFiles().isNotEmpty()

    /** Removes the manifest, the merge file and all segment files, but not the [part] file. */
    fun deleteSegmentArtifacts() {
        segmentFiles().forEach { it.delete() }
        manifest.delete()
        File(dir, manifest.name + ".tmp").delete()
        merge.delete()
    }

    /** Removes everything this download owns. */
    fun deleteAll() {
        deleteSegmentArtifacts()
        part.delete()
    }

    private fun segmentFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.startsWith("dl-$id.seg") && SEGMENT_FILE.matches(f.name) }?.toList().orEmpty()

    companion object {
        private val SEGMENT_FILE = Regex("^dl-(\\d+)\\.seg\\d+$")
        private val OWNED = Regex("^dl-(\\d+)\\.(part|segs|segs\\.tmp|merge|seg\\d+)$")

        /**
         * Deletes temporary files nobody owns any more: files of downloads that are not in [keepIds], and any `.part`
         * file whose name is not in [keepPartNames]. Files that do not look like ours are never touched.
         */
        fun sweep(dir: File, keepIds: Set<Long>, keepPartNames: Set<String>) {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val owned = OWNED.matchEntire(f.name)
                val remove = when {
                    owned != null -> owned.groupValues[1].toLongOrNull() !in keepIds && f.name !in keepPartNames
                    f.name.endsWith(".part") -> f.name !in keepPartNames
                    else -> false
                }
                if (remove) f.delete()
            }
        }
    }
}
