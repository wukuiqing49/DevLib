package com.wkq.util.exif

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PhotoFormatPolicyTest {
    @Test fun jpegUsesBytesNotExtension() {
        assertEquals(PhotoFormatPolicy.Format.JPEG,
            PhotoFormatPolicy.detect(ByteArrayInputStream(byteArrayOf(-1, -40, -1, -32))))
    }

    @Test fun pngSignature() {
        assertEquals(PhotoFormatPolicy.Format.PNG, PhotoFormatPolicy.detect(ByteArrayInputStream(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))))
    }

    @Test fun webpRequiresBothRiffAndWebp() {
        assertEquals(PhotoFormatPolicy.Format.WEBP,
            PhotoFormatPolicy.detect(ByteArrayInputStream("RIFF0000WEBP".toByteArray())))
        assertEquals(PhotoFormatPolicy.Format.UNKNOWN,
            PhotoFormatPolicy.detect(ByteArrayInputStream("RIFF0000WAVE".toByteArray())))
    }

    @Test fun shortOrEmptyInputIsNotAcceptedAsJpeg() {
        listOf(byteArrayOf(), byteArrayOf(-1, -40), "not an image".toByteArray()).forEach {
            assertEquals(PhotoFormatPolicy.Format.UNKNOWN, PhotoFormatPolicy.detect(ByteArrayInputStream(it)))
        }
    }

    @Test fun partialReadsAreHandled() {
        val data = "RIFF0000WEBP".toByteArray()
        val input = object : InputStream() {
            var index = 0
            override fun read(): Int = if (index == data.size) -1 else data[index++].toInt() and 255
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val value = read()
                if (value < 0) return -1
                b[off] = value.toByte()
                return 1
            }
        }
        assertEquals(PhotoFormatPolicy.Format.WEBP, PhotoFormatPolicy.detect(input))
    }

    @Test fun capabilityDoesNotConfuseReadingWithWriting() {
        PhotoFormatPolicy.Operation.values().forEach { operation ->
            assertTrue(PhotoFormatPolicy.supportsNative(PhotoFormatPolicy.Format.JPEG, operation))
            listOf(PhotoFormatPolicy.Format.PNG, PhotoFormatPolicy.Format.WEBP).forEach {
                assertTrue(PhotoFormatPolicy.supportsNative(it, operation))
            }
            assertFalse(PhotoFormatPolicy.supportsNative(PhotoFormatPolicy.Format.UNKNOWN, operation))
        }
    }

    @Test fun preservesOriginalJpegNameAndCase() {
        assertEquals("采访原图.JPEG", name("采访原图.JPEG", PhotoFormatPolicy.Format.JPEG))
    }

    @Test fun conversionChangesOnlyExtension() {
        assertEquals("采访.现场.jpg", name("采访.现场.heic", PhotoFormatPolicy.Format.JPEG))
    }

    @Test fun extensionMustMatchActualOutput() {
        assertEquals("photo.jpg", name("photo.png", PhotoFormatPolicy.Format.JPEG))
        assertEquals("photo.png", name("photo.jpg", PhotoFormatPolicy.Format.PNG))
    }

    @Test fun missingNameUsesFallbackAndAddsExtension() {
        assertEquals("fallback.jpg", PhotoFormatPolicy.outputName(null, PhotoFormatPolicy.Format.JPEG, "fallback"))
    }

    @Test fun pathCharactersCannotEscapeOutputDirectory() {
        assertEquals(".._private_photo.jpg", name("../private/photo.jpg", PhotoFormatPolicy.Format.JPEG))
        assertEquals("photo.jpg", name("..", PhotoFormatPolicy.Format.JPEG))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOutputIsNotLabelledJpeg() {
        name("photo.jpg", PhotoFormatPolicy.Format.UNKNOWN)
    }

    private fun name(value: String, format: PhotoFormatPolicy.Format) =
        PhotoFormatPolicy.outputName(value, format, "fallback.jpg")
}
