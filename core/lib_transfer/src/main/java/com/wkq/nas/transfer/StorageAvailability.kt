package com.wkq.nas.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.provider.ProviderFactory
import com.wkq.nas.transfer.provider.classify
import kotlinx.coroutines.*

data class StorageAvailability(val checking: Boolean = false, val checkedAt: Long = 0,
    val error: TransferError? = null, val readOnly: Boolean = false)

/** 检查实际授权目录或共享根，不以互联网验证、端口或保存配置推断在线。 */
class StorageProbe(context: Context) {
    private val app = context.applicationContext
    suspend fun check(id: String): StorageAvailability = withContext(Dispatchers.IO) {
        try {
            val connection = TransferDatabase.get(app).dao().connection(id) ?: throw TransferException(TransferError.NOT_FOUND)
            var readOnly = false
            withTimeout(15000) { runInterruptible {
                if (connection.protocol == "SAF") {
                    val tree = Uri.parse(connection.root)
                    val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree && it.isReadPermission }
                        ?: throw TransferException(TransferError.PERMISSION)
                    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    app.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) throw TransferException(TransferError.STORAGE_UNAVAILABLE)
                        if (cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) throw TransferException(TransferError.NOT_FOUND)
                        readOnly = !grant.isWritePermission || cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0
                    } ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
                } else ProviderFactory(app).open(connection).use { provider ->
                    if (provider.stat("")?.directory != true) throw TransferException(TransferError.NOT_FOUND)
                }
            } }
            SyncDiagnostics(app).event(DiagnosticEvent.PROBE, endpoint = id)
            StorageAvailability(checkedAt = System.currentTimeMillis(), readOnly = readOnly)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val classified = classify(e).first
            val protocol = TransferDatabase.get(app).dao().connection(id)?.protocol
            val error = if (protocol == "SAF" && (e is TimeoutCancellationException || classified == TransferError.NOT_FOUND)) TransferError.STORAGE_UNAVAILABLE
                else if (e is TimeoutCancellationException) TransferError.NETWORK else classified
            SyncDiagnostics(app).event(DiagnosticEvent.PROBE, endpoint = id, reason = error)
            StorageAvailability(checkedAt = System.currentTimeMillis(), error = error)
        }
    }
}
