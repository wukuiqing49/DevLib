package com.wkq.photo.imagerender

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.MetadataPolicy
import com.wkq.photo.core.operation.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnnotationRendererTest {
    @Test
    fun historySupportsBoundedUndoAndRedo() {
        val history = AnnotationHistory(maxStates = 2)
        val first = AnnotationDocument(listOf(AnnotationLayer.Cover(NormalizedRect(0f, 0f, 0.2f, 0.2f), Color.BLACK)))
        val second = AnnotationDocument(listOf(AnnotationLayer.Number(NormalizedPoint(0.5f, 0.5f), 1)))

        history.update(first)
        history.update(second)

        assertEquals(first, history.undo())
        assertEquals(second, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun mosaicAndBlurChangeOnlySelectedRegions() {
        val root = Files.createTempDirectory("annotation-redaction-test").toFile()
        val source = root.resolve("source.png")
        val output = root.resolve("output.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb(x * 4, y * 4, (x + y) * 2))
            }
        }
        source.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        val result = AnnotationRenderer.render(
            source = ImageSource.FileSource(source),
            output = output,
            document = AnnotationDocument(
                listOf(
                    AnnotationLayer.Mosaic(NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f), 0.08f),
                    AnnotationLayer.Blur(NormalizedRect(0.55f, 0.55f, 0.9f, 0.9f), 0.03f),
                )
            ),
            encodeOptions = ImageEncodeOptions(ImageOutputFormat.PNG, 100, MetadataPolicy.STRIP_ALL),
        )

        assertTrue(result is OperationResult.Success)
        val rendered = BitmapFactory.decodeFile(output.absolutePath)
        assertEquals(0, countPixelDifferences(bitmap, rendered, 0..10, 0..10))
        assertTrue(countPixelDifferences(bitmap, rendered, 13..31, 13..31) > 0)
        assertTrue(countPixelDifferences(bitmap, rendered, 36..56, 36..56) > 0)
        bitmap.recycle()
        rendered.recycle()
    }

    private fun countPixelDifferences(
        original: Bitmap,
        rendered: Bitmap,
        xRange: IntRange,
        yRange: IntRange,
    ): Int = yRange.sumOf { y ->
        xRange.count { x -> original.getPixel(x, y) != rendered.getPixel(x, y) }
    }
}
