package com.phototoolkit.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.MetadataPolicy
import com.wkq.photo.core.operation.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VipsNativeInstrumentedTest {

    @Test
    fun commonCompositionFillsPortraitWidthWithoutStretchingHead() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-id-photo-width-fill-test").apply { mkdirs() }
        val input = root.resolve("portrait.png")
        val output = root.resolve("id-photo.jpg").apply { delete() }
        Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val mask = ByteArray(1000 * 1000)
        for (y in 150 until 800) {
            for (x in 275 until 725) mask[y * 1000 + x] = 0xFF.toByte()
        }
        val compositionMetrics = doubleArrayOf(Double.NaN, Double.NaN)

        val error = VipsNative.createIdPhoto(
            inputPath = input.path,
            outputPath = output.path,
            alphaMask = mask,
            maskWidth = 1000,
            maskHeight = 1000,
            faceLeft = 400.0,
            faceTop = 250.0,
            faceWidth = 200.0,
            faceHeight = 300.0,
            outputWidth = 600,
            outputHeight = 600,
            outputDpi = 300,
            faceHeightRatio = 0.60,
            faceTopRatio = 0.07,
            autoComposition = false,
            faceHeightMinRatio = 0.50,
            faceHeightMaxRatio = 0.69,
            faceTopMinRatio = 0.02,
            faceTopMaxRatio = 0.14,
            faceCenterXRatio = 0.50,
            portraitBottomRatio = 1.0,
            maxPortraitWidthRatio = 1.0,
            preferBottomAlignment = true,
            compositionMetrics = compositionMetrics,
            strictComposition = false,
            preserveBackground = false,
            red = 255,
            green = 255,
            blue = 255,
        )

        assertEquals("", error)
        assertTrue(compositionMetrics[0] in 0.50..0.69)
        val result = BitmapFactory.decodeFile(output.path)
        try {
            assertEquals(600, result.width)
            assertEquals(600, result.height)
            val centerY = result.height / 2
            assertTrue(Color.red(result.getPixel(0, centerY)) > 200)
            assertTrue(Color.red(result.getPixel(result.width - 1, centerY)) > 200)
            assertTrue(Color.red(result.getPixel(result.width / 2, result.height - 1)) > 200)
        } finally {
            result.recycle()
        }
    }

    @Test
    fun isolatedAlphaNoiseDoesNotChangePortraitComposition() {
        val root = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("vips-id-photo-alpha-noise-test").apply { mkdirs() }
        val input = root.resolve("portrait.png")
        Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        fun compose(includeNoise: Boolean): DoubleArray {
            val mask = ByteArray(1000 * 1000)
            for (y in 100 until 701) {
                for (x in 300 until 700) mask[y * 1000 + x] = 0xFF.toByte()
            }
            if (includeNoise) mask[900 * 1000] = 0xFF.toByte()
            val output = root.resolve(if (includeNoise) "noisy.jpg" else "clean.jpg").apply { delete() }
            val metrics = doubleArrayOf(Double.NaN, Double.NaN)
            assertEquals(
                "",
                VipsNative.createIdPhoto(
                    inputPath = input.path,
                    outputPath = output.path,
                    alphaMask = mask,
                    maskWidth = 1000,
                    maskHeight = 1000,
                    faceLeft = 400.0,
                    faceTop = 200.0,
                    faceWidth = 200.0,
                    faceHeight = 300.0,
                    outputWidth = 600,
                    outputHeight = 600,
                    outputDpi = 300,
                    faceHeightRatio = 0.40,
                    faceTopRatio = 0.10,
                    autoComposition = false,
                    faceHeightMinRatio = 0.35,
                    faceHeightMaxRatio = 0.50,
                    faceTopMinRatio = 0.05,
                    faceTopMaxRatio = 0.15,
                    faceCenterXRatio = 0.50,
                    portraitBottomRatio = 1.0,
                    maxPortraitWidthRatio = 0.50,
                    preferBottomAlignment = true,
                    compositionMetrics = metrics,
                    strictComposition = false,
                    preserveBackground = false,
                    red = 255,
                    green = 255,
                    blue = 255,
                ),
            )
            return metrics
        }

        val clean = compose(includeNoise = false)
        val noisy = compose(includeNoise = true)
        assertEquals(clean[0], noisy[0], 0.001)
        assertEquals(clean[1], noisy[1], 0.001)
    }

    @Test
    fun adaptiveIdPhotoCompositionKeepsOnlyHeadAndShoulders() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-id-photo-adaptive-test").apply { mkdirs() }
        val input = root.resolve("portrait.png")
        val output = root.resolve("id-photo.jpg").apply { delete() }
        Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
            Canvas(bitmap).drawRect(
                0f,
                760f,
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                Paint().apply { color = Color.GREEN },
            )
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val mask = ByteArray(1000 * 1000)
        for (y in 100 until 1000) {
            // 宽肩蒙版延伸到下半身；肩宽不能缩小头部，绿色身体区域也必须裁掉。
            for (x in 120 until 880) mask[y * 1000 + x] = 0xFF.toByte()
        }

        val compositionMetrics = doubleArrayOf(Double.NaN, Double.NaN)
        val error = VipsNative.createIdPhoto(
            inputPath = input.path,
            outputPath = output.path,
            alphaMask = mask,
            maskWidth = 1000,
            maskHeight = 1000,
            faceLeft = 350.0,
            faceTop = 200.0,
            faceWidth = 300.0,
            faceHeight = 400.0,
            outputWidth = 600,
            outputHeight = 600,
            outputDpi = 300,
            faceHeightRatio = 0.68,
            faceTopRatio = 0.08,
            autoComposition = false,
            faceHeightMinRatio = 0.64,
            faceHeightMaxRatio = 0.72,
            faceTopMinRatio = 0.04,
            faceTopMaxRatio = 0.12,
            faceCenterXRatio = 0.50,
            portraitBottomRatio = 1.0,
            maxPortraitWidthRatio = 1.25,
            preferBottomAlignment = true,
            compositionMetrics = compositionMetrics,
            strictComposition = false,
            preserveBackground = false,
            red = 255,
            green = 255,
            blue = 255,
        )

        assertEquals("", error)
        assertEquals(0.68, compositionMetrics[0], 0.001)
        assertEquals(0.04, compositionMetrics[1], 0.001)
        val result = BitmapFactory.decodeFile(output.path)
        try {
            assertTrue(Color.green(result.getPixel(result.width / 2, result.height - 1)) > 200)
        } finally {
            result.recycle()
        }
    }

    @Test
    fun autoRotateBakesExifOrientationIntoPixels() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-orientation-test").apply { mkdirs() }
        val input = root.resolve("camera.jpg").apply { delete() }
        val output = root.resolve("normalized.png").apply { delete() }
        Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(input).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
            saveAttributes()
        }

        assertEquals("", VipsNative.autoRotate(input.path, output.path))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(40, bounds.outWidth)
        assertEquals(80, bounds.outHeight)
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface(output).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ),
        )
    }

    @Test
    fun strictIdPhotoCompositionKeepsPlannedHeadTopWhenBottomAlignmentIsPreferred() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-id-photo-composition-test").apply { mkdirs() }
        val input = root.resolve("portrait.png")
        val output = root.resolve("id-photo.jpg").apply { delete() }
        Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val compositionMetrics = doubleArrayOf(Double.NaN, Double.NaN)

        val error = VipsNative.createIdPhoto(
            inputPath = input.path,
            outputPath = output.path,
            alphaMask = ByteArray(1000 * 1000) { 0xFF.toByte() },
            maskWidth = 1000,
            maskHeight = 1000,
            faceLeft = 350.0,
            faceTop = 200.0,
            faceWidth = 300.0,
            faceHeight = 400.0,
            outputWidth = 600,
            outputHeight = 600,
            outputDpi = 300,
            faceHeightRatio = 0.60,
            faceTopRatio = 0.10,
            autoComposition = false,
            faceHeightMinRatio = 0.55,
            faceHeightMaxRatio = 0.65,
            faceTopMinRatio = 0.06,
            faceTopMaxRatio = 0.14,
            faceCenterXRatio = 0.50,
            portraitBottomRatio = 1.0,
            maxPortraitWidthRatio = 1.25,
            preferBottomAlignment = true,
            compositionMetrics = compositionMetrics,
            strictComposition = true,
            preserveBackground = false,
            red = 255,
            green = 255,
            blue = 255,
        )

        assertEquals("", error)
        assertTrue(output.isFile && output.length() > 0)
        assertEquals(0.60, compositionMetrics[0], 0.001)
        assertEquals(0.10, compositionMetrics[1], 0.001)
    }

    @Test
    fun sourcePreservingCompositionClampsCropToAvailableImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-id-photo-preserve-crop-test").apply { mkdirs() }
        val input = root.resolve("portrait.png")
        val output = root.resolve("id-photo.jpg").apply { delete() }
        Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val mask = ByteArray(1000 * 1000)
        for (y in 700 until 901) {
            for (x in 300 until 701) mask[y * 1000 + x] = 0xFF.toByte()
        }
        val metrics = doubleArrayOf(Double.NaN, Double.NaN)

        assertEquals(
            "",
            VipsNative.createIdPhoto(
                inputPath = input.path,
                outputPath = output.path,
                alphaMask = mask,
                maskWidth = 1000,
                maskHeight = 1000,
                faceLeft = 400.0,
                faceTop = 750.0,
                faceWidth = 200.0,
                faceHeight = 150.0,
                outputWidth = 600,
                outputHeight = 600,
                outputDpi = 300,
                faceHeightRatio = 0.30,
                faceTopRatio = 0.0,
                autoComposition = true,
                faceHeightMinRatio = 0.25,
                faceHeightMaxRatio = 0.35,
                faceTopMinRatio = 0.0,
                faceTopMaxRatio = 0.89,
                faceCenterXRatio = 0.50,
                portraitBottomRatio = 1.0,
                maxPortraitWidthRatio = 1.25,
                preferBottomAlignment = false,
                compositionMetrics = metrics,
                strictComposition = false,
                preserveBackground = true,
                red = 255,
                green = 255,
                blue = 255,
            ),
        )
        assertTrue(output.isFile && output.length() > 0)
        val result = BitmapFactory.decodeFile(output.path)
        try {
            assertEquals(600, result.width)
            assertEquals(600, result.height)
        } finally {
            result.recycle()
        }
        assertTrue(metrics[1] >= 0.0)
    }

    @Test
    fun enhancementPresetsProduceDistinctValidOutputs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-enhancement-test").apply { mkdirs() }
        val input = root.resolve("document.png")
        Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.rgb(226, 220, 205))
                drawRect(12f, 18f, 148f, 30f, Paint().apply { color = Color.rgb(35, 35, 35) })
                drawRect(12f, 48f, 110f, 60f, Paint().apply { color = Color.rgb(100, 100, 100) })
            }
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        val enhanced = root.resolve("enhanced.png").apply { delete() }
        val clear = root.resolve("clear.png").apply { delete() }
        val blackWhite = root.resolve("black-white.png").apply { delete() }
        assertEquals("", VipsNative.autoEnhance(input.path, enhanced.path))
        assertEquals("", VipsNative.ocrEnhance(input.path, clear.path))
        assertEquals("", VipsNative.blackWhite(input.path, blackWhite.path))
        assertTrue(VipsNative.imageInfo(enhanced.path).startsWith("160x96,"))
        assertTrue(VipsNative.imageInfo(clear.path).contains("bands=1"))
        assertTrue(VipsNative.imageInfo(blackWhite.path).contains("bands=1"))

        val binary = BitmapFactory.decodeFile(blackWhite.path)
        try {
            val pixels = IntArray(binary.width * binary.height)
            binary.getPixels(pixels, 0, binary.width, 0, 0, binary.width, binary.height)
            assertTrue(pixels.all { Color.red(it) == 0 || Color.red(it) == 255 })
            assertTrue(pixels.any { Color.red(it) == 0 })
            assertTrue(pixels.any { Color.red(it) == 255 })
        } finally {
            binary.recycle()
        }
    }

    @Test
    fun executesRealImagePipelineOnCurrentAbi() {
        assertTrue(Build.SUPPORTED_ABIS.any(SUPPORTED_ABIS::contains))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("vips-native-test").apply { mkdirs() }
        val input = root.resolve("input.jpg")
        val output = root.resolve("output.png").apply { delete() }
        Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.GREEN)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }

        assertEquals("", VipsNative.initialize())
        assertTrue(VipsNative.version().isNotBlank())
        assertTrue(VipsOperationExecutor.configureRuntime() is OperationResult.Success)
        val result = VipsOperationExecutor.execute(
            inputFile = input,
            outputFile = output,
            operation = NativeImageOperation.ResizeTo(ImageResizeSpec(32, 32, ImageResizeMode.CONTAIN)),
            encodeOptions = ImageEncodeOptions(ImageOutputFormat.PNG, 100, MetadataPolicy.STRIP_ALL)
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        assertTrue(output.isFile && output.length() > 0)
        assertTrue(VipsNative.imageInfo(output.path).startsWith("32x16,"))

        val tiff = root.resolve("output.tiff").apply { delete() }
        val tiffResult = VipsOperationExecutor.execute(
            inputFile = input,
            outputFile = tiff,
            operation = NativeImageOperation.Convert,
            encodeOptions = ImageEncodeOptions(ImageOutputFormat.TIFF, 100, MetadataPolicy.STRIP_ALL),
        )
        assertTrue(tiffResult.toString(), tiffResult is OperationResult.Success)
        assertTrue(tiff.isFile && tiff.length() > 0)
        assertTrue(VipsNative.imageInfo(tiff.path).startsWith("64x32,"))

        val limited = root.resolve("limited.jpg").apply { delete() }
        val limitedResult = VipsOperationExecutor.compressJpegToBytes(
            inputFile = input,
            outputFile = limited,
            targetBytes = 4_096,
            maxQuality = 80,
        )
        assertTrue(limitedResult.toString(), limitedResult is OperationResult.Success)
        assertTrue(limited.length() in 1..4_096)

        val cropped = root.resolve("cropped.png").apply { delete() }
        val cropResult = VipsOperationExecutor.execute(
            input,
            cropped,
            NativeImageOperation.Crop(left = 8, top = 4, width = 40, height = 20),
            ImageEncodeOptions(ImageOutputFormat.PNG, 100, MetadataPolicy.STRIP_ALL),
        )
        assertTrue(cropResult.toString(), cropResult is OperationResult.Success)
        assertTrue(VipsNative.imageInfo(cropped.path).startsWith("40x20,"))

        val padded = root.resolve("padded.png").apply { delete() }
        val padResult = VipsOperationExecutor.execute(
            cropped,
            padded,
            NativeImageOperation.Pad(width = 64, height = 64, red = 240, green = 240, blue = 240),
            ImageEncodeOptions(ImageOutputFormat.PNG, 100, MetadataPolicy.STRIP_ALL),
        )
        assertTrue(padResult.toString(), padResult is OperationResult.Success)
        assertTrue(VipsNative.imageInfo(padded.path).startsWith("64x64,"))

        val flattened = root.resolve("flattened.jpg").apply { delete() }
        val flattenResult = VipsOperationExecutor.execute(
            padded,
            flattened,
            NativeImageOperation.FlattenBackground(red = 255, green = 255, blue = 255),
            ImageEncodeOptions(ImageOutputFormat.JPEG, 90, MetadataPolicy.STRIP_ALL),
        )
        assertTrue(flattenResult.toString(), flattenResult is OperationResult.Success)
        assertTrue(VipsNative.imageInfo(flattened.path).startsWith("64x64,"))
    }

    companion object {
        private val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a")
    }
}
