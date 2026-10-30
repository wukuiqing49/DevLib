package com.wkq.iptc.feature.press.metadata

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wkq.iptc.camera.metadata.ExifWriter
import com.wkq.dev.metadata.model.MetadataTemplate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UnicodeXmpRoundTripTest {

    @Test
    fun multilingualMetadataSurvivesJpegApp1RoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photoFile = File(context.cacheDir, "unicode_xmp_round_trip.jpg")
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            photoFile.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
            bitmap.recycle()

            val template = MetadataTemplate(
                id = "unicode_xmp_round_trip",
                iptcAuthor = "吴奎庆",
                headline = "中文标题 / العربية / 日本語",
                caption = "हिन्दी, Tiếng Việt, emoji 😀",
                copyright = "Copyright 2026 吴奎庆",
                rightsUsageTerms = "Editorial use only",
                enabledFields = listOf(
                    "iptcAuthor",
                    "headline",
                    "caption",
                    "copyright",
                    "rightsUsageTerms"
                )
            )
            IptcWriter.writeTemplateMetadata(
                photoFile = photoFile,
                template = template,
                replaceManagedFields = true
            ).getOrThrow()
            ExifWriter.writeAllMetadata(
                file = photoFile,
                location = null,
                xmpPacket = XmpWriter.buildTemplatePacket(
                    photoFile = photoFile,
                    template = template,
                    replaceManagedProperties = true
                )
            ).getOrThrow()

            val storedXmp = JpegXmpStore.read(photoFile)
            assertTrue(storedXmp.isNotBlank())
            assertTrue(storedXmp.contains("吴奎庆"))
            assertTrue(storedXmp.contains("العربية"))
            assertTrue(storedXmp.contains("日本語"))
            assertTrue(storedXmp.contains("हिन्दी"))
            assertTrue(storedXmp.contains("Tiếng Việt"))
            assertTrue(storedXmp.contains("😀"))

            // 详情页使用该读取器解析标准字段，确保展示链路与 APP1 UTF-8 写入链路一致。
            val displayedFields = XmpMetadataReader.readIptcXmpFields(photoFile).getOrThrow()
            assertTrue(displayedFields.creator.contains("吴奎庆"))
            assertTrue(displayedFields.headline.contains("العربية"))
            assertTrue(displayedFields.headline.contains("日本語"))
            assertTrue(displayedFields.caption.contains("हिन्दी"))
            assertTrue(displayedFields.caption.contains("Tiếng Việt"))

            val verification = MetadataVerifier.verify(photoFile, template, expectGps = false)
            assertTrue(verification.error.orEmpty(), verification.isFullyVerified)
        } finally {
            photoFile.delete()
        }
    }

    @Test
    fun oversizedPacketIsRejectedWithoutCorruptingJpeg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photoFile = File(context.cacheDir, "oversized_xmp.jpg")
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            photoFile.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
            bitmap.recycle()

            val oversized = "x".repeat(JpegXmpStore.MAX_STANDARD_XMP_PACKET_BYTES + 1)
            assertTrue(JpegXmpStore.write(photoFile, oversized).isFailure)
            assertTrue(photoFile.isFile && photoFile.length() > 0)
        } finally {
            photoFile.delete()
        }
    }
}
