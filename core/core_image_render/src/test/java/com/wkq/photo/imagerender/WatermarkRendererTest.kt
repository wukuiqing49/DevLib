package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatermarkRendererTest {

    @Test
    fun rendersRequestedPngFormat() {
        val root = Files.createTempDirectory("watermark-png-test").toFile()
        val source = createImage(root.resolve("source.png"))
        val output = root.resolve("output.png")

        WatermarkRenderer.render(
            source,
            output,
            WatermarkContent.Text("Batch Photo"),
            WatermarkSpec(WatermarkPosition.BOTTOM_END, relativeSize = 0.1f, alpha = 180),
            WatermarkRenderOptions(WatermarkOutputFormat.PNG)
        )

        assertArrayEquals(byteArrayOf(-119, 80, 78, 71), output.readBytes().copyOf(4))
    }

    @Test
    fun successfulRenderReplacesExistingOutputWithoutTemporaryFiles() {
        val root = Files.createTempDirectory("watermark-replace-test").toFile()
        val source = createImage(root.resolve("source.png"))
        val output = root.resolve("output.jpg").apply { writeText("existing-output") }

        WatermarkRenderer.render(
            source,
            output,
            WatermarkContent.Text("Watermark"),
            WatermarkSpec(WatermarkPosition.CENTER, relativeSize = 0.2f, alpha = 255)
        )

        assertArrayEquals(byteArrayOf(-1, -40, -1), output.readBytes().copyOf(3))
        assertFalse(root.listFiles().orEmpty().any { it.extension == "tmp" || it.extension == "bak" })
    }

    @Test
    fun supportsNinePositionsAndTiledRotatedWatermark() {
        assertEquals(9, WatermarkPosition.entries.size)
        val root = Files.createTempDirectory("watermark-tiled-test").toFile()
        val source = createImage(root.resolve("source.png"))
        val watermark = createImage(root.resolve("watermark.png"), 40, 40, Color.RED)
        val output = root.resolve("output.png")

        WatermarkRenderer.render(
            source,
            output,
            WatermarkContent.Image(watermark),
            WatermarkSpec(WatermarkPosition.TOP_START, relativeSize = 0.08f, alpha = 128),
            WatermarkRenderOptions(
                outputFormat = WatermarkOutputFormat.PNG,
                placement = WatermarkPlacement.TILED,
                rotationDegrees = -30f,
                tileSpacingRatio = 0.75f
            )
        )

        assertArrayEquals(byteArrayOf(-119, 80, 78, 71), output.readBytes().copyOf(4))
    }

    @Test
    fun rendersImageWatermarkWithOpacity() {
        val root = Files.createTempDirectory("watermark-image-test").toFile()
        val source = createImage(root.resolve("source.png"))
        val watermark = createImage(root.resolve("watermark.png"), 40, 40, Color.RED)
        val output = root.resolve("output.png")

        WatermarkRenderer.render(
            source,
            output,
            WatermarkContent.Image(watermark),
            WatermarkSpec(WatermarkPosition.TOP_START, relativeSize = 0.2f, alpha = 128),
            WatermarkRenderOptions(WatermarkOutputFormat.PNG)
        )

        val rendered = BitmapFactory.decodeFile(output.absolutePath)
        try {
            assertTrue(Color.red(rendered.getPixel(10, 10)) > 0)
        } finally {
            rendered.recycle()
        }
    }

    private fun createImage(file: File, width: Int = 320, height: Int = 200, color: Int = Color.BLUE): File {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }
}
