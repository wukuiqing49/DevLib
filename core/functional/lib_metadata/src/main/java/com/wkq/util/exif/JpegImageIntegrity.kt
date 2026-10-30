package com.wkq.util.exif

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** 流式保护 JPEG 编码、ICC/Adobe 色彩段和显示方向，不解码或重新压缩像素。 */
object JpegImageIntegrity {
    data class Snapshot(val imageHash: String, val width: Int, val height: Int,
                        val orientation: Int, val colorSpace: String?)

    fun snapshot(file: File): Snapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        var width = 0
        var height = 0
        var scan = false
        RandomAccessFile(file, "r").use { input ->
            check(input.readUnsignedShort() == 0xffd8) { "Invalid JPEG header" }
            val buffer = ByteArray(65536)
            var segments = 0
            while (input.filePointer < input.length()) {
                check(++segments <= 100_000) { "Too many JPEG segments" }
                check(input.readUnsignedByte() == 0xff) { "Invalid JPEG marker" }
                var marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                check(marker !in setOf(0, 0xd8, 0xd9) && marker !in 0xd0..0xd7) { "Unexpected JPEG marker" }
                val length = input.readUnsignedShort()
                check(length >= 2 && input.filePointer + length - 2 <= input.length()) { "Truncated JPEG segment" }
                val start = input.filePointer
                if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                    check(length >= 8) { "Invalid JPEG frame" }
                    input.readUnsignedByte()
                    height = input.readUnsignedShort()
                    width = input.readUnsignedShort()
                    input.seek(start)
                }
                if (marker !in setOf(0xe1, 0xed, 0xfe)) {
                    digest.update(byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()))
                    var left = (length - 2).toLong()
                    if (marker == 0xda) {
                        // 从首个 SOS 起保留所有后续扫描，覆盖渐进 JPEG，且不全量加载大图。
                        left = input.length() - start
                        scan = true
                    }
                    while (left > 0) {
                        val size = minOf(left, buffer.size.toLong()).toInt()
                        input.readFully(buffer, 0, size)
                        digest.update(buffer, 0, size)
                        left -= size
                    }
                    if (scan) break
                } else input.seek(start + length - 2)
            }
        }
        check(scan && width > 0 && height > 0) { "Incomplete JPEG image" }
        val exif = ExifInterface(file)
        return Snapshot(digest.digest().joinToString("") { "%02x".format(it) }, width, height,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            exif.getAttribute(ExifInterface.TAG_COLOR_SPACE))
    }

    fun verify(file: File, before: Snapshot, preserveOrientation: Boolean = true) {
        try {
        val after = snapshot(file)
        check(after.imageHash == before.imageHash && after.width == before.width && after.height == before.height &&
            after.colorSpace == before.colorSpace && (!preserveOrientation || after.orientation == before.orientation)) {
            "JPEG image data, dimensions or rendering properties changed"
        }
        } catch (failure: Exception) {
            throw com.wkq.iptc.feature.press.metadata.MetadataIntegrityException("JPEG integrity verification failed", failure)
        }
    }
}
