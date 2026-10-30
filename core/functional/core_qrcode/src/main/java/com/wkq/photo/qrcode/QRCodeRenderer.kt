package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Version
import com.wkq.photo.core.operation.OperationContext
import java.util.Arrays

object QRCodeRenderer {
    fun render(matrix: BitMatrix, request: QRCodeRequest, context: OperationContext): Bitmap {
        require(matrix.width == matrix.height) { "QR matrix must be square" }
        require(request.sizePx in 128..2048) { "QR size must be between 128 and 2048 pixels" }
        require(request.quietZoneModules in 4..8) { "QR quiet zone must be between 4 and 8 modules" }
        require(request.backgroundVeilAlpha in 0.75f..0.95f) { "Background veil must preserve QR contrast" }
        validateContrast(request.colorStyle)

        val moduleCount = matrix.width
        val totalModules = moduleCount + request.quietZoneModules * 2
        val moduleSize = (request.sizePx / totalModules).coerceAtLeast(1).toFloat()
        val renderedSide = moduleSize * totalModules
        val origin = ((request.sizePx - renderedSide) / 2f).toInt().toFloat()
        val qrOrigin = origin + request.quietZoneModules * moduleSize
        val qrBounds = RectF(qrOrigin, qrOrigin, qrOrigin + moduleCount * moduleSize, qrOrigin + moduleCount * moduleSize)
        val backgroundColor = request.colorStyle.backgroundColor
        if (request.moduleStyle == QRModuleStyle.SQUARE &&
            request.finderPatternStyle == QRFinderPatternStyle.SQUARE &&
            request.colorStyle is QRColorStyle.Solid &&
            request.backgroundImage == null &&
            request.logo == null
        ) {
            return renderClassicPixels(
                matrix,
                request.sizePx,
                origin.toInt() + request.quietZoneModules * moduleSize.toInt(),
                moduleSize.toInt(),
                request.colorStyle.foregroundColor,
                backgroundColor,
                context
            )
        }
        val bitmap = Bitmap.createBitmap(request.sizePx, request.sizePx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(backgroundColor)
        val canvas = Canvas(bitmap)
        request.backgroundImage?.let {
            QRBackgroundProcessor.draw(canvas, it, qrBounds, backgroundColor, request.backgroundVeilAlpha)
        }

        val paint = Paint().apply {
            style = Paint.Style.FILL
            when (val colorStyle = request.colorStyle) {
                is QRColorStyle.Solid -> color = colorStyle.foregroundColor
                is QRColorStyle.Gradient -> shader = createGradient(colorStyle, qrBounds)
            }
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = backgroundColor
        }
        val mask = FunctionModuleMask.create(moduleCount)
        for (y in 0 until moduleCount) {
            context.checkActive()
            for (x in 0 until moduleCount) {
                if (!matrix[x, y] || mask.isFinder(x, y)) continue
                drawModule(
                    canvas = canvas,
                    left = qrOrigin + x * moduleSize,
                    top = qrOrigin + y * moduleSize,
                    size = moduleSize,
                    style = if (mask.isFunction(x, y)) QRModuleStyle.SQUARE else request.moduleStyle,
                    paint = paint
                )
            }
        }
        drawFinders(canvas, moduleCount, qrOrigin, moduleSize, request.finderPatternStyle, paint, backgroundPaint)
        request.logo?.let { QRLogoProcessor.draw(canvas, it, qrBounds, moduleSize) }
        return bitmap
    }

    private fun renderClassicPixels(
        matrix: BitMatrix,
        sizePx: Int,
        qrOrigin: Int,
        moduleSize: Int,
        foregroundColor: Int,
        backgroundColor: Int,
        context: OperationContext
    ): Bitmap {
        val pixels = IntArray(sizePx * sizePx) { backgroundColor }
        for (moduleY in 0 until matrix.height) {
            context.checkActive()
            val top = qrOrigin + moduleY * moduleSize
            for (moduleX in 0 until matrix.width) {
                if (!matrix[moduleX, moduleY]) continue
                val left = qrOrigin + moduleX * moduleSize
                for (pixelY in top until top + moduleSize) {
                    Arrays.fill(pixels, pixelY * sizePx + left, pixelY * sizePx + left + moduleSize, foregroundColor)
                }
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun drawModule(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        style: QRModuleStyle,
        paint: Paint
    ) {
        when (style) {
            QRModuleStyle.SQUARE -> canvas.drawRect(left, top, left + size, top + size, paint)
            QRModuleStyle.ROUNDED_SQUARE -> {
                paint.isAntiAlias = true
                val inset = size * 0.06f
                canvas.drawRoundRect(
                    left + inset,
                    top + inset,
                    left + size - inset,
                    top + size - inset,
                    size * 0.28f,
                    size * 0.28f,
                    paint
                )
                paint.isAntiAlias = false
            }
            QRModuleStyle.CIRCLE_DOT -> {
                paint.isAntiAlias = true
                canvas.drawCircle(left + size / 2f, top + size / 2f, size * 0.44f, paint)
                paint.isAntiAlias = false
            }
        }
    }

    private fun drawFinders(
        canvas: Canvas,
        moduleCount: Int,
        origin: Float,
        moduleSize: Float,
        style: QRFinderPatternStyle,
        foreground: Paint,
        background: Paint
    ) {
        listOf(0 to 0, (moduleCount - 7) to 0, 0 to (moduleCount - 7)).forEach { (moduleX, moduleY) ->
            val left = origin + moduleX * moduleSize
            val top = origin + moduleY * moduleSize
            drawFinderLayer(canvas, left, top, 7f * moduleSize, style, foreground, moduleSize)
            drawFinderLayer(canvas, left + moduleSize, top + moduleSize, 5f * moduleSize, style, background, moduleSize)
            drawFinderLayer(canvas, left + 2f * moduleSize, top + 2f * moduleSize, 3f * moduleSize, style, foreground, moduleSize)
        }
    }

    private fun drawFinderLayer(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        style: QRFinderPatternStyle,
        paint: Paint,
        moduleSize: Float
    ) {
        when (style) {
            QRFinderPatternStyle.SQUARE -> canvas.drawRect(left, top, left + size, top + size, paint)
            QRFinderPatternStyle.ROUNDED -> {
                paint.isAntiAlias = true
                canvas.drawRoundRect(
                    left,
                    top,
                    left + size,
                    top + size,
                    moduleSize * 1.15f,
                    moduleSize * 1.15f,
                    paint
                )
                paint.isAntiAlias = false
            }
            QRFinderPatternStyle.CIRCLE -> {
                paint.isAntiAlias = true
                canvas.drawOval(left, top, left + size, top + size, paint)
                paint.isAntiAlias = false
            }
        }
    }

    private fun createGradient(style: QRColorStyle.Gradient, bounds: RectF): Shader =
        LinearGradient(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            style.startColor,
            style.endColor,
            Shader.TileMode.CLAMP
        )

    private fun validateContrast(style: QRColorStyle) {
        val colors = when (style) {
            is QRColorStyle.Solid -> listOf(style.foregroundColor)
            is QRColorStyle.Gradient -> listOf(style.startColor, style.endColor)
        }
        require(colors.all { contrastRatio(it, style.backgroundColor) >= 4.5 }) {
            "QR foreground and background contrast is too low"
        }
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double = (value / 255.0).let {
                if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(android.graphics.Color.red(color)) +
                0.7152 * channel(android.graphics.Color.green(color)) +
                0.0722 * channel(android.graphics.Color.blue(color))
        }
        val firstLum = luminance(first)
        val secondLum = luminance(second)
        return (maxOf(firstLum, secondLum) + 0.05) / (minOf(firstLum, secondLum) + 0.05)
    }
}

internal class FunctionModuleMask private constructor(
    private val size: Int,
    private val functions: BooleanArray,
    private val finders: BooleanArray
) {
    fun isFunction(x: Int, y: Int): Boolean = functions[y * size + x]

    fun isFinder(x: Int, y: Int): Boolean = finders[y * size + x]

    companion object {
        fun create(size: Int): FunctionModuleMask {
            require(size >= 21 && (size - 17) % 4 == 0) { "Invalid QR matrix size: $size" }
            val functions = BooleanArray(size * size)
            val finders = BooleanArray(size * size)

            fun mark(x: Int, y: Int, width: Int, height: Int, finder: Boolean = false) {
                for (row in y until y + height) for (column in x until x + width) {
                    if (row in 0 until size && column in 0 until size) {
                        functions[row * size + column] = true
                        if (finder) finders[row * size + column] = true
                    }
                }
            }

            mark(0, 0, 9, 9)
            mark(size - 8, 0, 8, 9)
            mark(0, size - 8, 9, 8)
            mark(0, 0, 7, 7, finder = true)
            mark(size - 7, 0, 7, 7, finder = true)
            mark(0, size - 7, 7, 7, finder = true)
            mark(6, 8, 1, size - 16)
            mark(8, 6, size - 16, 1)
            mark(8, 0, 1, 9)
            mark(0, 8, 9, 1)
            mark(size - 8, 8, 8, 1)
            mark(8, size - 7, 1, 7)

            val versionNumber = (size - 17) / 4
            val centers = Version.getVersionForNumber(versionNumber).alignmentPatternCenters
            centers.forEach { centerY ->
                centers.forEach { centerX ->
                    val overlapsFinder =
                        (centerX <= 8 && centerY <= 8) ||
                            (centerX >= size - 8 && centerY <= 8) ||
                            (centerX <= 8 && centerY >= size - 8)
                    if (!overlapsFinder) mark(centerX - 2, centerY - 2, 5, 5)
                }
            }
            if (versionNumber >= 7) {
                mark(size - 11, 0, 3, 6)
                mark(0, size - 11, 6, 3)
            }
            return FunctionModuleMask(size, functions, finders)
        }
    }
}
