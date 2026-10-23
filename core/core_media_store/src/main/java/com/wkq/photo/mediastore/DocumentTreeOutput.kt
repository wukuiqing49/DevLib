package com.wkq.photo.mediastore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** 对 SAF 目录执行“临时文档写入 -> 重命名提交”，避免暴露半成品。 */
object DocumentTreeOutput {
    fun write(
        context: Context,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        block: (OutputStream) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val finalName = resolveAvailableName(context, treeUri, parentId, safeName(displayName))
        val temporaryName = ".${UUID.randomUUID()}.$finalName.partial"
        val temporary = DocumentsContract.createDocument(resolver, parent, mimeType, temporaryName)
            ?: throw IOException("Unable to create destination document")
        try {
            resolver.openOutputStream(temporary, "wt")?.use(block)
                ?: throw IOException("Unable to open destination document")
            return DocumentsContract.renameDocument(resolver, temporary, finalName)
                ?: throw IOException("Unable to commit destination document")
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            throw error
        }
    }

    private fun resolveAvailableName(context: Context, treeUri: Uri, parentId: String, requested: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(existing::add)
        }
        if (requested !in existing) return requested
        val extension = requested.substringAfterLast('.', "").takeIf(String::isNotBlank)
        val base = if (extension == null) requested else requested.removeSuffix(".$extension")
        for (index in 1..9_999) {
            val candidate = if (extension == null) "$base ($index)" else "$base ($index).$extension"
            if (candidate !in existing) return candidate
        }
        throw IOException("Unable to resolve destination name")
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
        .trim().trim('.').take(180).ifBlank { "received_file" }
}
