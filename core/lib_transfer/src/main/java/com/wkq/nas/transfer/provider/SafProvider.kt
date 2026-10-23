package com.wkq.nas.transfer.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wkq.nas.transfer.*
import java.io.InputStream

/** 只操作用户授予的树 URI，保留 content URI 身份而不是转换成文件路径。 */
internal class SafProvider(private val context: Context, root: String) : StorageProvider {
    override val capabilities = StorageCapabilities(false, false, false, false, false)
    private val tree = Uri.parse(root)
    private val resolver = context.contentResolver
    private val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private data class Document(val uri: Uri, val name: String, val directory: Boolean, val size: Long, val modified: Long)
    private fun children(parent: Uri): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val result = mutableListOf<Document>()
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Document(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1),
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (cursor.isNull(3)) -1 else cursor.getLong(3), if (cursor.isNull(4)) -1 else cursor.getLong(4))
            }
        } ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
        return result
    }
    private fun find(path: String): Document? {
        resolver.query(rootDocument, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (!it.moveToFirst()) throw TransferException(TransferError.STORAGE_UNAVAILABLE)
            if (it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) throw TransferException(TransferError.NOT_FOUND)
        } ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
        var current = Document(rootDocument, "", true, 0, -1)
        TransferPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach { part ->
            if (!current.directory) throw TransferException(TransferError.CONFLICT)
            current = children(current.uri).firstOrNull { it.name == part } ?: return null
        }
        return current
    }
    override fun stat(path: String) = find(path)?.let { FileMetadata(TransferPaths.normalize(path), it.directory, it.size, it.modified) }
    override fun list(path: String): List<FileMetadata> {
        val parent = find(path) ?: throw TransferException(TransferError.NOT_FOUND)
        return children(parent.uri).map { FileMetadata(TransferPaths.join(path, it.name), it.directory, it.size, it.modified) }
    }
    override fun read(path: String): InputStream = resolver.openInputStream(find(path)?.uri ?: throw TransferException(TransferError.NOT_FOUND))
        ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
    override fun visit(path: String, accept: (FileMetadata) -> Unit) {
        val parent = find(path) ?: throw TransferException(TransferError.NOT_FOUND)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val directory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                accept(FileMetadata(TransferPaths.join(path, cursor.getString(0)), directory,
                    if (directory) 0 else if (cursor.isNull(2)) -1 else cursor.getLong(2), if (cursor.isNull(3)) -1 else cursor.getLong(3)))
            }
        } ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
    }
    override fun mkdirs(path: String) {
        var current = rootDocument
        TransferPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach { part ->
            val existing = children(current).firstOrNull { it.name == part }
            if (existing != null && !existing.directory) throw TransferException(TransferError.CONFLICT)
            current = existing?.uri ?: DocumentsContract.createDocument(resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, part)
                ?: throw TransferException(TransferError.PERMISSION)
        }
    }
    private fun create(path: String): Uri {
        if (find(path) != null) throw TransferException(TransferError.CONFLICT)
        val parent = find(TransferPaths.parent(path)) ?: throw TransferException(TransferError.NOT_FOUND)
        val created = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", TransferPaths.name(path))
            ?: throw TransferException(TransferError.PERMISSION)
        // Provider 自动改名表示存在冲突；只删除本次创建的文档。
        val exact = children(parent.uri).firstOrNull { it.uri == created }
        if (exact?.name != TransferPaths.name(path)) {
            DocumentsContract.deleteDocument(resolver, created)
            throw TransferException(TransferError.CONFLICT)
        }
        return created
    }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) {
        val created = create(path)
        (resolver.openOutputStream(created, "wt") ?: throw TransferException(TransferError.PERMISSION)).use { copyBytes(input, it, progress) }
    }
    override fun moveNew(source: String, target: String, check: () -> Unit) {
        val created = create(target)
        try {
            read(source).use { input -> (resolver.openOutputStream(created, "wt") ?: throw TransferException(TransferError.PERMISSION)).use {
                copyBytes(input, it) { check() }
            } }
            delete(source)
        } catch (error: Exception) {
            DocumentsContract.deleteDocument(resolver, created)
            throw error
        }
    }
    override fun delete(path: String) {
        require(TransferPaths.normalize(path).isNotEmpty())
        find(path)?.let {
            require(!it.directory)
            if (!DocumentsContract.deleteDocument(resolver, it.uri)) throw TransferException(TransferError.PERMISSION)
        }
    }
}
