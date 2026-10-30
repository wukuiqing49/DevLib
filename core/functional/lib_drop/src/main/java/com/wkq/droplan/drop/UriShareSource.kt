package com.wkq.droplan.drop

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** 不把 content URI 转成路径；默认拒绝无法确认本地读取的第三方/云提供者。 */
class UriShareSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val id: String,
    override val displayName: String,
    override val mimeType: String,
    override val length: Long?,
    override val supportsRandomAccess: Boolean,
) : ShareSource {
    override fun openStream(): InputStream = resolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        ?: throw IOException("source_unavailable")

    override fun openRange(offset: Long, length: Long): InputStream {
        require(offset >= 0 && length >= 0 && offset <= Long.MAX_VALUE - length)
        check(supportsRandomAccess)
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("source_unavailable")
        try {
            val input = descriptor.createInputStream()
            try {
                val absoluteOffset = Math.addExact(descriptor.startOffset, offset)
                if (Os.lseek(descriptor.fileDescriptor, absoluteOffset, OsConstants.SEEK_SET) != absoluteOffset)
                    throw IOException("seek_failed")
                return object : FilterInputStream(input) {
                    private var remaining = length
                    override fun read(): Int {
                        val b = ByteArray(1)
                        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (remaining == 0L) return -1
                        val read = `in`.read(b, off, minOf(len.toLong(), remaining).toInt())
                        if (read > 0) remaining -= read
                        return read
                    }
                }
            } catch (error: Exception) { input.close(); throw error }
        } catch (error: Exception) { descriptor.close(); throw error }
    }

    companion object {
        private val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents",
            "com.android.providers.media.documents", "media", "com.android.providers.media.module")

        /** 在工作线程调用；应用仅对用户通过 SAF 明确授权的 URI 调用。 */
        fun create(resolver: ContentResolver, uri: Uri): UriShareSource {
            require(uri.scheme == "content" && uri.authority in localAuthorities) { "select_local_file" }
            var name = "download"
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
            var seek = false
            val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("source_unavailable")
            descriptor.use {
                if (it.declaredLength >= 0) size = it.declaredLength
                seek = runCatching {
                    val stat = Os.fstat(it.fileDescriptor)
                    if (!OsConstants.S_ISREG(stat.st_mode)) false else {
                        if (size == null) size = (stat.st_size - it.startOffset).takeIf { value -> value >= 0 }
                        Os.lseek(it.fileDescriptor, it.startOffset, OsConstants.SEEK_SET) == it.startOffset
                    }
                }.getOrDefault(false)
                // 确认至少可打开并读取，不复制整个文件；零字节文件同样合法。
                it.createInputStream().use { stream -> if (size != 0L) stream.read() }
            }
            return UriShareSource(resolver, uri, ShareEngine.newFileId(), name.take(1024),
                resolver.getType(uri) ?: "application/octet-stream", size, seek && size != null)
        }
    }
}
