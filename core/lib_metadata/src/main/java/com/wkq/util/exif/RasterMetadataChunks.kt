package com.wkq.util.exif

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** PNG/WebP 容器级操作：仅移除指定元数据块，不经过 Bitmap 或重新编码。 */
object RasterMetadataChunks {
    private data class Chunk(val type: String, val start: Long, val data: Long, val size: Long, val end: Long)

    private fun chunks(file: File): List<Chunk> = RandomAccessFile(file, "r").use { input ->
        val format = PhotoFormatPolicy.detect(file)
        require(format == PhotoFormatPolicy.Format.PNG || format == PhotoFormatPolicy.Format.WEBP)
        val png = format == PhotoFormatPolicy.Format.PNG
        if (!png) {
            input.seek(4)
            check(readLe(input) + 8 == input.length()) { "Invalid WebP RIFF length" }
        }
        input.seek(if (png) 8 else 12)
        val result = mutableListOf<Chunk>()
        while (input.filePointer < input.length()) {
            check(result.size < 100_000) { "Too many image chunks" }
            val start = input.filePointer
            check(input.length() - start >= if (png) 12 else 8) { "Truncated image chunk" }
            val size: Long
            val type: String
            if (png) {
                size = input.readInt().toLong() and 0xffffffffL
                type = readType(input)
            } else {
                type = readType(input)
                size = readLe(input)
            }
            val data = input.filePointer
            val end = data + size + if (png) 4 else size % 2
            check(end <= input.length()) { "Truncated image data" }
            check(type != "acTL") { "Animated PNG metadata editing is not supported" }
            if (png) {
                val crc = java.util.zip.CRC32()
                crc.update(type.toByteArray(Charsets.US_ASCII))
                val buffer = ByteArray(8192)
                var left = size
                while (left > 0) {
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    left -= count
                }
                check(crc.value == (input.readInt().toLong() and 0xffffffffL)) { "Invalid PNG CRC" }
            }
            result += Chunk(type, start, data, size, end)
            input.seek(end)
        }
        check(result.count { it.type in setOf("eXIf", "EXIF") } <= 1) { "Multiple EXIF chunks are not supported" }
        if (png) {
            check(result.firstOrNull()?.type == "IHDR" && result.lastOrNull()?.type == "IEND" &&
                result.last().size == 0L && result.count { it.type == "IEND" } == 1 &&
                result.count { it.type == "IHDR" } == 1 && result.any { it.type == "IDAT" }) { "Invalid PNG structure" }
        } else check(result.any { it.type in setOf("VP8 ", "VP8L", "ANMF") }) { "Invalid WebP structure" }
        result
    }

    private fun isText(chunk: Chunk) = chunk.type in setOf("iTXt", "tEXt", "zTXt")

    private fun isXmp(input: RandomAccessFile, chunk: Chunk): Boolean {
        if (chunk.type == "XMP ") return true
        if (!isText(chunk)) return false
        input.seek(chunk.data)
        val keyword = "XML:com.adobe.xmp".toByteArray(Charsets.US_ASCII)
        if (chunk.size < keyword.size + 1) return false
        val prefix = ByteArray(keyword.size)
        input.readFully(prefix)
        return prefix.contentEquals(keyword) && input.read() == 0
    }

    fun countTextOrXmp(file: File, allText: Boolean): Int {
        val entries = chunks(file)
        return RandomAccessFile(file, "r").use { input ->
            entries.count { (allText && isText(it)) || isXmp(input, it) }
        }
    }

    /** 独立读取 TIFF Tag700；ExifInterface 可能优先返回原生 XMP 块而隐藏这一份。 */
    internal fun readExifXmp(file: File): String? {
        val entry = chunks(file).firstOrNull { it.type in setOf("eXIf", "EXIF") } ?: return null
        require(entry.size <= 4 * 1024 * 1024) { "EXIF chunk exceeds safe read limit" }
        var bytes = RandomAccessFile(file, "r").use { input ->
            input.seek(entry.data)
            ByteArray(entry.size.toInt()).also(input::readFully)
        }
        if (bytes.take(6).toByteArray().contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
            bytes = bytes.copyOfRange(6, bytes.size)
        }
        val metadata = org.apache.commons.imaging.formats.tiff.TiffImageParser().getMetadata(
            org.apache.commons.imaging.bytesource.ByteSource.array(bytes),
            org.apache.commons.imaging.formats.tiff.TiffImagingParameters())
            as org.apache.commons.imaging.formats.tiff.TiffImageMetadata
        return metadata.findField(org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants.TIFF_TAG_XMP)
            ?.byteArrayValue?.toString(Charsets.UTF_8)
    }

