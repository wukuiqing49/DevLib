package com.wkq.iptc.camera.metadata

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import androidx.exifinterface.media.ExifInterface

/** EXIF 与 XMP 组合写入失败时必须保留源文件。 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class ExifWriterTransactionTest {
    /** 用过大的 XMP 触发后半程失败，已写入副本的 EXIF 不得泄漏到原图。 */
    @Test fun failedXmpKeepsOriginalExifAndBytes() {
        val root = Files.createTempDirectory("exif-transaction").toFile()
        try {
            val image = root.resolve("photo.jpg")
            javaClass.getResourceAsStream("/test.jpg")!!.use { input -> image.outputStream().use { input.copyTo(it) } }
            ExifInterface(image).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
                saveAttributes()
            }
            val original = image.readBytes()
            assertTrue(ExifWriter.writeAllMetadata(image, null, "x".repeat(5 * 1024 * 1024)).isFailure)
            assertArrayEquals(original, image.readBytes())
            assertEquals(listOf("photo.jpg"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
    /** 单独基础写入在事务成功后保留原拍摄时间，不生成当前时间。 */
    @Test fun successfulWriteKeepsOriginalCaptureDate() {
        val root = Files.createTempDirectory("exif-success").toFile()
        try {
            val image = root.resolve("photo.jpg")
            javaClass.getResourceAsStream("/test.jpg")!!.use { input -> image.outputStream().use { input.copyTo(it) } }
            ExifInterface(image).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
                saveAttributes()
            }
            ExifWriter.writeBasicMetadata(image, null).getOrThrow()
            assertEquals("2020:01:02 03:04:05", ExifInterface(image).getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        } finally { root.deleteRecursively() }
    }
}
