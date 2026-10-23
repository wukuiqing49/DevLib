package com.wkq.photo.mediastore

import android.graphics.Bitmap
import android.graphics.Color
import com.wkq.photo.core.operation.OperationException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class MediaOutputVerifierTest {

    @Test
    fun acceptsDecodableJpegAndPngOutputs() {
        val jpeg = createBitmapFile(Bitmap.CompressFormat.JPEG, "jpg", 640, 480)
        val png = createBitmapFile(Bitmap.CompressFormat.PNG, "png", 320, 640)
        try {
            MediaOutputVerifier.verifySource(jpeg, "image/jpeg")
            MediaOutputVerifier.verifySource(png, "image/png")
        } finally {
            jpeg.delete()
            png.delete()
        }
    }

    @Test
    fun acceptsTransparentPngOutput() {
        val png = createBitmapFile(Bitmap.CompressFormat.PNG, "png", 320, 320, transparent = true)
        try {
            MediaOutputVerifier.verifySource(png, "image/png")
        } finally {
            png.delete()
        }
    }

    @Test
    fun rejectsImageWithMismatchedMimeType() {
        val png = createBitmapFile(Bitmap.CompressFormat.PNG, "png", 12, 12)
        try {
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(png, "image/jpeg")
            }
        } finally {
            png.delete()
        }
    }

    @Test
    fun rejectsCorruptedAndEmptyImageOutputs() {
        val corrupted = File.createTempFile("output-contract", ".png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val empty = File.createTempFile("output-contract", ".jpg")
        try {
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(corrupted, "image/png")
            }
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(empty, "image/jpeg")
            }
        } finally {
            corrupted.delete()
            empty.delete()
        }
    }

    @Test
    fun acceptsLargeBitmapWithoutDecodingPixelsIntoTheVerifier() {
        val image = createBitmapFile(Bitmap.CompressFormat.JPEG, "jpg", 2_048, 1_536)
        try {
            MediaOutputVerifier.verifySource(image, "image/jpeg")
        } finally {
            image.delete()
        }
    }

    @Test
    fun validatesPdfZipAndTiffOutputEnvelopes() {
        val pdf = File.createTempFile("output-contract", ".pdf").apply {
            writeText("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n")
        }
        val zip = File.createTempFile("output-contract", ".zip").apply {
            ZipOutputStream(outputStream()).use { output ->
                output.putNextEntry(ZipEntry("image.jpg"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
        }
        val tiff = File.createTempFile("output-contract", ".tiff").apply {
            writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00))
        }
        try {
            MediaOutputVerifier.verifySource(pdf, "application/pdf")
            MediaOutputVerifier.verifySource(zip, "application/zip")
            MediaOutputVerifier.verifySource(tiff, "image/tiff")
        } finally {
            pdf.delete()
            zip.delete()
            tiff.delete()
        }
    }

    @Test
    fun rejectsTruncatedPdfZipAndTiffOutputs() {
        val pdf = File.createTempFile("output-contract", ".pdf").apply { writeText("%PDF-1.4\n") }
        val zip = File.createTempFile("output-contract", ".zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
        val tiff = File.createTempFile("output-contract", ".tiff").apply {
            writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
        }
        try {
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(pdf, "application/pdf")
            }
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(zip, "application/zip")
            }
            assertThrows(OperationException::class.java) {
                MediaOutputVerifier.verifySource(tiff, "image/tiff")
            }
        } finally {
            pdf.delete()
            zip.delete()
            tiff.delete()
        }
    }

    private fun createBitmapFile(
        format: Bitmap.CompressFormat,
        extension: String,
        width: Int,
        height: Int,
        transparent: Boolean = false,
    ): File {
        val file = File.createTempFile("output-contract", ".$extension")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (transparent) bitmap.eraseColor(Color.TRANSPARENT)
            file.outputStream().use { output ->
                check(bitmap.compress(format, 92, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
