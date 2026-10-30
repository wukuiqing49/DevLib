package com.wkq.util.exif

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.iptc.feature.press.metadata.*
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
import java.security.MessageDigest

/** 在独立测试包中执行真实 Android 编解码与元数据读写，不访问用户数据库。 */
@RunWith(Parameterized::class)
class ImageFormatDeviceTest(private val name: String, private val supported: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun formats(): List<Array<Any>> = listOf(
            arrayOf("jpeg.jpg", true), arrayOf("png.png", true),
            arrayOf("png_alpha.png", true), arrayOf("webp_lossy.webp", true),
            arrayOf("webp_lossless.webp", true), arrayOf("gif.gif", true),
            arrayOf("gif_animated.gif", true), arrayOf("bmp.bmp", true),
            arrayOf("avif.avif", true), arrayOf("heic.heic", true),
            arrayOf("heif.heif", true), arrayOf("tiff.tif", false),
            arrayOf("jpeg2000.jp2", false), arrayOf("ico.ico", true),
            arrayOf("jpeg_named_png.png", true), arrayOf("png_named_jpeg.jpg", true),
            arrayOf("broken.jpg", false), arrayOf("empty.png", false),
            arrayOf("vector.svg", false),
            arrayOf("dng_with_exif_with_xmp.dng", true),
            arrayOf("heic_with_exif.heic", true),
            arrayOf("jpeg_with_xmp_in_exif_first_then_separate_app1.jpg", true),
            arrayOf("png_with_xmp_in_both_exif_and_itxt.png", true),
            arrayOf("webp_with_anim_without_exif.webp", true)
        )
    }

    private val template = MetadataTemplate(
        id = "format-device-test", iptcAuthor = "格式测试作者",
        headline = "格式测试 العربية 日本語", caption = "CaptionMeta format 😀",
        copyright = "Synthetic test 2026", keywords = listOf("format", "测试"),
        enabledFields = listOf("iptcAuthor", "headline", "caption", "copyright", "keywords")
    )

    private fun withFixture(block: (File, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "format-matrix-$name-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val source = File(dir, name)
            instrumentation.context.assets.open("format-matrix/$name").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val originalHash = hash(source)
            if (name in listOf("broken.jpg", "empty.png")) {
                assertEquals("损坏文件不能统计读取器默认字段", 0, MetadataScrubber.countMetadataFields(source))
            }
            block(source, dir)
            assertEquals("输入副本被修改", originalHash, hash(source))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    private fun normalized(source: File, dir: File): Result<File> = runCatching {
        val output = File(dir, "output.jpg")
        if (CleanJpegExporter.isJpeg(source)) source.copyTo(output)
        else CleanJpegExporter.exportCleanJpeg(source, output).getOrThrow()
        val bitmap = BitmapFactory.decodeFile(output.path) ?: error("输出无法解码")
        bitmap.recycle()
        output
    }

    private fun stamp(file: File) {
        IptcWriter.writeTemplateMetadata(file, template, replaceManagedFields = true).getOrThrow()
        ExifWriter.writeAllMetadata(file, null, XmpWriter.buildTemplatePacket(
            photoFile = file, template = template, replaceManagedProperties = true
        )).getOrThrow()
    }

    @Test
    fun encapsulateAndReadBack() = withFixture { source, dir ->
        val result = normalized(source, dir)
        Log.i("FormatMatrix", "ENCAPSULATE $name normalized=${result.isSuccess}")
        if (!supported) {
            assertTrue("不支持或损坏的格式应明确失败", result.isFailure)
        } else {
            val output = result.getOrThrow()
            stamp(output)
            val verification = MetadataVerifier.verify(output, template, expectGps = false)
            assertTrue(verification.error, verification.isFullyVerified)
            val bitmap = BitmapFactory.decodeFile(output.path)
            assertNotNull(bitmap)
            if (name == "png_alpha.png") assertTrue(Color.red(bitmap!!.getPixel(250, 100)) > 240)
            bitmap?.recycle()
        }
    }

    @Test
    fun clearSourceAndEncapsulatedMetadata() = withFixture { source, dir ->
        val result = normalized(source, dir)
        Log.i("FormatMatrix", "CLEAR $name normalized=${result.isSuccess}")
        if (!supported) {
            assertTrue(result.isFailure)
        } else {
            val output = result.getOrThrow()
            // 先清理原始样本中的字段，再验证已封装照片的完整清理。
            runBlocking { MetadataScrubber.scrubMetadata(output).getOrThrow() }
            assertSensitiveFieldsAbsent(output)
            stamp(output)
            ExifInterface(output).apply {
                setLatLong(31.2, 121.5)
                setAttribute(ExifInterface.TAG_MAKE, "FORMAT_TEST_CAMERA")
                setAttribute(ExifInterface.TAG_ARTIST, "FORMAT_TEST_AUTHOR")
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            assertTrue(MetadataScrubber.countSelectedMetadataFields(output, true, true, true) > 0)
            runBlocking { MetadataScrubber.scrubMetadata(output).getOrThrow() }
            assertSensitiveFieldsAbsent(output)
            assertEquals(6, ExifInterface(output).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            val bitmap = BitmapFactory.decodeFile(output.path)
            assertNotNull("清理后图片损坏", bitmap)
            bitmap?.recycle()
        }
    }

    private fun assertSensitiveFieldsAbsent(file: File) {
        val exif = ExifInterface(file)
        assertNull("GPS 残留", exif.latLong)
        listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_XMP).forEach {
            assertNull("敏感字段残留: $it", exif.getAttribute(it))
        }
        assertTrue("XMP APP1 残留", JpegXmpStore.read(file).isBlank())
        val records = JpegImageParser().getPhotoshopMetadata(ByteSource.file(file), JpegImagingParameters())
            ?.photoshopApp13Data?.records.orEmpty()
        assertTrue("IPTC APP13 残留", records.isEmpty())
    }
}
