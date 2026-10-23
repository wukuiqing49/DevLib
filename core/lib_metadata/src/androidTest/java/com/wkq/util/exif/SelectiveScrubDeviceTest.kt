package com.wkq.util.exif

import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.iptc.feature.press.metadata.IptcWriter
import com.wkq.iptc.feature.press.metadata.JpegXmpStore
import com.wkq.iptc.feature.press.metadata.XmpWriter
import com.wkq.dev.metadata.model.MetadataTemplate
import kotlinx.coroutines.runBlocking
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifIFD0Directory

@RunWith(Parameterized::class)
class SelectiveScrubDeviceTest(private val mask: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "mask={0}")
        fun options() = (1..7).map { arrayOf(it) }
    }

    @Test fun selectedGroupsRemovedOtherGroupsRetained() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "selective-scrub-$mask.jpg")
        try {
            instrumentation.context.assets.open("format-matrix/jpeg.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val template = MetadataTemplate(id = "selective-test", headline = "Selective test",
                enabledFields = listOf("headline"))
            IptcWriter.writeTemplateMetadata(file, template).getOrThrow()
            ExifWriter.writeAllMetadata(file, null, XmpWriter.buildTemplatePacket(template)).getOrThrow()
            ExifInterface(file).apply {
                setLatLong(31.2, 121.5)
                setAttribute(ExifInterface.TAG_MAKE, "TEST_CAMERA")
                setAttribute(ExifInterface.TAG_DATETIME, "2026:10:16 10:00:00")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:16 10:00:00")
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "2026:10:16 10:00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME, "+08:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "+08:00")
                // 合法的 0 值必须计数；不能简单忽略所有 LightSource=0。
                setAttribute(ExifInterface.TAG_LIGHT_SOURCE, "0")
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            val iptc = mask and 1 != 0
            val exif = mask and 2 != 0
            val personal = mask and 4 != 0
            MetadataScrubber.scrubMetadata(file, iptc, exif, personal).getOrThrow()
            val after = ExifInterface(file)
            assertEquals(!exif, after.latLong != null)
            assertEquals(!personal, after.getAttribute(ExifInterface.TAG_MAKE) != null)
            // 使用独立解析器的物理数字标签检查，避免清理和内部计数共用漏项。
            val physical = ImageMetadataReader.readMetadata(file)
            val ifd0 = physical.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val subIfd = physical.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            assertEquals("DateTime", !personal, ifd0?.containsTag(0x0132) == true)
            for (tag in listOf(0x9003, 0x9004, 0x9010, 0x9011, 0x9012)) {
                assertEquals("时间标签 $tag", !personal, subIfd?.containsTag(tag) == true)
            }
            assertEquals(6, after.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            val records = JpegImageParser().getPhotoshopMetadata(ByteSource.file(file), JpegImagingParameters())
                ?.photoshopApp13Data?.records.orEmpty()
            assertEquals(!iptc, records.isNotEmpty())
            assertEquals(!(iptc || personal), JpegXmpStore.read(file).isNotBlank())
            val remaining = MetadataScrubber.countSelectedMetadataFields(file, iptc, exif, personal)
            val detail = listOf(ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
                ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_APERTURE_VALUE,
                ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
                ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_SCENE_TYPE,
                ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_LIGHT_SOURCE,
                ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION)
                .mapNotNull { tag -> after.getAttribute(tag)?.let { "$tag=$it" } }
            val physicalLightSource = ImageMetadataReader.readMetadata(file)
                .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                ?.getObject(ExifSubIFDDirectory.TAG_LIGHT_SOURCE)
            assertEquals("未选中的真实 LightSource 应保留", !exif, physicalLightSource != null)
            assertEquals("读取字段 $detail; physicalLightSource=$physicalLightSource", 0, remaining)
        } finally {
            file.delete()
        }
    }
}
