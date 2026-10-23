package com.wkq.photo.qrtransfer.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wkq.photo.qrtransfer.model.PreparedTransferSource
import com.wkq.photo.qrtransfer.model.TransferFile
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object TransferFilePreparer {
    suspend fun prepare(
        context: Context,
        uris: List<Uri>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PreparedTransferSource> = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        uris.distinct().mapIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val resolver = context.contentResolver
            var displayName: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName = cursor.getString(0)
                    }
                }
            val digest = MessageDigest.getInstance("SHA-256")
            var measuredSize = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    measuredSize += count
                }
            } ?: throw java.io.FileNotFoundException("Unable to read input URI")
            onProgress(index + 1, uris.size)
            PreparedTransferSource(
                uri = uri,
                file = TransferFile(
                    fileId = UUID.randomUUID().toString(),
                    name = sanitizeFileName(displayName ?: uri.lastPathSegment ?: "file.bin"),
                    mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" },
                    size = measuredSize,
                    sha256 = digest.digest().toHex(),
                ),
            )
        }
    }
}

interface ReceivedFileSink {
    suspend fun hasCapacity(totalBytes: Long): Boolean = true

    /** 实现必须先写临时目标，校验通过后再提交最终文件。 */
    suspend fun store(file: TransferFile, input: InputStream): String
}

class IntegrityException(message: String) : java.io.IOException(message)

fun verifyAndCopy(file: TransferFile, input: InputStream, output: java.io.OutputStream) {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    input.use { source ->
        output.use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                target.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
    }
    if (bytes != file.size) throw IntegrityException("File size mismatch")
    if (!digest.digest().toHex().equals(file.sha256, ignoreCase = true)) {
        throw IntegrityException("SHA-256 mismatch")
    }
}

fun sanitizeFileName(value: String): String {
    val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
        .trim().trim('.')
    return leaf.take(180).ifBlank { "received_file" }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
