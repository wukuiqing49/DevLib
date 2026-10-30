package com.wkq.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Uri 读取与复制工具。
 *
 * 主要用于系统 Photo Picker、文件选择器、相册等返回的 content:// Uri。
 */
object UriUtil {

    /** 获取 Uri 对应的展示名，查不到时使用 path 片段或临时文件名。 */
    fun getDisplayName(context: Context, uri: Uri): String {
        return queryString(context, uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment
            ?: "file_${System.currentTimeMillis()}"
    }

    /** 获取 Uri 对应文件大小，查不到时返回 0。 */
    fun getSize(context: Context, uri: Uri): Long {
        return queryLong(context, uri, OpenableColumns.SIZE) ?: 0L
    }

    /** 获取 Uri MIME 类型，ContentResolver 查不到时按文件名兜底推断。 */
    fun getMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri)
            ?: FileUtil.getMimeType(getDisplayName(context, uri))
    }

    fun isContentUri(uri: Uri): Boolean = uri.scheme.equals("content", ignoreCase = true)

    fun isFileUri(uri: Uri): Boolean = uri.scheme.equals("file", ignoreCase = true)

    /** 将 Uri 内容复制到 cacheDir。 */
    fun copyToCache(context: Context, uri: Uri, fileName: String? = null): File? {
        val name = fileName?.takeIf { it.isNotBlank() } ?: getDisplayName(context, uri)
        val target = File(context.cacheDir, name)
        return copyToFile(context, uri, target)
    }

    /** 将 Uri 内容复制到指定文件。 */
    fun copyToFile(context: Context, uri: Uri, target: File): File? {
        return runCatching {
            FileUtil.ensureParentDir(target)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            target
        }.getOrNull()
    }

    /** 读取 Uri 文本内容。适合 txt/json 等小文件。 */
    fun readText(context: Context, uri: Uri, charset: java.nio.charset.Charset = Charsets.UTF_8): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(charset)
            }
        }.getOrNull()
    }

    /** 读取 Uri 字节内容。大文件建议用 copyToFile，避免一次性占用过多内存。 */
    fun readBytes(context: Context, uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(column)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun queryLong(context: Context, uri: Uri, column: String): Long? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(column)
                if (index >= 0) cursor.getLong(index) else null
            }
        }.getOrNull()
    }
}
