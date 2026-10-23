package com.wkq.iptc.feature.press.metadata

import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadMetadataCopierTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preservesFinalPhotoMetadataAndNormalizesCompressedGeometry() {
        val pixels = folder.newFile("pixels.jpg")
        // 实际编码的 8×16 JPEG，不依赖 Android 位图解码或桌面 AWT。
        pixels.writeBytes(java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q=="
        ))
        val exifFile = folder.newFile("exif.jpg")
        val fields = TiffOutputSet().apply {
            orCreateRootDirectory.add(TiffTagConstants.TIFF_TAG_ORIENTATION, 6.toShort())
            orCreateExifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, "2020:01:02 03:04:05")
            setGpsInDegrees(120.0, 30.0)
        }
        exifFile.outputStream().use { ExifRewriter().updateExifMetadataLossless(pixels, it, fields) }
        val iptcFile = folder.newFile("iptc.jpg")
        val records = listOf(
            IptcRecord(IptcTypes.HEADLINE, "Per-photo headline"),
            IptcRecord(IptcTypes.CAPTION_ABSTRACT, "Final edited caption"),
            IptcRecord(IptcTypes.KEYWORDS, "one"), IptcRecord(IptcTypes.KEYWORDS, "two")
        )
        iptcFile.outputStream().use {
            JpegIptcRewriter().writeIptc(ByteSource.file(exifFile), it, PhotoshopApp13Data(records, emptyList(), true))
        }
        val source = folder.newFile("source.jpg")
        val packet = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><custom>final value</custom></x:xmpmeta>"
        source.outputStream().use { JpegXmpRewriter().updateXmpXml(ByteSource.file(iptcFile), it, packet) }
        val original = source.readBytes()
        val target = folder.newFile("compressed.jpg")
        pixels.copyTo(target, overwrite = true)
        UploadMetadataCopier.copy(source, target, 8, 16)
        val parser = JpegImageParser()
        val params = JpegImagingParameters()
        val result = parser.getMetadata(ByteSource.file(target), params) as JpegImageMetadata
        assertEquals(records.map { it.iptcType.type to it.value }.sortedBy { it.first },
            result.photoshop.photoshopApp13Data.records.map { it.iptcType.type to it.value }.sortedBy { it.first })
        assertEquals(packet, parser.getXmpXml(ByteSource.file(target), params))
        assertEquals("2020:01:02 03:04:05", result.findExifValueWithExactMatch(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL).stringValue)
        assertEquals(1, result.findExifValueWithExactMatch(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
        assertEquals(8, result.findExifValueWithExactMatch(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH).intValue)
        assertEquals(16, result.findExifValueWithExactMatch(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH).intValue)
        assertTrue(original.contentEquals(source.readBytes()))
        // 后处理一旦丢失元数据，必须阻止上传，而不是仅依赖数据库里的已校验标记。
        pixels.copyTo(target, overwrite = true)
        assertThrows(IllegalStateException::class.java) {
            UploadMetadataCopier.verifyContent(source, target)
        }
    }

    @Test fun missingExifIsOptionalAndDoesNotInventCaptureTime() {
        val source = folder.newFile("without-exif.jpg")
        source.writeBytes(java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q=="))
        val target = folder.newFile("compressed-without-exif.jpg")
        source.copyTo(target, overwrite = true)
        UploadMetadataCopier.copy(source, target, 8, 16)
        UploadMetadataCopier.verifyContent(source, target)
        val metadata = JpegImageParser().getMetadata(ByteSource.file(target), JpegImagingParameters()) as JpegImageMetadata
        assertEquals(1, metadata.findExifValueWithExactMatch(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
        org.junit.Assert.assertNull(metadata.findExifValueWithExactMatch(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL))
    }
}
