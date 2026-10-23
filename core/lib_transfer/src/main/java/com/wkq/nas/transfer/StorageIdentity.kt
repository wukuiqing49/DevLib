package com.wkq.nas.transfer

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.net.URI
import java.util.Locale

/** 用于拒绝同源或源内目标；未知 SAF Provider 不推断不透明 Document ID 的层级。 */
internal object StorageIdentity {
    fun validate(source: ConnectionConfig, sourcePath: String, target: ConnectionConfig, targetPath: String, directory: Boolean) {
        if (source.protocol != target.protocol) return
        if (source.protocol == StorageProtocol.SAF) {
            val from = Uri.parse(source.root)
            val to = Uri.parse(target.root)
            if (from.authority != to.authority) return
            if (DocumentsContract.getTreeDocumentId(from) != DocumentsContract.getTreeDocumentId(to) &&
                from.authority != "com.android.externalstorage.documents") throw TransferException(TransferError.UNSUPPORTED)
        }
        val from = identity(source, sourcePath)
        val to = identity(target, targetPath)
        require(from != to && (!directory || !to.startsWith("$from/"))) { "Target equals or is inside source" }
    }

    fun identity(config: ConnectionConfig, path: String): String {
        val relative = TransferPaths.normalize(path)
        val value = when (config.protocol) {
            StorageProtocol.LOCAL -> File(config.root, relative).canonicalPath.replace('\\', '/')
            StorageProtocol.SAF -> {
                val uri = Uri.parse(config.root)
                "${uri.authority}/${DocumentsContract.getTreeDocumentId(uri).trimEnd('/')}/$relative"
            }
            else -> {
                val uri = URI(config.root)
                val port = if (uri.port > 0) uri.port else when (config.protocol) {
                    StorageProtocol.SMB -> 445; StorageProtocol.SFTP -> 22; else -> 443
                }
                val root = TransferPaths.normalize(uri.path.orEmpty().trim('/'))
                "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host.lowercase(Locale.ROOT)}:$port/${TransferPaths.join(root, relative)}"
            }
        }.trimEnd('/')
        return if (config.protocol == StorageProtocol.SMB) value.lowercase(Locale.ROOT) else value
    }
}
