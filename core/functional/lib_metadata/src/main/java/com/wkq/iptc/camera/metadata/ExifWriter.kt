package com.wkq.iptc.camera.metadata

import android.location.Location
import androidx.exifinterface.media.ExifInterface
import com.wkq.iptc.feature.press.metadata.MetadataXmpStore
import java.io.File

/** 基础 EXIF 写入门面；组合写入在副本完成后才替换源文件，调用方串行管理同一文件。 */
object ExifWriter {
    /** 事务式写入基础 EXIF，失败时保留原文件。 */
    fun writeBasicMetadata(file: File, location: Location?): Result<Unit> {
        return writeAllMetadata(file, location)
    }

    /** 在同目录副本上完成 EXIF 和可选 XMP；任一步失败都不发布半成品。 */
    fun writeAllMetadata(file: File, location: Location?, xmpPacket: String? = null): Result<Unit> {
        return runCatching {
            require(file.isFile) { "Metadata source must exist" }
            val target = file.canonicalFile
            val work = File.createTempFile("exif_write_", ".${target.extension.ifBlank { "tmp" }}", target.parentFile)
            try {
                file.copyTo(work, overwrite = true)
                writeExifMetadata(work, location).getOrThrow()
                if (!xmpPacket.isNullOrBlank()) writeXmpMetadata(work, xmpPacket).getOrThrow()
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Metadata write interrupted")
                com.wkq.util.exif.RasterMetadataChunks.replaceVerified(target, work)
            } finally { work.delete() }
        }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
    }

    /** 通过既有格式分派写入 XMP，并保留原结果契约。 */
    fun writeXmpMetadata(file: File, xmpPacket: String): Result<Unit> {
        return MetadataXmpStore.write(file, xmpPacket)
    }

    private fun writeExifMetadata(file: File, location: Location?): Result<Unit> {
        return runCatching {
            val exif = ExifInterface(file)
            // 缺失拍摄时间保持未知；不能把本次处理时间当作原始拍摄时间。
            val captured = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (!captured.isNullOrBlank() && exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED).isNullOrBlank()) {
                exif.setAttribute(
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    captured
                )
            }
            if (location != null) {
                exif.setGpsInfo(location)
            }
            exif.saveAttributes()
        }
    }
}
