package com.wkq.util.exif

import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.File
import kotlinx.coroutines.ensureActive

object MetadataScrubber {

    /**
     * 统计照片中包含的元数据字段数量 (EXIF + IPTC + XMP)。
     * 使用真实文件标签统计，不把读取器自动补齐的默认字段算进概览。
     */
    fun countMetadataFields(photoFile: File): Int {
        // 0 是有效统计结果；不再用读取器补出的 Orientation/LightSource 默认值兜底。
        // 解析失败返回 0 仅用于概览，不能作为清理成功的判据。
        return ImageMetadataInspector.inspect(photoFile).getOrNull()?.totalCount ?: 0
    }

    fun countSelectedMetadataFields(
        photoFile: File,
        scrubIptc: Boolean,
        scrubExif: Boolean,
        scrubPersonal: Boolean
    ): Int {
        if (!scrubIptc && !scrubExif && !scrubPersonal) return 0
        val exif = ExifInterface(photoFile)
        val tags = buildList {
            if (scrubExif) { addAll(GPS_TAGS); addAll(EXIF_TECH_TAGS) }
            if (scrubPersonal) addAll(PERSONAL_TAGS)
        }.distinct()
        var count = tags.count { tag -> exif.getAttribute(tag) != null &&
            (tag != ExifInterface.TAG_LIGHT_SOURCE || hasStoredLightSource(photoFile)) }
        if (scrubExif) {
            // 独立解析器检查整个物理 GPS 目录，避免清理列表漏项后仍报告零残留。
            count += ImageMetadataReader.readMetadata(photoFile)
                .getDirectoriesOfType(com.drew.metadata.exif.GpsDirectory::class.java).sumOf { it.tagCount }
        }
        count += readXmpPackets(photoFile).sumOf {
            MetadataPrivacyRules.countXmp(it, scrubIptc, scrubExif, scrubPersonal)
        }
        val format = PhotoFormatPolicy.detect(photoFile)
        if (format in setOf(PhotoFormatPolicy.Format.PNG, PhotoFormatPolicy.Format.WEBP)) {
            count += RasterMetadataChunks.countPrivacyText(photoFile, scrubIptc, scrubExif, scrubPersonal)
        }
        if (format == PhotoFormatPolicy.Format.JPEG) {
            val (extendedXmp, comments) = JpegPrivacySegments.counts(photoFile)
            // 无法选择性解析的扩展包不能漏报为零残留。
            count += extendedXmp
            if (scrubPersonal) count += comments
            val metadata = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
                .getPhotoshopMetadata(ByteSource.file(photoFile), JpegImagingParameters())
            count += metadata?.photoshopApp13Data?.records.orEmpty().count {
                MetadataPrivacyRules.selectedIptc(it.iptcType.type, scrubIptc, scrubExif, scrubPersonal)
            }
        }
        return count
    }

    /**
     * 兼容原先无参数的擦除调用：默认擦除所有 IPTC、EXIF、个人信息，并保留旋转方向。
     */
    suspend fun scrubMetadata(photoFile: File): Result<Unit> {
        return scrubMetadata(
            photoFile = photoFile,
            scrubIptc = true,
            scrubExif = true,
            scrubPersonal = true,
            keepOrientation = true
        )
    }

