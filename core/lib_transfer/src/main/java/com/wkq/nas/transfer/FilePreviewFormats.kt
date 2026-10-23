package com.wkq.nas.transfer

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class FilePreviewType { IMAGE, TEXT, PDF, VIDEO, OTHER }

object FilePreviewFormats {
    fun type(path: String) = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> FilePreviewType.IMAGE
        "txt", "md", "csv", "json", "xml", "log", "yaml", "yml" -> FilePreviewType.TEXT
        "pdf" -> FilePreviewType.PDF
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp" -> FilePreviewType.VIDEO
        else -> FilePreviewType.OTHER
    }
    fun decodeText(bytes: ByteArray, encoding: String? = null): String {
        require(bytes.size <= 1024 * 1024)
        var offset = 0
        val charset = if (encoding != null) Charset.forName(encoding) else when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> { offset = 3; Charsets.UTF_8 }
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> { offset = 2; Charsets.UTF_16LE }
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> { offset = 2; Charsets.UTF_16BE }
            else -> Charsets.UTF_8
        }
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString().removePrefix("\uFEFF")
    }
}
