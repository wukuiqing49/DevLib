package com.wkq.dev.document

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.wkq.roof.export.checkOfficeActive
import java.io.File

/** A4 记录 PDF 排版器；文字保持为文字，长段落和表格行可跨页，图片按解码预算缩放。 */
internal class RecordPdfWriter private constructor(private val template: RecordTemplate, watermark: File?) {
    private val mark = watermark?.let { BitmapFactory.decodeFile(it.path) }
    private val pageWidth = if (template.style.landscape) 842 else 595
    private val pageHeight = if (template.style.landscape) 595 else 842
    private val LEFT get() = template.style.marginPoints.toFloat()
    private val TOP get() = LEFT + if (template.style.header.isBlank()) 0f else 16f
    private val BOTTOM get() = pageHeight - LEFT - 16f
    private val WIDTH get() = pageWidth - LEFT * 2
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = TOP
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = template.style.bodySize.toFloat() }
    private val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }

    /** 结束旧页并开始下一页；页码在底边独立区域绘制。 */
    private fun newPage() {
        checkOfficeActive()
        finishPage()
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = TOP
    }

    /** 写页码后关闭当前页，确保页面生命周期与文档一致。 */
    private fun finishPage() {
        page?.let {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY; textAlign = Paint.Align.CENTER }
            if (mark != null && (template.watermark.everyPage || pageNumber == 1)) {
                it.canvas.drawBitmap(mark, null, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), null)
            }
            val footer = template.style.footer + if (template.style.showPageNumbers) "  $pageNumber" else ""
            val footerText = android.text.TextUtils.ellipsize(footer, TextPaint(paint), WIDTH, android.text.TextUtils.TruncateAt.END).toString()
            it.canvas.drawText(footerText, pageWidth / 2f, pageHeight - LEFT / 2f, paint)
            paint.textAlign = Paint.Align.LEFT
            val header = android.text.TextUtils.ellipsize(template.style.header, TextPaint(paint), WIDTH, android.text.TextUtils.TruncateAt.END).toString()
            it.canvas.drawText(header, LEFT, LEFT, paint)
            document.finishPage(it)
            page = null
        }
    }

    /** 按可用宽度排版 Unicode 文本，具体字体回退交给 Android 字体系统。 */
    private fun layout(text: String, width: Int, size: Float = template.style.bodySize.toFloat(), bold: Boolean = false): StaticLayout {
        val paint = TextPaint(textPaint).apply { textSize = size; if (bold) color = Color.parseColor("#${template.style.primaryColor}"); typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
    }

    /** 绘制布局的连续行片段，通过裁剪与平移避免丢失跨页文本。 */
    private fun drawSlice(layout: StaticLayout, start: Int, end: Int, left: Float, top: Float) {
        val canvas = checkNotNull(page).canvas
        val height = layout.getLineBottom(end - 1) - layout.getLineTop(start)
        canvas.save()
        try {
            canvas.clipRect(left, top, left + layout.width, top + height)
            canvas.translate(left, top - layout.getLineTop(start))
            layout.draw(canvas)
        } finally { canvas.restore() }
    }

    /** 将段落按实际行高分页，不以字符数量粗略截断。 */
    private fun paragraph(text: String, size: Float = template.style.bodySize.toFloat(), bold: Boolean = false) {
        if (text.isEmpty()) return
        val layout = layout(text, WIDTH.toInt(), size, bold)
        var start = 0
        while (start < layout.lineCount) {
            checkOfficeActive()
            if (page == null || BOTTOM - y < layout.getLineBottom(start) - layout.getLineTop(start)) newPage()
            var end = start + 1
            while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(start) <= BOTTOM - y) end++
            drawSlice(layout, start, end, LEFT, y)
            y += layout.getLineBottom(end - 1) - layout.getLineTop(start)
            start = end
        }
        y += 8f
    }

    /** 等宽表格逐行绘制，超长行切分到下一页，各分片仍绘制单元格边界。 */
    private fun table(rows: List<List<String>>, header: Boolean) {
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.size }
        val cellWidth = WIDTH / columns
        rows.forEachIndexed { rowIndex, row ->
            val layouts = List(columns) { layout(row.getOrElse(it) { "" }, (cellWidth - 10).toInt(), bold = header && rowIndex == 0) }
            val starts = IntArray(columns)
            while (layouts.indices.any { starts[it] < layouts[it].lineCount }) {
                checkOfficeActive()
                if (page == null || BOTTOM - y < textPaint.fontSpacing + 10f) newPage()
                val ends = layouts.indices.map { column ->
                    val l = layouts[column]
                    val start = starts[column]
                    var end = start
                    while (end < l.lineCount && l.getLineBottom(end) - l.getLineTop(start) <= BOTTOM - y - 10) end++
                    end
                }
                val height = layouts.indices.maxOf { c ->
                    if (ends[c] > starts[c]) layouts[c].getLineBottom(ends[c] - 1) - layouts[c].getLineTop(starts[c]) else 0
                } + 10f
                layouts.indices.forEach { c ->
                    val left = LEFT + c * cellWidth
                    if (header && rowIndex == 0) checkNotNull(page).canvas.drawRect(left, y, left + cellWidth, y + height,
                        Paint().apply { color = Color.parseColor("#${template.style.headerFill}") })
                    checkNotNull(page).canvas.drawRect(left, y, left + cellWidth, y + height, border)
                    if (ends[c] > starts[c]) drawSlice(layouts[c], starts[c], ends[c], left + 5, y + 5)
                    starts[c] = ends[c]
                }
                y += height
            }
        }
        y += 10f
    }

    /** 按最大约 1600 像素边长解码图片并适配页面；位图无论成功失败都释放。 */
    private fun image(image: RecordImage, small: Boolean = false) {
        checkOfficeActive()
        val bounds = imageBounds(image.file)
        var sample = 1
        while (maxOf(bounds.first, bounds.second) / sample > 1600) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(image.file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val scale = minOf((if (small) minOf(WIDTH, 180f) else WIDTH) / bitmap.width, (if (small) 120f else BOTTOM - TOP - 20f) / bitmap.height)
            val height = bitmap.height * scale
            if (page == null || y + height > BOTTOM) newPage()
            checkNotNull(page).canvas.drawBitmap(bitmap, null, RectF(LEFT, y, LEFT + bitmap.width * scale, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
            y += height + 8
        } finally { bitmap.recycle() }
        paragraph(image.caption)
    }

    /** 照片按模板列数排列，每组之后完整输出说明，不裁掉长说明文字。 */
    private fun gallery(images: List<RecordImage>) {
        val columns = template.imageColumns
        val width = (WIDTH - 8 * (columns - 1)) / columns
        images.chunked(columns).forEach { row ->
            val height = minOf(220f, BOTTOM - TOP - 20)
            if (page == null || y + height > BOTTOM) newPage()
            row.forEachIndexed { index, image ->
                checkOfficeActive()
                val bounds = imageBounds(image.file)
                var sample = 1
                while (maxOf(bounds.first, bounds.second) / sample > 1600) sample *= 2
                val bitmap = requireNotNull(BitmapFactory.decodeFile(image.file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                try {
                    val scale = minOf(width / bitmap.width, height / bitmap.height)
                    val left = LEFT + index * (width + 8)
                    checkNotNull(page).canvas.drawBitmap(bitmap, null, RectF(left, y, left + bitmap.width * scale, y + bitmap.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally { bitmap.recycle() }
            }
            y += height + 8
            row.forEach { paragraph(it.caption) }
        }
    }

    companion object {

        /** 读取图片边界并限制解码源像素，拒绝不可识别图片。 */
        internal fun imageBounds(file: File): Pair<Int, Int> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            val expectedMime = if (file.extension.lowercase(java.util.Locale.ROOT) == "png") "image/png" else "image/jpeg"
            require(options.outMimeType == expectedMime)
            require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000)
            return options.outWidth to options.outHeight
        }

        /** 将完整记录写入调用方隔离的临时文件，异常时关闭所有 PDF 页面和文档。 */
        fun write(file: File, record: ExportRecord, watermark: File? = null) {
            val writer = RecordPdfWriter(record.template, watermark)
            try {
                val sections = RecordLayout.sections(record)
                require(sections.isNotEmpty()) { "Template hides all available content" }
                sections.forEach { section ->
                    if (section.label.isNotBlank()) writer.paragraph(section.label, record.template.style.titleSize.toFloat() * 0.7f, true)
                    if (section.block == RecordBlock.IMAGES && record.template.imageColumns > 1) {
                        writer.gallery(section.content.filterIsInstance<RecordContent.Picture>().map { it.image })
                    } else section.content.forEach { item -> when (item) {
                        is RecordContent.Text -> writer.paragraph(item.value, if (item.title) record.template.style.titleSize.toFloat() else record.template.style.bodySize.toFloat(), item.title)
                        is RecordContent.Table -> writer.table(item.rows.map { row -> row.map { it.value } }, item.header)
                        is RecordContent.Picture -> writer.image(item.image, !item.gallery)
                    } }
                }
                writer.finishPage()
                checkOfficeActive()
                file.outputStream().buffered().use { writer.document.writeTo(it) }
                java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
            } finally {
                writer.finishPage()
                writer.document.close()
                writer.mark?.recycle()
            }
        }
    }
}