    /**
     * 支持选择性擦除敏感元数据，且支持选择是否保留旋转方向 (Orientation)。
     *
     * - scrubIptc    : 清除 APP13 IPTC 块 + XMP（包含 IPTC Core/Extension、Dublin Core、Photoshop）
     * - scrubExif    : 清除 GPS 全字段 + 相机曝光/光学/场景等技术参数
     * - scrubPersonal: 清除拍摄时间、设备型号/序列号、创作者/版权、唯一图像 ID
     */
    suspend fun scrubMetadata(
        photoFile: File,
        scrubIptc: Boolean,
        scrubExif: Boolean,
        scrubPersonal: Boolean,
        keepOrientation: Boolean = true
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (!scrubIptc && !scrubExif && !scrubPersonal) return@runCatching
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val jpegBefore = if (CleanJpegExporter.isJpeg(photoFile)) JpegImageIntegrity.snapshot(photoFile) else null
            val raster = PhotoFormatPolicy.detect(photoFile) in
                setOf(PhotoFormatPolicy.Format.PNG, PhotoFormatPolicy.Format.WEBP)
            val protectedBefore = if (raster) RasterMetadataChunks.protectedDigest(photoFile) else null
            if (jpegBefore != null && !scrubIptc) {
                check(JpegPrivacySegments.counts(photoFile).first == 0) {
                    "Extended XMP requires full XMP cleanup"
                }
            }
            // 全部 XMP 被选中时不解析坏包；选择性清理必须能读懂旧内容，否则不能声称保留成功。
            val retainedXmp = if (scrubIptc) "" else MetadataPrivacyRules.scrubXmp(
                readXmpPackets(photoFile), false, scrubExif, scrubPersonal)
            if (raster) RasterMetadataChunks.removePrivacyMetadata(photoFile, scrubIptc, scrubExif, scrubPersonal)
            if (jpegBefore != null) {
                val tempFile = File.createTempFile("scrub_xmp_", ".jpg", photoFile.parentFile)
                try {
                    tempFile.outputStream().buffered().use { output ->
                        JpegPrivacySegments.remove(photoFile, output, scrubPersonal)
                    }
                    RasterMetadataChunks.replaceVerified(photoFile, tempFile)
                } finally { tempFile.delete() }
            }
            // 1. 暂存原图的旋转方向
            val sourceExif = ExifInterface(photoFile)
            val orientation = sourceExif.getAttribute(ExifInterface.TAG_ORIENTATION)
                ?: ExifInterface.ORIENTATION_NORMAL.toString()

            // 2. 用 ExifInterface 擦除 EXIF 和 XMP 字段并保存
            val targetExif = ExifInterface(photoFile)
            val sensitiveTags = mutableListOf(ExifInterface.TAG_XMP)

            if (scrubExif) {
                sensitiveTags.addAll(GPS_TAGS)
                sensitiveTags.addAll(EXIF_TECH_TAGS)
            }

            if (scrubPersonal) {
                sensitiveTags.addAll(PERSONAL_TAGS)
            }

            sensitiveTags.distinct().forEach { tag ->
                targetExif.setAttribute(tag, null)
            }

            if (keepOrientation) {
                targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            }
            targetExif.saveAttributes()

            // 3. 最后一步：物理抹除 IPTC (APP13 区) 记录
            // 必须在 ExifInterface 保存后进行，避免其 saveAttributes 时将内存中的旧 APP13 覆写回去
            if (CleanJpegExporter.isJpeg(photoFile)) {
                // 流式读取，不再 readBytes() 全量加载到内存
                val byteSource = ByteSource.file(photoFile)
                val metadata = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
                    .getPhotoshopMetadata(byteSource, JpegImagingParameters())
                val nonIptcBlocks = metadata?.photoshopApp13Data?.nonIptcBlocks.orEmpty()
                val retainedRecords = metadata?.photoshopApp13Data?.records.orEmpty().filterNot {
                    MetadataPrivacyRules.selectedIptc(it.iptcType.type, scrubIptc, scrubExif, scrubPersonal)
                }
                val emptyApp13Data = PhotoshopApp13Data(retainedRecords, nonIptcBlocks)

                val tempFile = File(photoFile.parent, "${photoFile.name}.scrub_tmp")
                try {
                    tempFile.outputStream().buffered().use { out ->
                        JpegIptcRewriter().writeIptc(byteSource, out, emptyApp13Data)
                    }
                    RasterMetadataChunks.replaceVerified(photoFile, tempFile)
                } finally { tempFile.delete() }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (retainedXmp.isNotBlank()) {
                // 此处两种旧 XMP 已清除；不要再次调用 ExifInterface 保存，避免补回 LightSource 默认值。
                if (raster) RasterMetadataChunks.writeNativeXmp(photoFile, retainedXmp)
                else {
                    require(retainedXmp.toByteArray(Charsets.UTF_8).size <=
                        com.wkq.iptc.feature.press.metadata.JpegXmpStore.MAX_STANDARD_XMP_PACKET_BYTES) {
                        "Retained XMP exceeds standard APP1 capacity"
                    }
                    val output = File.createTempFile("scrub_xmp_", ".jpg", photoFile.parentFile)
                    try {
                        output.outputStream().use {
                            JpegXmpRewriter().updateXmpXml(ByteSource.file(photoFile), it, retainedXmp)
                        }
                        RasterMetadataChunks.replaceVerified(photoFile, output)
                    } finally { output.delete() }
                }
                check(retainedXmp in readXmpPackets(photoFile)) { "Retained XMP readback failed" }
            }
            check(countSelectedMetadataFields(photoFile, scrubIptc, scrubExif, scrubPersonal) == 0) {
                "Selected privacy metadata remains after scrub"
            }
            jpegBefore?.let { JpegImageIntegrity.verify(photoFile, it, keepOrientation) }
            if (raster && RasterMetadataChunks.protectedDigest(photoFile) != protectedBefore) {
                throw com.wkq.iptc.feature.press.metadata.MetadataIntegrityException("Image payload changed during scrub")
            }
        }
    }

