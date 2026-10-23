package com.phototoolkit.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 仅供 debug/test 调用方使用的证件照诊断输出配置。
 * 正式产品默认不传入该配置，避免把用户原图写入持久化目录。
 */
data class IdPhotoDiagnosticsRequest(
    val directory: String,
    val sampleId: String = "sample",
    val source: String? = null,
    val regionCode: String? = null,
    val documentId: String? = null,
    val variantId: String? = null,
    val ruleProfileId: String? = null,
    val catalogVersion: Int? = null,
)

object IdPhotoDiagnosticsWriter {
    private const val MAX_PREVIEW_SIDE = 1200
    private const val SHEET_WIDTH = 1200
    private const val SHEET_HEIGHT = 800

    fun write(
        request: IdPhotoDiagnosticsRequest,
        normalized: File,
        output: File,
        mask: PortraitMatting.AlphaMask,
        face: PortraitFace,
        head: PortraitHeadGeometry,
        shoulders: PortraitShoulderGeometry?,
        spec: IdPhotoSpec,
        plan: IdPhotoCompositionPlan,
        compositionMetrics: DoubleArray,
        report: IdPhotoComplianceReport,
        outputFormat: IdPhotoOutputFormat,
        durationMs: Long,
    ) {
        require(request.directory.isNotBlank())
        val root = File(request.directory).apply { check(isDirectory || mkdirs()) }
        val normalizedBitmap = decodePreview(normalized) ?: error("Unable to decode normalized source")
        val outputBitmap = decodePreview(output) ?: error("Unable to decode ID photo output")
        val sourceDimensions = decodeDimensions(normalized)
        val geometry = drawGeometryOverlay(
            normalizedBitmap,
            sourceDimensions.first,
            sourceDimensions.second,
            face,
            head,
            shoulders,
        )
        val maskBitmap = drawMask(mask)
        val cropPlan = drawCropPlan(
            normalizedBitmap,
            sourceDimensions.first,
            sourceDimensions.second,
            face,
            head,
            spec,
            compositionMetrics,
        )
        val outputGuides = drawOutputGuides(outputBitmap, report)
        val comparison = createComparisonSheet(
            normalizedBitmap,
            geometry,
            maskBitmap,
            cropPlan,
            outputBitmap,
            outputGuides,
        )
        try {
            saveJpeg(normalizedBitmap, File(root, "01_source_normalized.jpg"))
            saveJpeg(geometry, File(root, "02_geometry_overlay.jpg"))
            savePng(maskBitmap, File(root, "03_alpha_mask.png"))
            saveJpeg(cropPlan, File(root, "04_crop_plan.jpg"))
            output.copyTo(File(root, "05_output.${outputFormat.fileExtension}"), overwrite = true)
            saveJpeg(comparison, File(root, "06_comparison.jpg"))
            writeReport(
                root = root,
                request = request,
                mask = mask,
                face = face,
                head = head,
                shoulders = shoulders,
                spec = spec,
                plan = plan,
                compositionMetrics = compositionMetrics,
                report = report,
                output = output,
                outputFormat = outputFormat,
                durationMs = durationMs,
                sourceWidth = sourceDimensions.first,
                sourceHeight = sourceDimensions.second,
            )
            File(root, "run.log").writeText(
                "sampleId=${request.sampleId}\n" +
                    "decision=${report.decision}\n" +
                    "issues=${report.issues.joinToString { it.code.name }}\n",
                Charsets.UTF_8,
            )
        } finally {
            normalizedBitmap.recycle()
            outputBitmap.recycle()
            geometry.recycle()
            maskBitmap.recycle()
            cropPlan.recycle()
            outputGuides.recycle()
            comparison.recycle()
        }
    }

