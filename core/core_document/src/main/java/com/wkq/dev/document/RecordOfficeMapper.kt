package com.wkq.dev.document

import com.wkq.roof.export.excel.SimpleXlsxWriter as Xlsx
import com.wkq.roof.export.word.SimpleDocxWriter as Docx
import com.wkq.roof.export.word.WordReportTheme
import com.wkq.roof.export.word.WordPageOrientation
import java.io.File

/** 将模板语义映射到 Office，资料和签署区与正文统一排序。 */
internal object RecordOfficeMapper {
    /** 生成可编辑 Word，应用主题、纸张方向、照片网格、页眉页脚与水印。 */
    fun word(record: ExportRecord, watermark: File? = null): Docx.Document {
        val t = record.template; val s = t.style
        val blocks = buildList<Docx.Block> {
            RecordLayout.sections(record).forEach { section ->
                if (section.label.isNotBlank()) add(Docx.Paragraph(section.label, Docx.ParagraphStyle.HEADING_1))
                val photos = section.content.filterIsInstance<RecordContent.Picture>()
                if (section.block == RecordBlock.IMAGES && t.imageColumns > 1) {
                    add(Docx.ImageGrid(photos.map { Docx.ImageGrid.Item(wordImage(it.image, 520 / t.imageColumns), it.image.caption) }, t.imageColumns))
                } else section.content.forEach { item -> when (item) {
                    is RecordContent.Text -> add(Docx.Paragraph(item.value, if (item.title) Docx.ParagraphStyle.TITLE else Docx.ParagraphStyle.NORMAL))
                    is RecordContent.Table -> add(Docx.Table(item.rows.map { row -> row.map { it.value } }, headerRows = if (item.header) 1 else 0))
                    is RecordContent.Picture -> {
                        add(wordImage(item.image, if (section.block in listOf(RecordBlock.COMPANY, RecordBlock.PERSON, RecordBlock.SIGNATURES)) 180 else 520))
                        if (item.image.caption.isNotBlank()) add(Docx.Paragraph(item.image.caption, Docx.ParagraphStyle.CAPTION))
                    }
                } }
            }
        }
        require(blocks.isNotEmpty()) { "Template hides all available content" }
        return Docx.Document(blocks,
            theme = WordReportTheme(primaryColor = s.primaryColor, tableHeaderFill = s.headerFill, bodySize = s.bodySize, titleSize = s.titleSize),
            orientation = if (s.landscape) WordPageOrientation.LANDSCAPE else WordPageOrientation.PORTRAIT,
            marginTwips = s.marginPoints * 20, headerText = s.header, footerText = s.footer, showPageNumber = s.showPageNumbers,
            watermarkRepeatOnEveryPage = t.watermark.everyPage,
            watermarkImage = watermark?.let { Docx.Image(it, if (s.landscape) 1123 else 793, if (s.landscape) 793 else 1123, "Watermark") })
    }

    /** 按真实比例缩放 Word 图片，避免 Logo 和签名拉伸。 */
    private fun wordImage(image: RecordImage, width: Int): Docx.Image {
        val bounds = RecordPdfWriter.imageBounds(image.file)
        val scale = minOf(width.toDouble() / bounds.first, 650.0 / bounds.second, 1.0)
        return Docx.Image(image.file, (bounds.first * scale).toInt().coerceAtLeast(1), (bounds.second * scale).toInt().coerceAtLeast(1), image.caption)
    }

    /** 已启用区块依次生成工作表，显式数值保留；可打印区域内安排分页与水印图层。 */
    fun excel(record: ExportRecord, watermark: File? = null): List<Xlsx.Sheet> {
        val t = record.template; val s = t.style
        return RecordLayout.sections(record).mapIndexed { sectionIndex, section ->
            val rows = mutableListOf<List<Xlsx.Cell>>()
            val images = mutableListOf<Xlsx.Image>(); val heights = mutableMapOf<Int, Int>()
            if (section.label.isNotBlank()) rows += listOf(Xlsx.Cell(section.label, Xlsx.CellStyle.TITLE))
            section.content.forEach { item -> when (item) {
                is RecordContent.Text -> textChunks(item.value).forEach { rows += listOf(Xlsx.Cell(it, if (item.title) Xlsx.CellStyle.TITLE else Xlsx.CellStyle.WRAPPED)) }
                is RecordContent.Table -> rows += item.rows.mapIndexed { index, row -> row.map {
                    Xlsx.Cell(it.value, if (item.header && index == 0) Xlsx.CellStyle.HEADER else Xlsx.CellStyle.WRAPPED,
                        type = if (it.numeric) Xlsx.CellType.NUMBER else Xlsx.CellType.TEXT)
                } }
                is RecordContent.Picture -> {
                    rows += listOf(Xlsx.Cell(item.image.caption))
                    val bounds = RecordPdfWriter.imageBounds(item.image.file)
                    val scale = minOf(400.0 / bounds.first, 240.0 / bounds.second)
                    images += Xlsx.Image(rows.size, 0, item.image.file, (bounds.first * scale).toInt().coerceAtLeast(1), (bounds.second * scale).toInt().coerceAtLeast(1))
                    heights[rows.size] = 190; rows += listOf(Xlsx.Cell())
                }
            } }
            val breaks = mutableListOf<Int>(); val pageStarts = mutableListOf(0)
            val capacity = (if (s.landscape) 595 else 842) - 2 * s.marginPoints - 50
            var used = 0
            rows.indices.forEach { index ->
                val estimated = heights[index] ?: ((rows[index].maxOfOrNull { it.value.length } ?: 0) / 28 + 1).coerceAtMost(18) * (s.bodySize + 5)
                heights[index] = estimated
                if (used > 0 && used + estimated > capacity) { breaks += index; pageStarts += index; used = 0 }
                used += estimated
            }
            val marks = if (watermark != null && (t.watermark.everyPage || sectionIndex == 0))
                (if (t.watermark.everyPage) pageStarts else pageStarts.take(1)).map {
                    Xlsx.Image(it, 0, watermark, if (s.landscape) 1000 else 700, if (s.landscape) 700 else 1000)
                } else emptyList()
            val name = if (section.block == RecordBlock.TITLE) "Record" else section.block.name.lowercase().replaceFirstChar { it.uppercase() }
            Xlsx.Sheet(name, rows, columnWidths = List(rows.maxOfOrNull { it.size } ?: 1) { 32 },
                freezeRows = if (section.block == RecordBlock.DETAILS) (if (section.label.isNotBlank()) 2 else 1) else 0,
                autoFilter = section.block == RecordBlock.DETAILS && section.label.isBlank(), rowHeights = heights,
                images = images, watermarkImages = marks, pageBreakRows = breaks,
                landscape = s.landscape, marginPoints = s.marginPoints, headerText = s.header, footerText = s.footer, showPageNumbers = s.showPageNumbers)
        }.also { require(it.isNotEmpty()) { "Template hides all available content" } }
    }
    /** 长备注拆为多行单元格但不拆开 UTF-16 代理对，保留全部原始字符。 */
    private fun textChunks(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + 120, value.length)
            if (end < value.length && value[end - 1].isHighSurrogate()) end--
            result += value.substring(start, end)
            start = end
        }
        return result.ifEmpty { listOf("") }
    }

}
