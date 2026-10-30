package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NewToolsRenderersTest {

    private fun createSampleImage(file: File, width: Int = 300, height: Int = 500, color: Int = Color.BLUE): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun testDeviceMockupRenderer() {
        val root = Files.createTempDirectory("mockup-test").toFile()
        val sourceFile = createSampleImage(root.resolve("source.png"), 300, 600)
        val destFile = root.resolve("mockup-out.png")
        val result = DeviceMockupRenderer.render(
            source = ImageSource.FileSource(sourceFile),
            background = MockupBackground.DAWN,
            destination = destFile,
            format = ImageOutputFormat.PNG,
        )
        assertTrue(result is OperationResult.Success)
        assertTrue(destFile.exists())
        assertTrue(destFile.length() > 100L)
    }

    @Test
    fun testSignatureExtractorRenderer() {
        val root = Files.createTempDirectory("sig-test").toFile()
        val sourceFile = root.resolve("sig-source.png").apply {
            val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, 180f, 200f, Paint().apply { color = Color.rgb(205, 205, 205) })
                drawLine(70f, 100f, 330f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = 12f
                })
            }
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val destFile = root.resolve("sig-out.png")
        val result = SignatureExtractorRenderer.extract(
            source = ImageSource.FileSource(sourceFile),
            inkColor = SignatureInkColor.BLUE,
            destination = destFile,
        )
        assertTrue(result is OperationResult.Success)
        assertTrue(destFile.exists())
        assertTrue(destFile.length() > 100L)
        val output = BitmapFactory.decodeFile(destFile.path)
        assertTrue(output.width == 400 && output.height == 200)
        output.recycle()
    }

    @Test
    fun signatureMaskRemovesPaperShadowAndKeepsInk() {
        val width = 64
        val height = 32
        val sourcePixels = IntArray(width * height) { index ->
            if (index % width < width / 2) Color.rgb(205, 205, 205) else Color.WHITE
        }
        for (y in 12..19) {
            for (x in 10..53) sourcePixels[y * width + x] = Color.BLACK
        }

        val outputPixels = SignatureExtractorRenderer.extractInkPixels(
            sourcePixels = sourcePixels,
            width = width,
            height = height,
            inkColor = SignatureInkColor.BLUE,
        )

        assertTrue("paper shadow should remain transparent", Color.alpha(outputPixels[4 * width + 8]) < 16)
        assertTrue("signature stroke should remain visible", Color.alpha(outputPixels[16 * width + 40]) > 180)
    }

    @Test
    fun testPolaroidFrameRenderer() {
        val root = Files.createTempDirectory("polaroid-test").toFile()
        val sourceFile = createSampleImage(root.resolve("polaroid-source.jpg"), 400, 400)
        val destFile = root.resolve("polaroid-out.jpg")
        val result = PolaroidFrameRenderer.render(
            source = ImageSource.FileSource(sourceFile),
            style = PolaroidStyle.CLASSIC_WHITE,
            dateLabel = "2026.08.18 15:30",
            destination = destFile,
            format = ImageOutputFormat.JPEG,
        )
        assertTrue(result is OperationResult.Success)
        assertTrue(destFile.exists())
        assertTrue(destFile.length() > 100L)
    }

    @Test
    fun renderersHonorCancellationBeforeAllocatingOutput() {
        val root = Files.createTempDirectory("render-cancel-test").toFile()
        val sourceFile = createSampleImage(root.resolve("source.png"))
        val cancellation = OperationCancellation { true }

        val mockup = DeviceMockupRenderer.render(
            ImageSource.FileSource(sourceFile),
            MockupBackground.DAWN,
            root.resolve("mockup.png"),
            cancellation = cancellation,
        )
        val signature = SignatureExtractorRenderer.extract(
            ImageSource.FileSource(sourceFile),
            SignatureInkColor.BLACK,
            root.resolve("signature.png"),
            cancellation = cancellation,
        )
        val polaroid = PolaroidFrameRenderer.render(
            ImageSource.FileSource(sourceFile),
            PolaroidStyle.CLASSIC_WHITE,
            null,
            root.resolve("polaroid.jpg"),
            cancellation = cancellation,
        )

        assertTrue(mockup is OperationResult.Failure)
        assertTrue(signature is OperationResult.Failure)
        assertTrue(polaroid is OperationResult.Failure)
    }
}
