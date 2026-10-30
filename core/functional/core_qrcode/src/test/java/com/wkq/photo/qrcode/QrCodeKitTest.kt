package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodeKitTest {

    /** 已释放的位图必须在申请像素数组前拒绝。 */
    @Test(expected = IllegalArgumentException::class) fun rejectsRecycledRecognitionBitmap() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        QrCodeKit.recognizeAll(bitmap)
    }


    @Test
    fun renderer_keeps_white_quiet_zone() {
        val bitmap = QRCodeGenerator.generate(
            QRCodeRequest("debug-qr", sizePx = 512, verifyScannable = false)
        ).let { (it as com.wkq.photo.core.operation.OperationResult.Success).value }
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        assertEquals(Color.BLACK, bitmap.getPixel(80, 80))
        bitmap.recycle()
    }

    @Test
    fun create_qr_code_then_recognize_returns_original_content() {
        val content = "https://photokit.example/album/42"
        val bitmap = QrCodeKit.create(content, 512, QrCodeKit.QrCodeStyle.OCEAN)

        try {
            assertEquals(content, QrCodeKit.recognizeAny(bitmap)?.content)
            assertEquals("QR_CODE", QrCodeKit.recognizeAny(bitmap)?.format)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun create_code128_then_recognize_returns_original_content() {
        val content = "PHOTO-2026-00042"
        val bitmap = QrCodeKit.createBarcode(content, QrCodeKit.BarcodeType.CODE_128, 900, 240)

        try {
            assertEquals(content, QrCodeKit.recognizeAny(bitmap)?.content)
            assertEquals("CODE_128", QrCodeKit.recognizeAny(bitmap)?.format)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun create_custom_colored_qr_code_then_recognize_returns_original_content() {
        val content = "https://photokit.example/qr/custom-color"
        val bitmap = QrCodeKit.create(
            QrCodeKit.QrCodeRequest(
                content = content,
                sizePx = 512,
                foregroundColor = Color.rgb(26, 115, 232)
            )
        )

        try {
            assertEquals(content, QrCodeKit.recognizeAny(bitmap)?.content)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun recognize_all_returns_multiple_qr_codes() {
        val first = QrCodeKit.create("privacy-region-first", 320)
        val second = QrCodeKit.create("privacy-region-second", 320)
        val combined = Bitmap.createBitmap(720, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).apply {
                drawBitmap(first, 0f, 0f, null)
                drawBitmap(second, 400f, 0f, null)
            }
        }

        try {
            assertEquals(
                setOf("privacy-region-first", "privacy-region-second"),
                QrCodeKit.recognizeAll(combined).map { it.content }.toSet(),
            )
        } finally {
            first.recycle()
            second.recycle()
            combined.recycle()
        }
    }
}
