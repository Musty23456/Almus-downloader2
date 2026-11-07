package com.almus.ytdownloader.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/**
 * Lists, checks and deletes files in ONE folder the user picked with the Storage Access Framework
 * (ACTION_OPEN_DOCUMENT_TREE). Access exists only for that folder and only while the persisted
 * permission is held. Subfolders are not scanned.
 */
class DocumentsFolderLister(context: Context) : FolderLister {
    private val resolver = context.applicationContext.contentResolver

    override fun list(treeUri: String): FolderListing {
        val tree = Uri.parse(treeUri)
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!granted) return FolderListing.Denied

        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            return FolderListing.Failed("The saved folder is not valid any more.")
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val files = ArrayList<FolderFile>()
        try {
            val cursor = resolver.query(children, projection, null, null, null)
                ?: return FolderListing.Failed("The folder's storage provider is not available.")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    files += FolderFile(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(),
                        name = name,
                        sizeBytes = if (c.isNull(3)) -1L else c.getLong(3),
                        modifiedMs = if (c.isNull(4)) 0L else c.getLong(4),
                        mimeType = mime,
                        canDelete = !c.isNull(5) &&
                            (c.getInt(5) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0,
                    )
                }
            }
        } catch (e: SecurityException) {
            return FolderListing.Denied
        } catch (e: FileNotFoundException) {
            return FolderListing.Gone
        } catch (e: IllegalArgumentException) {
            return FolderListing.Gone
        }
        return FolderListing.Files(FolderLabel.of(rootId), files)
    }

    override fun exists(documentUri: String): Boolean = try {
        resolver.query(
            Uri.parse(documentUri),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false
    }

    override fun delete(documentUri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(documentUri))
    } catch (e: FileNotFoundException) {
        false
    }
}
