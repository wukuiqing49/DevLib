package com.wkq.usb

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/** 只操作用户授予的树 URI，保留 content URI 身份而不是转换成文件路径。 */
class UsbStorage(context: Context, root: String) {
    private val tree = Uri.parse(root)
    init {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Expected a granted content tree URI" }
    }
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
        } ?: throw UsbException(UsbError.STORAGE_UNAVAILABLE)
        return result
    }
    private fun find(path: String): Document? {
        resolver.query(rootDocument, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (!it.moveToFirst()) throw UsbException(UsbError.STORAGE_UNAVAILABLE)
            if (it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) throw UsbException(UsbError.NOT_FOUND)
        } ?: throw UsbException(UsbError.STORAGE_UNAVAILABLE)
        var current = Document(rootDocument, "", true, 0, -1)
        UsbPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach { part ->
            if (!current.directory) throw UsbException(UsbError.CONFLICT)
            current = children(current.uri).firstOrNull { it.name == part } ?: return null
        }
        return current
    }
    fun stat(path: String) = find(path)?.let { UsbEntry(UsbPaths.normalize(path), it.directory, it.size, it.modified) }
    fun list(path: String): List<UsbEntry> {
        val parent = find(path) ?: throw UsbException(UsbError.NOT_FOUND)
        return children(parent.uri).map { UsbEntry(UsbPaths.join(path, it.name), it.directory, it.size, it.modified) }
    }
    fun read(path: String): InputStream = resolver.openInputStream(find(path)?.uri ?: throw UsbException(UsbError.NOT_FOUND))
        ?: throw UsbException(UsbError.STORAGE_UNAVAILABLE)
    fun visit(path: String, accept: (UsbEntry) -> Unit) {
        val parent = find(path) ?: throw UsbException(UsbError.NOT_FOUND)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val directory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                // 与 stat/list 保持相同的目录快照，避免非零目录大小被误判为源已变化。
                accept(UsbEntry(UsbPaths.join(path, cursor.getString(0)), directory,
                    if (cursor.isNull(2)) -1 else cursor.getLong(2), if (cursor.isNull(3)) -1 else cursor.getLong(3)))
            }
        } ?: throw UsbException(UsbError.STORAGE_UNAVAILABLE)
    }
    fun mkdirs(path: String) {
        var current = rootDocument
        UsbPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach { part ->
            val existing = children(current).firstOrNull { it.name == part }
            if (existing != null && !existing.directory) throw UsbException(UsbError.CONFLICT)
            current = existing?.uri ?: DocumentsContract.createDocument(resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, part)
                ?: throw UsbException(UsbError.PERMISSION)
        }
    }
    private fun create(path: String): Uri {
        if (find(path) != null) throw UsbException(UsbError.CONFLICT)
        val parent = find(UsbPaths.parent(path)) ?: throw UsbException(UsbError.NOT_FOUND)
        val created = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", UsbPaths.name(path))
            ?: throw UsbException(UsbError.PERMISSION)
        // Provider 自动改名表示存在冲突；只删除本次创建的文档。
        val exact = children(parent.uri).firstOrNull { it.uri == created }
        if (exact?.name != UsbPaths.name(path)) {
            DocumentsContract.deleteDocument(resolver, created)
            throw UsbException(UsbError.CONFLICT)
        }
        return created
    }
    /** 只创建新文档；失败或取消时尽力删除本次创建的目标，清理失败附加到原异常。 */
    fun write(path: String, input: InputStream, progress: (Long) -> Unit = {}) {
        val created = create(path)
        try {
            (resolver.openOutputStream(created, "wt") ?: throw UsbException(UsbError.PERMISSION)).use { copyBytes(input, it, progress) }
        } catch (error: Exception) {
            cleanupCreated(created, error)
            throw error
        }
    }
    /** 复制成功后才删除源；失败清理不能覆盖原始错误，移动不保证原子性。 */
    fun moveNew(source: String, target: String, check: () -> Unit = {}) {
        check()
        val created = create(target)
        try {
            read(source).use { input -> (resolver.openOutputStream(created, "wt") ?: throw UsbException(UsbError.PERMISSION)).use {
                copyBytes(input, it) { check() }
            } }
            check()
            delete(source)
        } catch (error: Exception) {
            cleanupCreated(created, error)
            throw error
        }
    }
    /** 只清理明确属于本次操作的 URI，不按文件名删除，保留原错误及清理证据。 */
    private fun cleanupCreated(created: Uri, original: Exception) {
        try {
            if (!DocumentsContract.deleteDocument(resolver, created)) {
                original.addSuppressed(UsbException(UsbError.PERMISSION))
            }
        } catch (cleanup: Exception) {
            if (cleanup !== original) original.addSuppressed(cleanup)
        }
    }
    fun delete(path: String) {
        require(UsbPaths.normalize(path).isNotEmpty())
        find(path)?.let {
            require(!it.directory)
            if (!DocumentsContract.deleteDocument(resolver, it.uri)) throw UsbException(UsbError.PERMISSION)
        }
    }
    fun deleteEmptyDirectory(path: String) {
        // 只能删除授权树内的空目录；非空目录由任务引擎按已确认的预览逐项清理。
        require(UsbPaths.normalize(path).isNotEmpty())
        val document = find(path) ?: return
        if (!document.directory) throw UsbException(UsbError.CONFLICT)
        resolver.query(document.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (!it.moveToFirst()) throw UsbException(UsbError.STORAGE_UNAVAILABLE)
            if (it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE == 0) {
                throw UsbException(UsbError.PERMISSION)
            }
        } ?: throw UsbException(UsbError.STORAGE_UNAVAILABLE)
        if (children(document.uri).isNotEmpty()) throw UsbException(UsbError.SOURCE_CHANGED)
        if (!DocumentsContract.deleteDocument(resolver, document.uri)) throw UsbException(UsbError.PERMISSION)
    }
}

/** 有界复制，每块检查线程中断，回调取消异常保持原样。 */
private fun copyBytes(input: InputStream, output: java.io.OutputStream, progress: (Long) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    progress(0)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("USB operation interrupted")
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        bytes += count
        progress(bytes)
    }
    output.flush()
}
