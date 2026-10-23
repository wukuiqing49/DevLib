package com.wkq.util.exif

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.iptc.feature.press.metadata.*
import com.wkq.dev.metadata.model.MetadataTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest

/** 独立测试包，不接触用户数据库；输入与输出均保持PNG/WebP。 */
@RunWith(Parameterized::class)
class NativeRasterDeviceTest(private val name: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun files() = listOf("png.png", "png_alpha.png", "png_with_xmp_in_both_exif_and_itxt.png",
            "webp_lossy.webp", "webp_lossless.webp", "webp_with_anim_without_exif.webp")
            .map { arrayOf(it) }
    }

    private val template = MetadataTemplate(id = "native-raster", iptcAuthor = "记者测试",
        headline = "原格式 العربية", caption = "Native format 中文", copyright = "Test 2026",
        keywords = listOf("测试", "native"),
        enabledFields = listOf("iptcAuthor", "headline", "caption", "copyright", "keywords"))

    private fun fixture(block: (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "native-raster-${System.nanoTime()}")
        check(dir.mkdirs())
        try {
            val file = File(dir, name)
            instrumentation.context.assets.open("format-matrix/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            block(file)
        } finally { dir.deleteRecursively() }
    }

    private fun stamp(file: File) {
        IptcWriter.writeTemplateMetadata(file, template, true).getOrThrow()
        ExifWriter.writeBasicMetadata(file, null).getOrThrow()
        ExifWriter.writeXmpMetadata(file, XmpWriter.buildTemplatePacket(file, template, true)).getOrThrow()
        assertTrue(MetadataVerifier.verify(file, template, false).isFullyVerified)
    }

    @Test fun encapsulateWithoutChangingImageData() = fixture { file ->
        val before = RasterMetadataChunks.protectedDigest(file)
        val format = PhotoFormatPolicy.detect(file)
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        stamp(file)
        stamp(file) // 重复修改也不残留多份独立XMP。
        assertEquals(format, PhotoFormatPolicy.detect(file))
        assertEquals(before, RasterMetadataChunks.protectedDigest(file))
        assertEquals(1, RasterMetadataChunks.countTextOrXmp(file, false))
        val decoded = BitmapFactory.decodeFile(file.path)!!
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        val afterPixels = IntArray(width * height)
        decoded.getPixels(afterPixels, 0, width, 0, 0, width, height)
        assertArrayEquals(pixels, afterPixels)
        decoded.recycle()
        val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(file)
        assertTrue(metadata.getFirstDirectoryOfType(com.drew.metadata.xmp.XmpDirectory::class.java)
            ?.xmpMeta?.getPropertyString("http://purl.org/dc/elements/1.1/", "creator[1]") == "记者测试")
    }

    @Test fun clearOriginalAndWrittenMetadataWithoutChangingImageData() = fixture { file -> runBlocking {
        val before = RasterMetadataChunks.protectedDigest(file)
        val format = PhotoFormatPolicy.detect(file)
        // 先清原始双位置XMP，再封装清理，不能只测自己生成的数据。
        MetadataScrubber.scrubMetadata(file).getOrThrow()
        assertEquals(0, RasterMetadataChunks.countTextOrXmp(file, true))
        assertTrue(MetadataXmpStore.read(file).isBlank())
        stamp(file)
        ExifInterface(file).apply {
            setLatLong(31.2, 121.5)
            setAttribute(ExifInterface.TAG_MAKE, "Privacy-device")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:10:16 10:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME, "+08:00")
            saveAttributes()
        }
        val physicalBefore = com.drew.imaging.ImageMetadataReader.readMetadata(file)
        assertTrue(physicalBefore.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory::class.java)
            ?.containsTag(0x0132) == true)
        MetadataScrubber.scrubMetadata(file).getOrThrow()
        assertEquals(format, PhotoFormatPolicy.detect(file))
        assertEquals(before, RasterMetadataChunks.protectedDigest(file))
        assertEquals(0, MetadataScrubber.countSelectedMetadataFields(file, true, true, true))
        assertEquals(0, RasterMetadataChunks.countTextOrXmp(file, true))
        assertTrue(MetadataXmpStore.read(file).isBlank())
        assertNull(ExifInterface(file).latLong)
        assertNull(ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE))
        val physicalAfter = com.drew.imaging.ImageMetadataReader.readMetadata(file)
        assertFalse(physicalAfter.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory::class.java)
            ?.containsTag(0x0132) == true)
        assertFalse(physicalAfter.getFirstDirectoryOfType(com.drew.metadata.exif.ExifSubIFDDirectory::class.java)
            ?.containsTag(0x9010) == true)
    } }

    @Test fun selectiveExifClearKeepsXmp() = fixture { file -> runBlocking {
        stamp(file)
        val xmp = MetadataXmpStore.read(file)
        val before = RasterMetadataChunks.protectedDigest(file)
        MetadataScrubber.scrubMetadata(file, false, true, false).getOrThrow()
        assertEquals(xmp, MetadataXmpStore.read(file))
        assertEquals(before, RasterMetadataChunks.protectedDigest(file))
    } }
}
