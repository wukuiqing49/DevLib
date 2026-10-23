package com.wkq.photo.qrcode

import android.graphics.Color
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QRCodeGeneratorTest {
    @Test
    fun wifi_payload_escapes_reserved_characters() {
        val encoded = QRPayload.Wifi(
            ssid = "Office;5G",
            password = "p:a,s",
            hidden = true
        ).encode()

        assertEquals("WIFI:T:WPA;S:Office\\;5G;P:p\\:a\\,s;H:true;;", encoded)
    }

    @Test
    fun contact_payload_encodes_vcard_3() {
        val encoded = QRPayload.Contact(
            name = "Zhang, San",
            phone = "+86 13800000000",
            email = "zhang@example.com",
            organization = "Photo;Kit"
        ).encode()

        assertTrue(encoded.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(encoded.contains("FN:Zhang\\, San"))
        assertTrue(encoded.contains("ORG:Photo\\;Kit"))
        assertTrue(encoded.endsWith("END:VCARD"))
    }

    @Test
    fun url_payload_rejects_non_web_scheme() {
        try {
            QRPayload.Url("ftp://example.com").encode()
            fail("Expected invalid URL scheme")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun function_mask_separates_finders_from_data_modules() {
        val mask = FunctionModuleMask.create(21)

        assertTrue(mask.isFinder(0, 0))
        assertTrue(mask.isFunction(7, 7))
        assertTrue(mask.isFunction(6, 10))
        assertFalse(mask.isFunction(12, 12))
    }

    @Test
    fun generator_returns_cancelled_result_before_allocating_bitmap() {
        val result = QRCodeGenerator.generate(
            QRCodeRequest("cancelled"),
            cancellation = OperationCancellation { true }
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.CANCELLED, (result as OperationResult.Failure).code)
    }

    @Test
    fun generator_rejects_low_contrast_colors() {
        val result = QRCodeGenerator.generate(
            QRCodeRequest(
                content = "low contrast",
                colorStyle = QRColorStyle.Solid(Color.rgb(230, 230, 230), Color.WHITE)
            )
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.INVALID_INPUT, (result as OperationResult.Failure).code)
    }

    @Test
    fun generator_creates_scannable_hd_bitmap_with_statistics() {
        val content = "https://photokit.example/qr/hd"
        val result = QRCodeGenerator.generate(QRCodeRequest(content, sizePx = 1024))
        assertTrue(result is OperationResult.Success)
        val success = result as OperationResult.Success

        try {
            assertEquals(1024, success.value.width)
            assertEquals(1024, success.value.height)
            assertEquals(content, QrCodeKit.recognize(success.value))
            assertEquals(1024, success.statistics.outputWidth)
            assertEquals(1024L * 1024L, success.statistics.outputPixels)
        } finally {
            success.value.recycle()
        }
    }

    @Test
    fun renderer_supports_rounded_gradient_without_changing_output_size() {
        val result = QRCodeGenerator.generate(
            QRCodeRequest(
                content = "styled preview",
                sizePx = 512,
                moduleStyle = QRModuleStyle.ROUNDED_SQUARE,
                finderPatternStyle = QRFinderPatternStyle.ROUNDED,
                colorStyle = QRColorStyle.Gradient(Color.rgb(27, 94, 160), Color.rgb(0, 55, 109)),
                verifyScannable = false
            )
        )
        assertTrue(result is OperationResult.Success)
        val bitmap = (result as OperationResult.Success).value
        assertEquals(512, bitmap.width)
        bitmap.recycle()
    }
}
