package com.phototoolkit.raw

import com.phototoolkit.engine.ImageResizeSpec
import com.phototoolkit.engine.NativeImageOperation
import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RawDevelopOptionsTest {
    @Test
    fun acceptsSixteenBitTiff() {
        val options = RawDevelopOptions(format = RawOutputFormat.TIFF, outputBits = 16)
        assertEquals(16, options.outputBits)
    }

    @Test
    fun rejectsSixteenBitJpeg() {
        try {
            RawDevelopOptions(format = RawOutputFormat.JPEG, outputBits = 16)
            fail("Expected invalid RAW output options")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun modelsInspectPreviewDevelopAndVipsPostPipeline() {
        val request = RawPipelineRequest(
            source = File("source.dng"),
            previewOutput = File("preview.jpg"),
            output = File("result.webp"),
            postOperations = listOf(
                NativeImageOperation.ResizeTo(ImageResizeSpec(1600, 1200)),
                NativeImageOperation.Adjust(0.1, 1.05),
            ),
            encodeOptions = ImageEncodeOptions(format = ImageOutputFormat.WEBP),
        )

        assertEquals(2, request.postOperations.size)
        assertEquals(ImageOutputFormat.WEBP, request.encodeOptions.format)
    }
}
