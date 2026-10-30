package com.wkq.iptc.feature.press.metadata

import androidx.exifinterface.media.ExifInterface
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.File

/** JPEG XMP 的唯一读写入口，只写标准单段 APP1 XMP。 */
object JpegXmpStore {

    fun read(photoFile: File): String {
        val app1Xmp = runCatching {
            JpegImageParser().getXmpXml(
                ByteSource.file(photoFile),
                JpegImagingParameters()
            )
        }.getOrNull().orEmpty()
        if (app1Xmp.isNotBlank()) return app1Xmp

        // 兼容历史文件中存放在 EXIF Tag 700 的 XMP。
        return runCatching {
            ExifInterface(photoFile).getAttributeBytes(ExifInterface.TAG_XMP)
                ?.let { String(it, Charsets.UTF_8) }
                .orEmpty()
        }.getOrDefault("")
    }

    fun write(photoFile: File, packet: String): Result<Unit> = runCatching {
        require(photoFile.isFile) { "JPEG file does not exist" }
        require(packet.isNotBlank()) { "XMP packet is blank" }
        val packetSize = packet.toByteArray(Charsets.UTF_8).size
        require(packetSize <= MAX_STANDARD_XMP_PACKET_BYTES) {
            "XMP packet is too large for a standard JPEG APP1 segment: " +
                "$packetSize > $MAX_STANDARD_XMP_PACKET_BYTES bytes"
        }

        val parent = requireNotNull(photoFile.parentFile) { "JPEG parent directory is missing" }
        val input = File.createTempFile("xmp_input_", ".jpg", parent)
        val output = File.createTempFile("xmp_write_", ".jpg", parent)
        try {
            photoFile.copyTo(input, overwrite = true)
            // 只在副本上清除历史 EXIF Tag 700，失败时不会改动原图。
            ExifInterface(input).apply {
                setAttribute(ExifInterface.TAG_XMP, null)
                saveAttributes()
            }
            output.outputStream().buffered().use { stream ->
                JpegXmpRewriter().updateXmpXml(ByteSource.file(input), stream, packet)
            }
            check(read(output) == packet) { "XMP round-trip verification failed before replacing JPEG" }
            replaceWithVerifiedFile(photoFile, output, packet)
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun replaceWithVerifiedFile(target: File, replacement: File, expectedPacket: String) {
        val backup = File.createTempFile("xmp_backup_", ".jpg", target.parentFile)
        if (!backup.delete()) throw java.io.IOException("Unable to prepare XMP backup path")
        if (!target.renameTo(backup)) throw java.io.IOException("Unable to back up JPEG before XMP replacement")
        try {
            if (!replacement.renameTo(target)) throw java.io.IOException("Unable to replace JPEG after XMP write")
            check(read(target) == expectedPacket) { "XMP changed after JPEG replacement" }
            backup.delete()
        } catch (error: Throwable) {
            val invalidTargetRemoved = !target.exists() || target.delete()
            if (!invalidTargetRemoved || !backup.renameTo(target)) {
                throw java.io.IOException(
                    "Unable to restore JPEG after XMP replacement; backup=${backup.absolutePath}",
                    error
                )
            }
            throw error
        }
    }

    // JPEG 段数据最多 65533 字节，标准 XMP 标识占 29 字节。
    internal const val MAX_STANDARD_XMP_PACKET_BYTES = 65_504
}
