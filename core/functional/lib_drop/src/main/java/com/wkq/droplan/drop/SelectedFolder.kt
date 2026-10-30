package com.wkq.droplan.drop

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InterruptedIOException

data class FolderFile(val uri: Uri, val relativePath: String)
data class FolderSelection(val files: List<FolderFile>, val truncated: Boolean)

/** 只遍历 SAF 明确授权的本地树；限制节点、深度和文件数，不读取正文。 */
object SelectedFolder {
    private val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents",
        "com.android.providers.media.documents", "com.android.providers.media.module")

    fun read(resolver: ContentResolver, tree: Uri, limit: Int = 1000,
             cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): FolderSelection {
        require(limit in 1..1000 && tree.scheme == "content" && tree.authority in localAuthorities && DocumentsContract.isTreeUri(tree))
        data class Directory(val id: String, val path: String, val depth: Int)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val rootName = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw java.io.IOException("folder_unavailable")
        fun segment(name: String): String = name.replace('/', '_').replace('\\', '_').trim(' ', '.').take(180).ifEmpty { "folder" }
        val pending = java.util.ArrayDeque<Directory>()
        pending.add(Directory(rootId, segment(rootName), 0))
        val visited = hashSetOf<String>(); val files = arrayListOf<FolderFile>(); var nodes = 0
        while (pending.isNotEmpty()) {
            if (cancelled()) throw InterruptedIOException("folder_cancelled")
            val directory = pending.removeFirst()
            if (!visited.add(directory.id)) continue
            require(directory.depth <= 32) { "folder_depth_limit" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, projection, null, null, null) ?: throw java.io.IOException("folder_unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    if (cancelled()) throw InterruptedIOException("folder_cancelled")
                    require(++nodes <= 5000) { "folder_node_limit" }
                    val id = it.getString(0) ?: throw java.io.IOException("folder_unavailable")
                    val path = directory.path + "/" + segment(it.getString(1).orEmpty())
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(Directory(id, path, directory.depth + 1))
                    else {
                        if (files.size == limit) return FolderSelection(files, true)
                        files.add(FolderFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), path))
                    }
                }
            }
        }
        return FolderSelection(files, false)
    }
}