    private fun writeReport(
        root: File,
        request: IdPhotoDiagnosticsRequest,
        mask: PortraitMatting.AlphaMask,
        face: PortraitFace,
        head: PortraitHeadGeometry,
        shoulders: PortraitShoulderGeometry?,
        spec: IdPhotoSpec,
        plan: IdPhotoCompositionPlan,
        compositionMetrics: DoubleArray,
        report: IdPhotoComplianceReport,
        output: File,
        outputFormat: IdPhotoOutputFormat,
        durationMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val bounds = maskBounds(mask)
        val mappedBounds = Bounds(
            left = (bounds.left * sourceWidth.toDouble() / mask.width).toInt(),
            top = (bounds.top * sourceHeight.toDouble() / mask.height).toInt(),
            right = (bounds.right * sourceWidth.toDouble() / mask.width).toInt(),
            bottom = (bounds.bottom * sourceHeight.toDouble() / mask.height).toInt(),
        )
        val scale = compositionMetrics.getOrNull(0)?.takeIf(Double::isFinite)
            ?.times(spec.heightPx)?.div(head.height)
        val translationX = scale?.let {
            spec.widthPx * spec.faceCenterXRatio - (head.left + head.width * 0.5) * it
        }
        val translationY = scale?.let {
            compositionMetrics.getOrNull(1)?.takeIf(Double::isFinite)?.times(spec.heightPx)
                ?.minus(head.top * it)
        }
        val bottomGap = scale?.let {
            (spec.heightPx - (translationY ?: 0.0) - mappedBounds.bottom * it).toInt()
        }
        val json = JSONObject()
            .put("sampleId", request.sampleId)
            .putNullable("source", request.source)
            .putNullable("regionCode", request.regionCode)
            .putNullable("documentId", request.documentId)
            .putNullable("variantId", request.variantId)
            .putNullable("ruleProfileId", request.ruleProfileId)
            .putNullable("catalogVersion", request.catalogVersion)
            .put("outputWidth", spec.widthPx)
            .put("outputHeight", spec.heightPx)
            .put("outputFormat", outputFormat.name)
            .put("headHeightRatio", report.metrics.faceHeightRatio)
            .put("headTopRatio", report.metrics.faceTopRatio)
            .putNullable("eyeLineRatio", report.metrics.eyeLineRatio)
            .put("faceCenterXRatio", report.metrics.faceCenterXRatio)
            .putNullable("shoulderLineRatio", report.metrics.shoulderLineRatio)
            .put("portraitBounds", JSONObject()
                .put("left", mappedBounds.left)
                .put("top", mappedBounds.top)
                .put("right", mappedBounds.right)
                .put("bottom", mappedBounds.bottom))
            .putNullable("scale", scale)
            .putNullable("translationX", translationX)
            .putNullable("translationY", translationY)
            .putNullable("bottomGapPx", bottomGap)
            .put("decision", report.decision.name)
            .put("issues", report.issues.map { "${it.code.name}:${it.severity.name}" })
            .put("durationMs", durationMs)
            .put("face", JSONObject()
                .put("left", face.left)
                .put("top", face.top)
                .put("width", face.width)
                .put("height", face.height))
            .putNullable("shoulders", shoulders?.let {
                JSONObject()
                    .put("leftX", it.leftX)
                    .put("leftY", it.leftY)
                    .put("rightX", it.rightX)
                    .put("rightY", it.rightY)
                    .put("confidence", it.confidence)
            })
            .put("requestedPlan", JSONObject()
                .put("faceHeightRatio", plan.faceHeightRatio)
                .put("faceTopRatio", plan.faceTopRatio)
                .putNullable("eyeLineRatio", plan.predictedEyeLineRatio)
                .put("faceCenterXRatio", plan.faceCenterXRatio)
                .putNullable("shoulderLineRatio", plan.predictedShoulderLineRatio))
            .put("fileBytes", output.length())
        File(root, "report.json").writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun drawGeometryOverlay(
        source: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        face: PortraitFace,
        head: PortraitHeadGeometry,
        shoulders: PortraitShoulderGeometry?,
    ): Bitmap = source.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
        val scaleX = bitmap.width / sourceWidth.toDouble()
        val scaleY = bitmap.height / sourceHeight.toDouble()
        fun x(value: Double) = (value * scaleX).toFloat()
        fun y(value: Double) = (value * scaleY).toFloat()
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        paint.color = Color.RED
        canvas.drawRect(
            x(face.left.toDouble()),
            y(face.top.toDouble()),
            x((face.left + face.width).toDouble()),
            y((face.top + face.height).toDouble()),
            paint,
        )
        paint.color = Color.GREEN
        val headLeft: Double = head.left.toString().toDouble()
        val headTop: Double = head.top.toString().toDouble()
        val headRight: Double = head.left.toString().toDouble() + head.width.toString().toDouble()
        val headBottom: Double = head.top.toString().toDouble() + head.height.toString().toDouble()
        canvas.drawRect(
            (headLeft * scaleX).toFloat(),
            (headTop * scaleY).toFloat(),
            (headRight * scaleX).toFloat(),
            (headBottom * scaleY).toFloat(),
            paint,
        )
        paint.color = Color.YELLOW
        face.leftEyeX?.let { left -> face.leftEyeY?.let { top -> canvas.drawCircle(x(left.toDouble()), y(top.toDouble()), 7f, paint) } }
        face.rightEyeX?.let { right -> face.rightEyeY?.let { top -> canvas.drawCircle(x(right.toDouble()), y(top.toDouble()), 7f, paint) } }
        shoulders?.let {
            paint.color = Color.CYAN
            canvas.drawLine(x(it.leftX), y(it.leftY), x(it.rightX), y(it.rightY), paint)
        }
    }