    /** 分别读取 APP1 与 TIFF Tag700，不能只检查优先返回的那一份 XMP。 */
    private fun readXmpPackets(file: File): List<String> {
        if (CleanJpegExporter.isJpeg(file)) {
            val parser = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
            val source = ByteSource.file(file)
            val parameters = JpegImagingParameters()
            val metadata = parser.getMetadata(source, parameters) as? org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
            val tag700 = metadata?.exif?.findField(
                org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants.TIFF_TAG_XMP)
                ?.byteArrayValue?.toString(Charsets.UTF_8)
            return listOfNotNull(parser.getXmpXml(source, parameters), tag700).filter(String::isNotBlank).distinct()
        }
        check(RasterMetadataChunks.countTextOrXmp(file, false) <= 1) { "Multiple XMP chunks cannot be preserved safely" }
        return listOfNotNull(RasterMetadataChunks.readNativeXmp(file), RasterMetadataChunks.readExifXmp(file))
            .filter(String::isNotBlank).distinct()
    }

    private fun hasStoredLightSource(photoFile: File): Boolean = runCatching {
        // ExifInterface 会为缺失的 LightSource 补 0；读取物理标签避免误报残留。
        ImageMetadataReader.readMetadata(photoFile)
            .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ?.containsTag(ExifSubIFDDirectory.TAG_LIGHT_SOURCE) == true
    }.getOrDefault(true) // 解析失败时保守地保留计数，不能把未知当作清理成功。

    // -------------------------------------------------------------------------
    // 字段分组常量（与 UI 勾选项一一对应）
    // -------------------------------------------------------------------------

    /** GPS 定位全字段：经纬度、高度、速度、方向、目标点等 */
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP
    )

    /** 相机技术参数：曝光、光学、场景等（不含 GPS） */
    private val EXIF_TECH_TAGS = listOf(
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION
    )

    /** 个人身份信息：创作者、设备序列号、拍摄时间、唯一图像 ID 等 */
    private val PERSONAL_TAGS = listOf(
        // 修改/拍摄时间（含时区与亚秒级时间戳）
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // 设备制造商、型号、软件与序列号
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        // 创作者与版权
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        // 注释
        ExifInterface.TAG_USER_COMMENT,
        // 可追踪的唯一图像 ID（相机内生成，可用于追踪拍摄者）
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_MAKER_NOTE
    )
}
