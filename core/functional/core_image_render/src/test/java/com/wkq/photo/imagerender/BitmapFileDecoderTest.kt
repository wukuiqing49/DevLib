package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BitmapFileDecoderTest {

    /** 最终缩放尺寸合规但中间采样位图超预算时，必须在解码前拒绝。 */
    @Test fun budgetsIntermediateSampledBitmap() {
        val root = Files.createTempDirectory("bitmap-sampled-budget").toFile()
        try {
            val source = root.resolve("source.jpg")
            createOrientationFixture(source)
            val result = BitmapFileDecoder.decodeOriented(ImageSource.FileSource(source),
                BitmapDecodeOptions(100, 100), OperationBudget(maxTotalPixels = 7000))
            assertTrue(result is OperationResult.Failure)
            assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
        } finally { root.deleteRecursively() }
    }


    @Test
    fun supportsAllExifOrientationsAndDimensions() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        val root = Files.createTempDirectory("bitmap-all-exif-test").toFile()

        orientations.forEach { orientation ->
            val source = root.resolve("source-$orientation.jpg")
            createOrientationFixture(source)
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }

            val expectedWidth = if (orientation in SWAPPED_ORIENTATIONS) 180 else 120
            val expectedHeight = if (orientation in SWAPPED_ORIENTATIONS) 120 else 180
            assertEquals(
                "orientation=$orientation dimensions",
                expectedWidth to expectedHeight,
                BitmapFileDecoder.readOrientedDimensions(ImageSource.FileSource(source)),
            )

            val decoded = BitmapFileDecoder.decodeOriented(source)
            try {
                assertEquals("orientation=$orientation width", expectedWidth, decoded.width)
                assertEquals("orientation=$orientation height", expectedHeight, decoded.height)
            } finally {
                decoded.recycle()
            }
        }
    }

    @Test
    fun appliesExifRotation() {
        val root = Files.createTempDirectory("bitmap-exif-test").toFile()
        val source = root.resolve("source.jpg")
        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val decoded = BitmapFileDecoder.decodeOriented(source)
        try {
            assertEquals(20, decoded.width)
            assertEquals(40, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun samplesWhenOnlyWidthExceedsTarget() {
        val root = Files.createTempDirectory("bitmap-wide-test").toFile()
        val source = root.resolve("wide.png")
        Bitmap.createBitmap(3000, 100, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        val decoded = BitmapFileDecoder.decodeOriented(source, maxWidth = 500, maxHeight = 500)
        try {
            assertEquals(500, decoded.width)
            assertTrue(decoded.height <= 17)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun supportsParcelFileDescriptorWithoutTakingOwnership() {
        val root = Files.createTempDirectory("bitmap-pfd-test").toFile()
        val source = root.resolve("source.png")
        Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val result = BitmapFileDecoder.decodeOriented(ImageSource.ParcelFileDescriptorSource(descriptor))
            val bitmap = (result as OperationResult.Success).value
            try {
                assertEquals(24, bitmap.width)
                assertEquals(12, bitmap.height)
                assertTrue(descriptor.fileDescriptor.valid())
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun rejectsDecodedPixelBudgetOverflow() {
        val root = Files.createTempDirectory("bitmap-budget-test").toFile()
        val source = root.resolve("source.png")
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        val result = BitmapFileDecoder.decodeOriented(
            source = ImageSource.FileSource(source),
            budget = OperationBudget(maxWidth = 100, maxHeight = 100, maxTotalPixels = 9_999, maxPages = 1, timeoutMillis = 1_000)
        )

        assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
    }

    private fun createOrientationFixture(file: java.io.File) {
        Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
        }
    }

    companion object {
        private val SWAPPED_ORIENTATIONS = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )

    }
}
