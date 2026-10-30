package com.wkq.iptc.feature.press.metadata

import androidx.exifinterface.media.ExifInterface
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.dev.metadata.model.MetadataTemplate
import com.wkq.util.exif.MetadataScrubber
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegUtils
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class JpegOriginalQualityTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(): File = folder.newFile().also { file ->
        requireNotNull(javaClass.getResourceAsStream("/original-quality.jpg")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun integrityRejectsChangedImageData() {
        val file = fixture()
        val before = com.wkq.util.exif.JpegImageIntegrity.snapshot(file)
        val bytes = file.readBytes()
        val quantization = bytes.indices.first { it + 5 < bytes.size && bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xdb.toByte() }
        bytes[quantization + 5] = (bytes[quantization + 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { com.wkq.util.exif.JpegImageIntegrity.verify(file, before) }.isFailure)
    }

    @Test fun integrityRejectsChangedOrientation() {
        val file = fixture()
        val before = com.wkq.util.exif.JpegImageIntegrity.snapshot(file)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        assertTrue(runCatching { com.wkq.util.exif.JpegImageIntegrity.verify(file, before) }.isFailure)
    }

    @Test fun integrityRejectsChangedIccSegment() {
        val file = fixture()
        val before = com.wkq.util.exif.JpegImageIntegrity.snapshot(file)
        val bytes = file.readBytes()
        val payload = "ICC_PROFILE\u0000\u0001\u0001test-profile".toByteArray(Charsets.ISO_8859_1)
        val length = payload.size + 2
        file.writeBytes(bytes.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe2.toByte(),
            (length shr 8).toByte(), length.toByte()) + payload + bytes.copyOfRange(2, bytes.size))
        assertTrue(runCatching { com.wkq.util.exif.JpegImageIntegrity.verify(file, before) }.isFailure)
    }

    @Test fun metadataWritePreservesHighResolutionCompressedDataAndColorProfile() {
        checkRoundTrip(scrub = false)
    }

    @Test fun privacyScrubAndMetadataWritePreserveCompressedDataAndOrientation() {
        checkRoundTrip(scrub = true)
    }

    private fun checkRoundTrip(scrub: Boolean) {
        val source = folder.newFile("DSC_1234.JPG")
        // 长边超过旧转换器上限；本用例验证写入链路，不代表大图内存测试。
        requireNotNull(javaClass.getResourceAsStream("/original-quality.jpg")).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            saveAttributes()
        }
        val sourceBytes = source.readBytes()
        val originalImageData = imageDigest(source)
        val protectedImage = com.wkq.util.exif.JpegImageIntegrity.snapshot(source)
        val output = folder.newFile("processed.jpg")
        source.copyTo(output, overwrite = true)
        if (scrub) runBlocking {
            MetadataScrubber.scrubMetadata(
                output, scrubIptc = true, scrubExif = true, scrubPersonal = true, keepOrientation = true
            ).getOrThrow()
        }
        val template = MetadataTemplate(
            id = "original_quality",
            headline = "Professional delivery",
            caption = "Original image quality",
            iptcAuthor = "Photographer",
            enabledFields = listOf("headline", "caption", "iptcAuthor")
        )
        IptcWriter.writeTemplateMetadata(output, template, replaceManagedFields = true).getOrThrow()
        ExifWriter.writeBasicMetadata(output, null).getOrThrow()
        ExifWriter.writeXmpMetadata(
            output, XmpWriter.buildTemplatePacket(output, template, replaceManagedProperties = true)
        ).getOrThrow()
        assertTrue(MetadataVerifier.verify(output, template, false).isFullyVerified)
        val info = JpegImageParser().getImageInfo(ByteSource.file(output), JpegImagingParameters())
        assertEquals(5000, info.width)
        assertEquals(16, info.height)
        assertEquals(6, ExifInterface(output).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        assertArrayEquals(originalImageData, imageDigest(output))
        com.wkq.util.exif.JpegImageIntegrity.verify(output, protectedImage)
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    private fun imageDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        JpegUtils().traverseJfif(ByteSource.file(file), object : JpegUtils.Visitor {
            override fun beginSos() = true

            override fun visitSegment(
                marker: Int, markerBytes: ByteArray, segmentLength: Int,
                segmentLengthBytes: ByteArray, segmentData: ByteArray
            ): Boolean {
                // 排除可编辑的 EXIF/XMP、IPTC 和注释；保留编码参数与 ICC 等其余段。
                if (marker !in setOf(0xFFE1, 0xFFED, 0xFFFE)) {
                    digest.update(markerBytes)
                    digest.update(segmentLengthBytes)
                    digest.update(segmentData)
                }
                return true
            }

            override fun visitSos(marker: Int, markerBytes: ByteArray, imageData: ByteArray) {
                digest.update(markerBytes)
                digest.update(imageData)
            }
        })
        return digest.digest()
    }
}
