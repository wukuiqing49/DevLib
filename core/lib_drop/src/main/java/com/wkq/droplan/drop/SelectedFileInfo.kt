package com.wkq.droplan.drop

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class SelectedFileInfo(val uri: Uri, val name: String, val size: Long?, val readableMetadata: Boolean)

/** 仅查询用户选择的 URI 元信息，不读取或缓存文件正文；在工作线程调用。 */
object SelectedFileMetadata {
    fun read(resolver: ContentResolver, uri: Uri): SelectedFileInfo {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return SelectedFileInfo(uri, "", null, false)
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                SelectedFileInfo(uri, if (name >= 0 && !cursor.isNull(name)) cursor.getString(name).take(1024) else "",
                    if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null, true)
            } ?: SelectedFileInfo(uri, "", null, false)
        } catch (_: Exception) { SelectedFileInfo(uri, "", null, false) }
    }
}