    fun readNativeXmp(file: File): String? {
        val entries = chunks(file)
        return RandomAccessFile(file, "r").use { input ->
            val chunk = entries.firstOrNull { isXmp(input, it) } ?: return@use null
            check(chunk.size <= 4 * 1024 * 1024) { "XMP chunk is too large" }
            input.seek(chunk.data)
            val bytes = ByteArray(chunk.size.toInt()).also(input::readFully)
            if (chunk.type == "XMP ") return@use bytes.toString(Charsets.UTF_8)
            if (chunk.type != "iTXt") return@use null // 非标准历史存储交给Exif兼容读取。
            var position = bytes.indexOf(0) + 1
            check(position > 0 && position + 2 < bytes.size)
            val compressed = bytes[position++].toInt()
            check(compressed in 0..1 && bytes[position++].toInt() == 0)
            repeat(2) {
                while (position < bytes.size && bytes[position] != 0.toByte()) position++
                check(position < bytes.size)
                position++
            }
            val content = bytes.copyOfRange(position, bytes.size)
            if (compressed == 0) content.toString(Charsets.UTF_8) else {
                java.util.zip.InflaterInputStream(content.inputStream()).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = stream.read(buffer)
                    while (count >= 0) {
                        check(output.size() + count <= 4 * 1024 * 1024) { "Expanded XMP is too large" }
                        output.write(buffer, 0, count)
                        count = stream.read(buffer)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }
    }

    fun writeNativeXmp(file: File, packet: String) {
        val entries = chunks(file)
        val png = PhotoFormatPolicy.detect(file) == PhotoFormatPolicy.Format.PNG
        if (!png) check(entries.any { it.type == "VP8X" }) { "WebP extended header is missing" }
        val payload = if (png) "XML:com.adobe.xmp".toByteArray(Charsets.US_ASCII) +
            ByteArray(5) + packet.toByteArray(Charsets.UTF_8) else packet.toByteArray(Charsets.UTF_8)
        val temp = File.createTempFile("raster_xmp_", ".tmp", file.parentFile)
        try {
            RandomAccessFile(file, "r").use { input -> RandomAccessFile(temp, "rw").use { output ->
                copy(input, output, 0, if (png) 8 else 12)
                entries.forEach { chunk ->
                    if (png && chunk.type == "IEND") writeChunk(output, "iTXt", payload, true)
                    val start = output.filePointer
                    copy(input, output, chunk.start, chunk.end - chunk.start)
                    if (!png && chunk.type == "VP8X") {
                        val end = output.filePointer
                        output.seek(start + 8)
                        val flags = output.readUnsignedByte()
                        output.seek(start + 8)
                        output.writeByte(flags or 4)
                        output.seek(end)
                    }
                }
                if (!png) {
                    writeChunk(output, "XMP ", payload, false)
                    val length = output.length() - 8
                    check(length <= 0xffffffffL)
                    output.seek(4)
                    repeat(4) { output.writeByte((length shr (8 * it)).toInt()) }
                }
                output.fd.sync()
            } }
            chunks(temp)
            replaceVerified(file, temp)
        } finally { temp.delete() }
    }

    private fun writeChunk(output: RandomAccessFile, type: String, payload: ByteArray, png: Boolean) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        if (png) output.writeInt(payload.size)
        output.write(typeBytes)
        if (!png) repeat(4) { output.writeByte(payload.size shr (8 * it)) }
        output.write(payload)
        if (png) {
            val crc = java.util.zip.CRC32()
            crc.update(typeBytes)
            crc.update(payload)
            output.writeInt(crc.value.toInt())
        } else if (payload.size % 2 != 0) output.writeByte(0)
    }

    fun removeXmp(file: File, allText: Boolean = false) = removeMetadata(file, allText, false, false)

    fun removePrivacyMetadata(file: File, iptc: Boolean, exif: Boolean, personal: Boolean) =
        removeMetadata(file, iptc, exif, personal)

    fun countPrivacyText(file: File, iptc: Boolean, exif: Boolean, personal: Boolean): Int {
        val entries = chunks(file)
        return RandomAccessFile(file, "r").use { input ->
            entries.count { !isXmp(input, it) && selectedText(input, it, iptc, exif, personal) }
        }
    }

    private fun selectedText(input: RandomAccessFile, chunk: Chunk, all: Boolean, exif: Boolean, personal: Boolean): Boolean {
        if (!isText(chunk)) return false
        if (all) return true
        input.seek(chunk.data)
        val name = StringBuilder()
        repeat(minOf(chunk.size, 80).toInt()) {
            val next = input.read()
            if (next == 0) return MetadataPrivacyRules.selectedName(name.toString(), exif, personal) ||
                ((exif || personal) && name.startsWith("Raw profile type", ignoreCase = true))
            name.append(next.toChar())
        }
        // PNG 文本关键字超过规范限制时，不能证明它不包含选中的信息。
        check(!exif && !personal) { "Invalid PNG text keyword" }
        return false
    }

    private fun removeMetadata(file: File, allText: Boolean, exif: Boolean, personal: Boolean) {
        val entries = chunks(file)
        val png = PhotoFormatPolicy.detect(file) == PhotoFormatPolicy.Format.PNG
        val temp = File.createTempFile("raster_metadata_", ".tmp", file.parentFile)
        try {
            RandomAccessFile(file, "r").use { input ->
                RandomAccessFile(temp, "rw").use { output ->
                    copy(input, output, 0, if (png) 8 else 12)
                    entries.forEach { chunk ->
                        if (!(selectedText(input, chunk, allText, exif, personal) || isXmp(input, chunk))) {
                            val outputStart = output.filePointer
                            copy(input, output, chunk.start, chunk.end - chunk.start)
                            if (!png && chunk.type == "VP8X") {
                                check(chunk.size == 10L) { "Invalid VP8X size" }
                                val end = output.filePointer
                                output.seek(outputStart + 8)
                                val flags = output.readUnsignedByte()
                                output.seek(outputStart + 8)
                                output.writeByte(flags and 0xfb) // 仅清除独立XMP标记。
                                output.seek(end)
                            }
                        }
                    }
                    if (!png) {
                        val length = output.length() - 8
                        check(length <= 0xffffffffL)
                        output.seek(4)
                        repeat(4) { output.writeByte((length shr (8 * it)).toInt()) }
                    }
                    output.fd.sync()
                }
            }
            chunks(temp) // 完整结构读回，失败不替换工作副本。
            replaceVerified(file, temp)
        } finally { temp.delete() }
    }

    internal fun replaceVerified(file: File, replacement: File) {
        val backup = File.createTempFile("raster_backup_", ".tmp", file.parentFile)
        if (!backup.delete()) throw java.io.IOException("Unable to prepare raster backup")
        if (!file.renameTo(backup)) throw java.io.IOException("Unable to back up raster file")
        try {
            if (!replacement.renameTo(file)) throw java.io.IOException("Unable to replace raster metadata")
            backup.delete()
        } catch (error: Throwable) {
            if (!((!file.exists() || file.delete()) && backup.renameTo(file))) {
                throw java.io.IOException("Unable to restore raster file", error)
            }
            throw error
        }
    }

    /** 覆盖图像、动画帧/时序、色彩和透明度块。静态VP8X可因添加元数据而新建，动画VP8X仅忽略元数据位。 */
    fun protectedDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = chunks(file)
        RandomAccessFile(file, "r").use { input ->
            entries.filterNot { it.type in setOf("eXIf", "EXIF", "XMP ") || isText(it) ||
                (it.type == "VP8X" && run { input.seek(it.data); input.readUnsignedByte() and 2 == 0 })
            }.forEach { chunk ->
                digest.update(chunk.type.toByteArray(Charsets.US_ASCII))
                digest.update(chunk.size.toString().toByteArray(Charsets.US_ASCII))
                input.seek(chunk.data)
                val buffer = ByteArray(8192)
                var left = chunk.size
                var first = true
                while (left > 0) {
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    if (chunk.type == "VP8X" && first) buffer[0] = (buffer[0].toInt() and 0xf3).toByte()
                    digest.update(buffer, 0, count)
                    left -= count
                    first = false
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readType(input: RandomAccessFile): String = ByteArray(4).also(input::readFully)
        .toString(Charsets.US_ASCII)
    private fun readLe(input: RandomAccessFile): Long {
        var result = 0L
        repeat(4) { result = result or (input.readUnsignedByte().toLong() shl (8 * it)) }
        return result
    }
    private fun copy(input: RandomAccessFile, output: RandomAccessFile, start: Long, size: Long) {
        input.seek(start)
        var left = size
        val buffer = ByteArray(8192)
        while (left > 0) {
            val count = minOf(left, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            left -= count
        }
    }
}
