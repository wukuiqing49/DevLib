package com.wkq.photo.mediastore

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import java.io.File
import java.io.InputStream

/**
 * 所有发布到 MediaStore 的图片共用的最小完整性校验。
 *
 * 任务执行链仍可做尺寸等业务级校验；这里保证独立功能直接导出时也不会发布空文件、
 * 格式错误或 Android 无法读取的 JPEG/PNG/WebP。
 */
internal object MediaOutputVerifier {

    fun verifySource(file: File, mimeType: String) {
        requireNotEmpty(file)
        verifySignature(file.inputStream().buffered(), file.length(), mimeType)
        verifyDecodedFile(file, mimeType)
    }

    fun verifyPublished(
        resolver: ContentResolver,
        uri: Uri,
        expectedBytes: Long,
        mimeType: String,
    ) {
        resolver.openInputStream(uri)?.buffered()?.use { input ->
            verifySignature(input, expectedBytes, mimeType)
        } ?: throw outputError("Unable to read published output")

        if (isBitmapDecodableMime(mimeType)) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw outputError("Unable to inspect published output")
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw outputError("Published image is not decodable")
            }
        }
    }

    private fun verifyDecodedFile(file: File, mimeType: String) {
        if (!isBitmapDecodableMime(mimeType)) return
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw outputError("Image output is not decodable: ${file.name}")
        }
    }

    private fun verifySignature(input: InputStream, expectedBytes: Long, mimeType: String) {
        val header = ByteArray(12)
        val tail = ByteArray(MAX_TRAILER_BYTES)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var headerCount = 0
        var tailCount = 0
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (headerCount < header.size) {
                val copyCount = minOf(count, header.size - headerCount)
                buffer.copyInto(header, headerCount, 0, copyCount)
                headerCount += copyCount
            }
            tailCount = appendTail(tail, tailCount, buffer, count)
            bytes += count
        }
        if (bytes != expectedBytes) throw outputError("Published output size does not match source")
        if (!matchesSignature(header, headerCount, tail, tailCount, expectedBytes, mimeType)) {
            throw outputError("Output content does not match $mimeType")
        }
    }

    private fun requireNotEmpty(file: File) {
        if (!file.isFile || file.length() <= 0L) throw outputError("Export source is missing or empty")
    }

    private fun matchesSignature(
        header: ByteArray,
        count: Int,
        tail: ByteArray,
        tailCount: Int,
        expectedBytes: Long,
        mimeType: String,
    ): Boolean = when (mimeType) {
        "image/jpeg" -> count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        "image/png" -> count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "image/webp" -> count >= 12 && header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
        "image/tiff" -> hasValidTiffHeader(header, count, expectedBytes)
        "application/pdf" -> hasPdfEnvelope(header, count, tail, tailCount)
        "application/zip" -> hasZipEnvelope(header, count, tail, tailCount)
        else -> true
    }

    private fun appendTail(tail: ByteArray, retained: Int, source: ByteArray, count: Int): Int {
        if (count >= tail.size) {
            source.copyInto(tail, 0, count - tail.size, count)
            return tail.size
        }
        val keep = minOf(retained, tail.size - count)
        if (retained > keep) tail.copyInto(tail, 0, retained - keep, retained)
        source.copyInto(tail, keep, 0, count)
        return keep + count
    }

    private fun hasPdfEnvelope(header: ByteArray, count: Int, tail: ByteArray, tailCount: Int): Boolean {
        if (count < PDF_SIGNATURE.size || !header.copyOfRange(0, PDF_SIGNATURE.size).contentEquals(PDF_SIGNATURE)) return false
        return (0..tailCount - PDF_EOF.size).any { index ->
            tail.copyOfRange(index, index + PDF_EOF.size).contentEquals(PDF_EOF) &&
                (index + PDF_EOF.size until tailCount).all { tail[it].isPdfWhitespace() }
        }
    }

    private fun hasZipEnvelope(header: ByteArray, count: Int, tail: ByteArray, tailCount: Int): Boolean {
        val hasLocalHeader = count >= 4 && header.copyOfRange(0, 4).contentEquals(ZIP_LOCAL_HEADER)
        val hasEmptyArchiveHeader = count >= 4 && header.copyOfRange(0, 4).contentEquals(ZIP_EMPTY_ARCHIVE_HEADER)
        if (!hasLocalHeader && !hasEmptyArchiveHeader) return false
        return (tailCount - ZIP_END_RECORD_BYTES downTo 0).any { index ->
            if (!tail.copyOfRange(index, index + ZIP_END_SIGNATURE.size).contentEquals(ZIP_END_SIGNATURE)) return@any false
            val commentLength = tail[index + 20].unsignedByte() or (tail[index + 21].unsignedByte() shl 8)
            index + ZIP_END_RECORD_BYTES + commentLength == tailCount
        }
    }

    private fun hasValidTiffHeader(header: ByteArray, count: Int, expectedBytes: Long): Boolean {
        if (count < 8) return false
        val littleEndian = header[0] == 0x49.toByte() && header[1] == 0x49.toByte()
        val bigEndian = header[0] == 0x4D.toByte() && header[1] == 0x4D.toByte()
        if (!littleEndian && !bigEndian) return false
        val byteOrderLittleEndian = littleEndian
        return when {
            header[2] == 0x2A.toByte() && header[3] == 0x00.toByte() ||
                header[2] == 0x00.toByte() && header[3] == 0x2A.toByte() -> {
                val offset = unsignedInt(header, 4, byteOrderLittleEndian)
                offset >= 8L && offset < expectedBytes
            }
            count >= 16 && (
                header[2] == 0x2B.toByte() && header[3] == 0x00.toByte() ||
                    header[2] == 0x00.toByte() && header[3] == 0x2B.toByte()
                ) -> {
                val offsetSize = if (byteOrderLittleEndian) {
                    header[4].unsignedByte() or (header[5].unsignedByte() shl 8)
                } else {
                    (header[4].unsignedByte() shl 8) or header[5].unsignedByte()
                }
                val reserved = header[6].unsignedByte() or header[7].unsignedByte()
                val high = unsignedInt(header, if (byteOrderLittleEndian) 12 else 8, byteOrderLittleEndian)
                val low = unsignedInt(header, if (byteOrderLittleEndian) 8 else 12, byteOrderLittleEndian)
                offsetSize == 8 && reserved == 0 && high == 0L && low >= 16L && low < expectedBytes
            }
            else -> false
        }
    }

    private fun unsignedInt(value: ByteArray, offset: Int, littleEndian: Boolean): Long =
        if (littleEndian) {
            value[offset].unsignedByte().toLong() or
                (value[offset + 1].unsignedByte().toLong() shl 8) or
                (value[offset + 2].unsignedByte().toLong() shl 16) or
                (value[offset + 3].unsignedByte().toLong() shl 24)
        } else {
            (value[offset].unsignedByte().toLong() shl 24) or
                (value[offset + 1].unsignedByte().toLong() shl 16) or
                (value[offset + 2].unsignedByte().toLong() shl 8) or
                value[offset + 3].unsignedByte().toLong()
        }

    private fun Byte.unsignedByte(): Int = toInt() and 0xFF
    private fun Byte.isPdfWhitespace(): Boolean = this == 0x20.toByte() || this == 0x09.toByte() || this == 0x0A.toByte() || this == 0x0D.toByte()

    private fun isBitmapDecodableMime(mimeType: String): Boolean = mimeType in BITMAP_DECODABLE_MIME_TYPES

    private fun outputError(message: String) = OperationException(OperationErrorCode.ENCODE_FAILED, message)

    private val BITMAP_DECODABLE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    private val PDF_SIGNATURE = "%PDF-".toByteArray()
    private val PDF_EOF = "%%EOF".toByteArray()
    private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY_ARCHIVE_HEADER = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_END_SIGNATURE = ZIP_EMPTY_ARCHIVE_HEADER
    private const val ZIP_END_RECORD_BYTES = 22
    private const val MAX_TRAILER_BYTES = ZIP_END_RECORD_BYTES + 65_535
}
