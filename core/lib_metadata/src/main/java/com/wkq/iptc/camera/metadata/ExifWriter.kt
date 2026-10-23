package com.wkq.iptc.camera.metadata

import android.location.Location
import androidx.exifinterface.media.ExifInterface
import com.wkq.iptc.feature.press.metadata.MetadataXmpStore
import java.io.File

object ExifWriter {
    fun writeBasicMetadata(file: File, location: Location?): Result<Unit> {
        return writeExifMetadata(file, location)
    }

    fun writeAllMetadata(file: File, location: Location?, xmpPacket: String? = null): Result<Unit> {
        return runCatching {
            writeExifMetadata(file, location).getOrThrow()
            if (!xmpPacket.isNullOrBlank()) {
                writeXmpMetadata(file, xmpPacket).getOrThrow()
            }
        }
    }

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
