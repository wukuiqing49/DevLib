package com.wkq.iptc.feature.press.metadata

import android.graphics.BitmapFactory
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.dev.metadata.model.MetadataTemplate
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

/** 使用 ExifInterface 写入容器元数据，编码图像块必须保持不变。 */
object EmbeddedContainerMetadata {
    const val MODE = "embedded_xmp"
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun format(file: File): String? {
        val header = ByteArray(12)
        if (file.inputStream().use { it.read(header) } < 12) return null
        return when {
            header.copyOfRange(0, 8).contentEquals(pngSignature) -> "png"
            header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }

    fun readXmp(file: File): String? {
        if (format(file) == "webp") {
            DataInputStream(file.inputStream().buffered()).use { input ->
                input.skipBytes(12)
                var remaining = file.length() - 12
                while (remaining >= 8) {
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                    val padded = length + (length and 1)
                    require(padded <= remaining - 8) { "Truncated WebP metadata" }
                    if (type == "XMP ") {
                        require(length <= 8 * 1024 * 1024) { "XMP packet exceeds size limit" }
                        return ByteArray(length.toInt()).also(input::readFully).toString(Charsets.UTF_8)
                    }
                    var left = padded
                    while (left > 0) {
                        val count = input.skip(left)
                        require(count > 0)
                        left -= count
                    }
                    remaining -= 8 + padded
                }
            }
        }
        return ExifInterface(file).getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)
    }

