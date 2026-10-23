package com.wkq.iptc.feature.press.metadata

import androidx.exifinterface.media.ExifInterface
import com.wkq.util.exif.PhotoFormatPolicy
import com.wkq.util.exif.RasterMetadataChunks
import java.io.File

/** XMP按真实格式分派；非JPEG使用其原生块，不套用APP1限制。 */
object MetadataXmpStore {
    fun read(file: File): String = if (PhotoFormatPolicy.detect(file) == PhotoFormatPolicy.Format.JPEG) {
        JpegXmpStore.read(file)
    } else {
        runCatching { (if (PhotoFormatPolicy.detect(file) in setOf(
                PhotoFormatPolicy.Format.PNG, PhotoFormatPolicy.Format.WEBP)) {
                RasterMetadataChunks.readNativeXmp(file)
            } else null) ?:
            ExifInterface(file).getAttributeBytes(ExifInterface.TAG_XMP)
                ?.toString(Charsets.UTF_8).orEmpty() }.getOrDefault("")
    }

    fun write(file: File, packet: String): Result<Unit> = runCatching {
        if (PhotoFormatPolicy.detect(file) == PhotoFormatPolicy.Format.JPEG) {
            JpegXmpStore.write(file, packet).getOrThrow()
        } else {
            require(packet.isNotBlank() && packet.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
            val before = RasterMetadataChunks.protectedDigest(file)
            val work = File.createTempFile("native_xmp_", ".tmp", file.parentFile)
            try {
                file.copyTo(work, overwrite = true)
                // 先删除独立块，再清除Exif Tag700，避免双位置历史XMP残留。
                RasterMetadataChunks.removeXmp(work)
                ExifInterface(work).apply { setAttribute(ExifInterface.TAG_XMP, null); saveAttributes() }
                // 明确写入PNG iTXt/WebP XMP块，避免首次写入时落入Exif Tag700。
                RasterMetadataChunks.writeNativeXmp(work, packet)
                check(read(work) == packet && RasterMetadataChunks.countTextOrXmp(work, false) == 1) {
                    "XMP round-trip verification failed"
                }
                if (RasterMetadataChunks.protectedDigest(work) != before) throw MetadataIntegrityException("Image payload changed")
                RasterMetadataChunks.replaceVerified(file, work)
            } finally { work.delete() }
        }
    }
}