    private fun drawCropPlan(
        source: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        face: PortraitFace,
        head: PortraitHeadGeometry,
        spec: IdPhotoSpec,
        metrics: DoubleArray,
    ): Bitmap = source.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
        val scale = metrics.getOrNull(0)?.takeIf(Double::isFinite)?.times(spec.heightPx)
            ?.div(head.height)?.coerceAtLeast(0.01) ?: 1.0
        val top = metrics.getOrNull(1)?.takeIf(Double::isFinite)?.times(spec.heightPx)
            ?.minus(head.top * scale) ?: 0.0
        val left = spec.widthPx * spec.faceCenterXRatio - (head.left + head.width * 0.5) * scale
        val right = left + spec.widthPx / max(0.01, scale)
        val bottom = top + spec.heightPx / max(0.01, scale)
        val sourceScaleX = bitmap.width / max(1, sourceWidth).toDouble()
        val sourceScaleY = bitmap.height / max(1, sourceHeight).toDouble()
        val rect = RectF(
            (left / scale * sourceScaleX).toFloat(),
            (top / scale * sourceScaleY).toFloat(),
            (right / scale * sourceScaleX).toFloat(),
            (bottom / scale * sourceScaleY).toFloat(),
        )
        Canvas(bitmap).drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            style = Paint.Style.STROKE
            strokeWidth = 4f
        })
        face.chinY?.let { chin ->
            Canvas(bitmap).drawLine(
                0f,
                (chin * sourceScaleY).toFloat(),
                bitmap.width.toFloat(),
                (chin * sourceScaleY).toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = 2f },
            )
        }
    }

    private fun drawMask(mask: PortraitMatting.AlphaMask): Bitmap {
        val bitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(mask.bytes.size) { index ->
            val alpha = mask.bytes[index].toInt() and 0xFF
            Color.argb(alpha, 255, 255, 255)
        }
        bitmap.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        return bitmap
    }

    private fun drawOutputGuides(
        output: Bitmap,
        report: IdPhotoComplianceReport,
    ): Bitmap = output.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.YELLOW
        }
        val top = (report.metrics.faceTopRatio * bitmap.height).toFloat()
        val bottom = ((report.metrics.faceTopRatio + report.metrics.faceHeightRatio) * bitmap.height).toFloat()
        canvas.drawLine(0f, top, bitmap.width.toFloat(), top, paint)
        canvas.drawLine(0f, bottom, bitmap.width.toFloat(), bottom, paint)
        val center = (report.metrics.faceCenterXRatio * bitmap.width).toFloat()
        canvas.drawLine(center, 0f, center, bitmap.height.toFloat(), paint)
        report.metrics.eyeLineRatio?.let { eye ->
            canvas.drawLine(0f, (eye * bitmap.height).toFloat(), bitmap.width.toFloat(), (eye * bitmap.height).toFloat(), paint)
        }
        report.metrics.shoulderLineRatio?.let { shoulder ->
            paint.color = Color.CYAN
            canvas.drawLine(0f, (shoulder * bitmap.height).toFloat(), bitmap.width.toFloat(), (shoulder * bitmap.height).toFloat(), paint)
        }
        paint.color = Color.MAGENTA
        canvas.drawRect(
            0f,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            paint,
        )
    }

    private fun createComparisonSheet(vararg values: Bitmap): Bitmap {
        return Bitmap.createBitmap(SHEET_WIDTH, SHEET_HEIGHT, Bitmap.Config.ARGB_8888).also { sheet ->
            val canvas = Canvas(sheet).apply { drawColor(Color.DKGRAY) }
            val cellWidth = SHEET_WIDTH / 3f
            val cellHeight = SHEET_HEIGHT / 2f
            values.forEachIndexed { index, bitmap ->
                val left = (index % 3) * cellWidth
                val top = (index / 3) * cellHeight
                drawFit(canvas, bitmap, RectF(left, top, left + cellWidth, top + cellHeight))
                canvas.drawText(
                    "${index + 1}",
                    left + 12f,
                    top + 28f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f },
                )
            }
        }
    }

    private fun drawFit(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = target.left + (target.width() - width) * 0.5f
        val top = target.top + (target.height() - height) * 0.5f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun decodePreview(file: File): Bitmap? {
        val bounds = decodeDimensions(file)
        var sample = 1
        while (max(bounds.first, bounds.second) / sample > MAX_PREVIEW_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun decodeDimensions(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read image dimensions" }
        return bounds.outWidth to bounds.outHeight
    }

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun maskBounds(mask: PortraitMatting.AlphaMask): Bounds {
        var left = mask.width
        var top = mask.height
        var right = 0
        var bottom = 0
        mask.bytes.forEachIndexed { index, value ->
            if ((value.toInt() and 0xFF) <= 24) return@forEachIndexed
            val x = index % mask.width
            val y = index / mask.width
            left = min(left, x)
            top = min(top, y)
            right = max(right, x + 1)
            bottom = max(bottom, y + 1)
        }
        return if (right > left && bottom > top) Bounds(left, top, right, bottom)
        else Bounds(0, 0, 0, 0)
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

}