    /** WebP 的 XMP 使用独立标准块，不能只放在 EXIF TIFF tag 700 中。 */
    private fun writeWebpXmp(file: File, packet: String, checkCancelled: () -> Unit) {
        val bytes = packet.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "XMP packet exceeds size limit" }
        val temp = File.createTempFile("xmp_", ".webp", file.parentFile)
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                java.io.DataOutputStream(temp.outputStream().buffered()).use { output ->
                    output.write(ByteArray(12).also(input::readFully))
                    var remaining = file.length() - 12
                    var hasExtendedHeader = false
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        checkCancelled()
                        require(remaining >= 8)
                        val type = ByteArray(4).also(input::readFully)
                        val name = type.toString(Charsets.US_ASCII)
                        val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                        val padded = length + (length and 1)
                        require(padded <= remaining - 8)
                        val keep = name != "XMP "
                        if (keep) { output.write(type); output.writeInt(Integer.reverseBytes(length.toInt())) }
                        var left = padded
                        if (name == "VP8X") {
                            require(length == 10L)
                            val data = ByteArray(10).also(input::readFully)
                            data[0] = (data[0].toInt() or 0x04).toByte()
                            output.write(data)
                            hasExtendedHeader = true
                            left = 0
                        }
                        while (left > 0) {
                            checkCancelled()
                            val count = minOf(left, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            if (keep) output.write(buffer, 0, count)
                            left -= count
                        }
                        remaining -= 8 + padded
                    }
                    check(hasExtendedHeader) { "Missing WebP extended header" }
                    output.write("XMP ".toByteArray(Charsets.US_ASCII))
                    output.writeInt(Integer.reverseBytes(bytes.size))
                    output.write(bytes)
                    if (bytes.size % 2 != 0) output.writeByte(0)
                }
            }
            java.io.RandomAccessFile(temp, "rw").use {
                require(temp.length() - 8 <= 0xffffffffL)
                it.seek(4)
                it.writeInt(Integer.reverseBytes((temp.length() - 8).toInt()))
                it.fd.sync()
            }
            checkCancelled()
            if (!temp.renameTo(file)) throw java.io.IOException("Cannot commit WebP XMP copy")
        } finally { temp.delete() }
    }

    /** 清除标准 EXIF/XMP 和 PNG 文本块，方向单独保留，不解码像素。仅操作调用方的工作副本。 */
    fun scrubPrivacy(file: File, checkCancelled: () -> Unit = {}) {
        val kind = requireNotNull(format(file)) { "Unsupported privacy scrub format" }
        val original = fingerprint(file, checkCancelled, ignorePngText = true)
        val bounds = dimensions(file)
        val exif = ExifInterface(file)
        val orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
        val webpHeader = extendedHeader(file)
        val temp = File.createTempFile("privacy_", ".$kind", file.parentFile)
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                java.io.DataOutputStream(temp.outputStream().buffered()).use { output ->
                    val header = ByteArray(if (kind == "png") 8 else 12).also(input::readFully)
                    output.write(header)
                    var remaining = file.length() - header.size
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        checkCancelled()
                        val type: ByteArray
                        val rawLength = input.readInt()
                        val length: Long
                        if (kind == "png") {
                            length = rawLength.toLong() and 0xffffffffL
                            type = ByteArray(4).also(input::readFully)
                        } else {
                            type = java.nio.ByteBuffer.allocate(4).putInt(rawLength).array()
                            length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                        }
                        val name = type.toString(Charsets.US_ASCII)
                        val extra = if (kind == "png") 4L else length and 1
                        require(length + extra <= remaining - 8) { "Truncated image chunk" }
                        val remove = if (kind == "png") name in listOf("eXIf", "iTXt", "tEXt", "zTXt")
                            else name in listOf("EXIF", "XMP ")
                        if (!remove) {
                            if (kind == "png") { output.writeInt(rawLength); output.write(type) }
                            else { output.write(type); output.writeInt(Integer.reverseBytes(length.toInt())) }
                        }
                        var left = length + extra
                        if (kind == "webp" && name == "VP8X") {
                            require(length == 10L)
                            val data = ByteArray(10).also(input::readFully)
                            data[0] = (data[0].toInt() and 0xf3).toByte()
                            output.write(data)
                            left = 0
                        }
                        while (left > 0) {
                            checkCancelled()
                            val count = minOf(left, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            if (!remove) output.write(buffer, 0, count)
                            left -= count
                        }
                        remaining -= 8 + length + extra
                    }
                }
            }
            if (kind == "webp") java.io.RandomAccessFile(temp, "rw").use {
                require(temp.length() - 8 <= 0xffffffffL)
                it.seek(4)
                it.writeInt(Integer.reverseBytes((temp.length() - 8).toInt()))
            }
            if (orientation != null) ExifInterface(temp).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                setAttribute(ExifInterface.TAG_LIGHT_SOURCE, null)
                saveAttributes()
            }
            check(original.contentEquals(fingerprint(temp, checkCancelled, ignorePngText = true))) { "Encoded image data changed" }
            check(bounds == dimensions(temp)) { "Image dimensions changed" }
            check(ExifInterface(temp).getAttribute(ExifInterface.TAG_ORIENTATION) == orientation) { "Image orientation changed" }
            check(readXmp(temp).isNullOrBlank()) { "XMP removal failed" }
            if (webpHeader != null) check(webpHeader.contentEquals(extendedHeader(temp))) { "WebP canvas changed" }
            checkCancelled()
            check(temp.renameTo(file)) { "Cannot commit privacy scrub copy" }
        } finally { temp.delete() }
    }

    fun write(file: File, template: MetadataTemplate, location: Location?,
              checkCancelled: () -> Unit = {}): MetadataVerificationResult {
        require(format(file) != null) { "Unsupported embedded metadata format" }
        val original = fingerprint(file, checkCancelled)
        val rasterDigest = com.wkq.util.exif.RasterMetadataChunks.protectedDigest(file)
        val bounds = dimensions(file)
        require(bounds.first > 0 && bounds.second > 0) { "Invalid image dimensions" }
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        val webpHeader = extendedHeader(file)
        val effectiveTemplate = NativeIptcTemplate.forXmp(template)
        val packet = XmpWriter.buildSidecarPacket(effectiveTemplate, MetadataXmpStore.read(file), file)
        checkCancelled()
        ExifWriter.writeBasicMetadata(file, location).getOrThrow()
        MetadataXmpStore.write(file, packet).getOrThrow()
        checkCancelled()
        try {
        check(original.contentEquals(fingerprint(file, checkCancelled))) { "Encoded image data changed" }
        check(bounds == dimensions(file)) { "Image dimensions changed" }
        check(orientation == ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            "Image orientation changed"
        }
        if (webpHeader != null) check(webpHeader.contentEquals(extendedHeader(file))) { "WebP canvas changed" }
        check(com.wkq.util.exif.RasterMetadataChunks.protectedDigest(file) == rasterDigest) {
            "Image payload changed"
        }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw MetadataIntegrityException("Raster image integrity verification failed", failure)
        }
        return MetadataVerifier.verifyEmbeddedXmp(file, effectiveTemplate, location != null).also {
            check(it.error == null && it.exifVerified && it.xmpVerified) { "Embedded metadata verification failed" }
        }
    }

    private fun dimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    /** 完整性指纹同时覆盖 WebP 画布与非元数据标志，不能只核对压缩像素块。 */
    fun signatureFingerprint(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(fingerprint(file))
        extendedHeader(file)?.let { update(it) }
        digest()
    }

    private fun extendedHeader(file: File): ByteArray? {
        if (format(file) != "webp") return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.skipBytes(12)
            var remaining = file.length() - 12
            while (remaining >= 8) {
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val padded = length + (length and 1)
                require(padded <= remaining - 8)
                if (type == "VP8X") {
                    require(length == 10L)
                    return ByteArray(10).also { input.readFully(it); it[0] = (it[0].toInt() and 0xf3).toByte() }
                }
                var skipped = 0L
                while (skipped < padded) {
                    val count = input.skip(padded - skipped)
                    require(count > 0)
                    skipped += count
                }
                remaining -= 8 + padded
            }
        }
        return null
    }

    /** 流式核对编码块、ICC、透明度和动画块，不解码或重新压缩像素。 */
    fun fingerprint(file: File, checkCancelled: () -> Unit = {}, ignorePngText: Boolean = false): ByteArray {
        val kind = requireNotNull(format(file))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        DataInputStream(file.inputStream().buffered()).use { input ->
            val headerSize = if (kind == "png") 8 else 12
            val header = ByteArray(headerSize).also(input::readFully)
            if (kind == "webp") {
                val size = (header[4].toLong() and 255) or ((header[5].toLong() and 255) shl 8) or
                    ((header[6].toLong() and 255) shl 16) or ((header[7].toLong() and 255) shl 24)
                require(size + 8 == file.length()) { "Invalid RIFF length" }
            }
            var remaining = file.length() - headerSize
            var image = false
            var ended = false
            var first = true
            while (remaining > 0) {
                checkCancelled()
                require(remaining >= if (kind == "png") 12 else 8)
                val typeBytes: ByteArray
                val length: Long
                if (kind == "png") {
                    length = input.readInt().toLong() and 0xffffffffL
                    typeBytes = ByteArray(4).also(input::readFully)
                } else {
                    typeBytes = ByteArray(4).also(input::readFully)
                    length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                }
                val type = typeBytes.toString(Charsets.US_ASCII)
                val extra = if (kind == "png") 4L else length and 1
                require(length + extra <= remaining - 8) { "Truncated image chunk" }
                if (kind == "png" && first) require(type == "IHDR" && length == 13L)
                first = false
                val prefix = if (kind == "png" && type == "iTXt") ByteArray(minOf(length, 80).toInt())
                    .also(input::readFully) else ByteArray(0)
                val terminator = prefix.indexOf(0)
                val xmp = terminator >= 0 && prefix.copyOfRange(0, terminator)
                    .toString(Charsets.US_ASCII) == "XML:com.adobe.xmp"
                val metadata = if (kind == "png") type == "eXIf" || xmp ||
                    (ignorePngText && type in listOf("iTXt", "tEXt", "zTXt"))
                    else type in listOf("EXIF", "XMP ", "VP8X")
                if (!metadata) {
                    digest.update(typeBytes)
                    digest.update(byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(),
                        (length shr 8).toByte(), length.toByte()))
                    digest.update(prefix)
                }
                var left = length - prefix.size + extra
                while (left > 0) {
                    checkCancelled()
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    if (!metadata) digest.update(buffer, 0, count)
                    left -= count
                }
                if (type in listOf("IDAT", "VP8 ", "VP8L", "ANMF")) image = true
                remaining -= 8 + length + extra
                if (kind == "png" && type == "IEND") {
                    require(length == 0L && remaining == 0L)
                    ended = true
                }
            }
            require(image && (kind != "png" || ended)) { "Missing encoded image" }
        }
        return digest.digest()
    }
}
