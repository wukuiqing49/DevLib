package com.wkq.roof.export.excel

import com.wkq.roof.export.checkOfficeActive
import com.wkq.roof.export.commitOfficeOutput
import java.io.File
import com.wkq.roof.export.validXmlText
import java.io.RandomAccessFile
import java.net.URI
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 轻量 Excel OpenXML 写入器，支持表格、图片、图表和打印布局。 */
object SimpleXlsxWriter {
    /** 工作簿主题，字号与表头颜色可由统一记录模板配置。 */
    data class WorkbookTheme(
        val primaryColor: String = "1F6FEB",
        val accentColor: String = "14B8A6",
        val compactTables: Boolean = false,
        val currencyCode: String = "USD",
        val bodySize: Int = 11,
        val titleSize: Int = 20,
        val headerFill: String = primaryColor,
        val headerTextColor: String = "FFFFFF"
    ) {
        init {
            require(bodySize in 8..18 && titleSize in 14..32)
            require(headerFill.matches(Regex("[0-9A-Fa-f]{6}")) && headerTextColor.matches(Regex("[0-9A-Fa-f]{6}")))
            require(primaryColor.matches(Regex("[0-9A-Fa-f]{6}")))
            require(accentColor.matches(Regex("[0-9A-Fa-f]{6}")))
            require(currencyCode.matches(Regex("[A-Z]{3}")))
            java.util.Currency.getInstance(currencyCode)
        }
    }
    enum class CellStyle(val index: Int) {
        NORMAL(0), TITLE(1), HEADER(2), PASS(3), FAIL(4), WARNING(5), MUTED(6), WRAPPED(7), KEY(8), LINK(9), MONEY(10), DECIMAL(11), TOTAL(12)
    }

    data class Cell(
        val value: String = "",
        val style: CellStyle = CellStyle.NORMAL,
        val hyperlink: String? = null,
        val type: CellType = CellType.TEXT
    ) {
        companion object {
            fun number(value: Number, style: CellStyle = CellStyle.NORMAL): Cell =
                Cell(value.toString(), style = style, type = CellType.NUMBER)
        }
    }

    enum class CellType { TEXT, NUMBER }

    data class Chart(
        val row: Int,
        val column: Int,
        val title: String,
        val categories: List<String>,
        val values: List<Int>,
        val widthPx: Int = 520,
        val heightPx: Int = 280
    )

    data class Image(
        val row: Int,
        val column: Int,
        val file: File,
        val widthPx: Int = 120,
        val heightPx: Int = 90
    )

    /** 工作表内容与打印配置，旧调用保持默认页面行为。 */
    data class Sheet(
        val name: String,
        val rows: List<List<Cell>>,
        val columnWidths: List<Int> = emptyList(),
        val freezeRows: Int = 1,
        val autoFilter: Boolean = true,
        val rowHeights: Map<Int, Int> = emptyMap(),
        val images: List<Image> = emptyList(),
        val charts: List<Chart> = emptyList(),
        val rightToLeft: Boolean = false,
        /** 以最后一个绘图层写入，覆盖普通图片、图表和单元格内容。 */
        val watermarkImages: List<Image> = emptyList(),
        /** 行数为分页前已完成的行数，用于避免照片和说明被自动分页拆开。 */
        val pageBreakRows: List<Int> = emptyList(),
        val mergedRows: Set<Int> = emptySet(),
        val hidden: Boolean = false,
        val landscape: Boolean? = null,
        val marginPoints: Int = 36,
        val headerText: String = "",
        val footerText: String = "",
        val showPageNumbers: Boolean = false
    )

    /** 写入隔离临时文件，校验后原子替换；失败或中断不删除旧文档。 */
    fun write(
        output: File,
        sheets: List<Sheet>,
        theme: WorkbookTheme = WorkbookTheme()
    ): File {
        require(sheets.isNotEmpty())
        validate(sheets)
        output.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val media = sheets.flatMap { sheet -> sheet.images + sheet.watermarkImages }.map(Image::file)
        val mediaNames = media.mapIndexed { index, file -> "image${index + 1}.${file.mediaExtension()}" }
        val charts = sheets.flatMapIndexed { sheetIndex, sheet -> sheet.charts.map { sheetIndex to it } }
        val temporary = File.createTempFile("xlsx_", ".tmp", output.absoluteFile.parentFile)
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.text("[Content_Types].xml", contentTypes(sheets, media, charts))
                zip.text("_rels/.rels", rootRelationships())
                zip.text("docProps/app.xml", appProperties(sheets))
                zip.text("docProps/core.xml", coreProperties())
                zip.text("xl/workbook.xml", workbook(sheets))
                zip.text("xl/_rels/workbook.xml.rels", workbookRelationships(sheets))
                zip.text("xl/styles.xml", styles(theme))
                sheets.forEachIndexed { index, sheet ->
                    val number = index + 1
                    zip.text("xl/worksheets/sheet$number.xml", sheetXml(sheet))
                    val hyperlinks = sheet.rows.flatMap { row -> row.mapNotNull(Cell::hyperlink) }
                    if (hyperlinks.any { !it.startsWith("#") } || sheet.hasDrawing()) {
                        zip.text(
                            "xl/worksheets/_rels/sheet$number.xml.rels",
                            sheetRelationships(
                                sheetNumber = number,
                                sheet = sheet,
                                hyperlinks = hyperlinks
                            )
                        )
                    }
                    if (sheet.hasDrawing()) {
                        zip.text("xl/drawings/drawing$number.xml", drawing(sheet.images, sheet.charts, sheet.watermarkImages))
                        val mediaOffset = sheets.take(index).sumOf { previousSheet -> previousSheet.mediaCount() }
                        val chartOffset = sheets.take(index).sumOf { it.charts.size }
                        zip.text(
                            "xl/drawings/_rels/drawing$number.xml.rels",
                            drawingRelationships(
                                images = sheet.images,
                                charts = sheet.charts,
                                watermarkImages = sheet.watermarkImages,
                                mediaOffset = mediaOffset,
                                chartOffset = chartOffset,
                                mediaNames = mediaNames
                            )
                        )
                    }
                }
                media.forEachIndexed { index, image ->
                    zip.file("xl/media/${mediaNames[index]}", image)
                }
                var chartIndex = 1
                charts.forEach { (_, chart) -> zip.text("xl/charts/chart${chartIndex++}.xml", chartXml(chart)) }
            }
            check(temporary.isFile && temporary.length() > 0L)
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            // 同目录替换失败时保留旧报告，不能先删除正式文件。
            commitOfficeOutput(temporary, output)
        } finally {
            temporary.delete()
        }
        check(output.isFile && output.length() > 0L)
        return output
    }

    /** 校验工作表和单元格容量，拒绝导出无法完整表达的数据。 */
    private fun validate(sheets: List<Sheet>) {
        require(sheets.any { !it.hidden }) { "Workbook needs a visible sheet" }
        val normalizedNames = mutableSetOf<String>()
        sheets.forEach { sheet ->
            checkOfficeActive()
            require(sheet.marginPoints in 18..72 && sheet.headerText.length <= 200 && sheet.footerText.length <= 200)
            require(sheet.rows.size <= 1_048_576) { "Too many Excel rows" }
            require(sheet.rows.all { it.size <= 16_384 }) { "Too many Excel columns" }
            require(sheet.rows.all { row -> row.all { it.value.length <= 32_767 } }) { "Excel cell text is too long" }
            require(sheet.name.validXmlText() == sheet.name) { "Invalid XML character in sheet name" }
            require(sheet.mergedRows.all { it in sheet.rows.indices && sheet.rows[it].drop(1).all { cell -> cell.value.isEmpty() } }) {
                "Merged row must not discard cell values"
            }
            require(sheet.pageBreakRows.distinct().size == sheet.pageBreakRows.size &&
                sheet.pageBreakRows.all { it in 1 until sheet.rows.size }) { "Invalid manual page break" }
            sheet.rows.forEach { row ->
                row.filter { it.type == CellType.NUMBER }.forEach { cell ->
                    require(cell.value.toDoubleOrNull()?.isFinite() == true) { "Invalid Excel numeric cell" }
                }
            }
            require(sheet.name.isNotBlank() && sheet.name.length <= 31) { "Excel sheet name must contain 1..31 characters" }
            require(sheet.name.none { it in "\\/*?:[]" } && !sheet.name.startsWith("'") && !sheet.name.endsWith("'")) {
                "Excel sheet name contains an unsupported character: ${sheet.name}"
            }
            require(normalizedNames.add(sheet.name.lowercase(Locale.ROOT))) { "Duplicate Excel sheet name: ${sheet.name}" }
            sheet.images.forEach { image ->
                require(image.row >= 0 && image.column >= 0 && image.widthPx > 0 && image.heightPx > 0)
                require(image.file.isFile) { "Excel image does not exist: ${image.file}" }
            }
            sheet.watermarkImages.forEach { image ->
                require(image.row >= 0 && image.column >= 0 && image.widthPx > 0 && image.heightPx > 0)
                require(image.file.isFile) { "Excel watermark image does not exist: ${image.file}" }
            }
            sheet.charts.forEach { chart ->
                require(chart.row >= 0 && chart.column >= 0 && chart.widthPx > 0 && chart.heightPx > 0)
                require(chart.categories.isNotEmpty() && chart.categories.size == chart.values.size) {
                    "Excel chart categories and values must be non-empty and have the same size"
                }
            }
        }
    }

    private fun contentTypes(sheets: List<Sheet>, media: List<File>, charts: List<Pair<Int, Chart>>): String = buildString {
        append(XML_HEADER)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        media.map { file -> file.mediaExtension() }.distinct().forEach { extension ->
            val contentType = if (extension == "png") "image/png" else "image/jpeg"
            append("<Default Extension=\"").append(extension).append("\" ContentType=\"").append(contentType).append("\"/>")
        }
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        sheets.indices.forEach { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            if (sheets[index].hasDrawing()) {
                append("<Override PartName=\"/xl/drawings/drawing${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
            }
        }
        charts.indices.forEach { index -> append("<Override PartName=\"/xl/charts/chart${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>") }
        append("</Types>")
    }

    private fun rootRelationships(): String = XML_HEADER +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>""".trimIndent()

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append(XML_HEADER)
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView activeTab=\"0\" firstSheet=\"0\"/></bookViews><sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"").append(sheet.name.xml()).append("\" sheetId=\"").append(index + 1)
                .append("\" r:id=\"rId").append(index + 1).append("\"")
            if (sheet.hidden) append(" state=\"hidden\"")
            append("/>")
        }
        append("</sheets><definedNames>")
        sheets.forEachIndexed { index, sheet ->
            // 隐藏明细保留数据，但不声明打印区域，避免阅读器打印重复明细。
            if (sheet.hidden) return@forEachIndexed
            val lastColumn = columnName((sheet.rows.maxOfOrNull { it.size } ?: 1) - 1)
            // 保留短工作表水印的可见区域，但不让 drawing 自行扩大到额外打印页。
            val watermarkRows = sheet.watermarkImages.maxOfOrNull { image ->
                var row = image.row
                var remainingPoints = image.heightPx * 0.75
                while (remainingPoints > 0) {
                    remainingPoints -= rowHeight(sheet, row)
                    row++
                }
                row
            } ?: 1
            val printRows = maxOf(sheet.rows.size, watermarkRows)
            append("<definedName name=\"_xlnm.Print_Area\" localSheetId=\"").append(index).append("\">")
                .append(("'${sheet.name.replace("'", "''")}'!\$A\$1:\$$lastColumn\$$printRows").xml())
                .append("</definedName>")
            if (sheet.freezeRows > 0) {
                append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(index).append("\">")
                    .append(("'${sheet.name.replace("'", "''")}'!\$1:\$${sheet.freezeRows}").xml())
                    .append("</definedName>")
            }
        }
        append("</definedNames></workbook>")
    }

    private fun workbookRelationships(sheets: List<Sheet>): String = buildString {
        append(XML_HEADER).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.indices.forEach { index ->
            append("<Relationship Id=\"rId").append(index + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(index + 1).append(".xml\"/>")
        }
        append("<Relationship Id=\"rId").append(sheets.size + 1)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    /** 将单元格、打印方向、页边距与页眉页脚写入工作表。 */
    private fun sheetXml(sheet: Sheet): String = buildString {
        val maxColumns = sheet.rows.maxOfOrNull(List<Cell>::size)?.coerceAtLeast(1) ?: 1
        val lastCell = columnName(maxColumns - 1) + sheet.rows.size.coerceAtLeast(1)
        val hyperlinks = mutableListOf<Pair<String, String>>()
        append(XML_HEADER)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        append("<dimension ref=\"A1:").append(lastCell).append("\"/>")
        if (sheet.freezeRows > 0) {
            append("<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"")
            if (sheet.rightToLeft) append(" rightToLeft=\"1\"")
            append("><pane ySplit=\"").append(sheet.freezeRows)
                .append("\" topLeftCell=\"A").append(sheet.freezeRows + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        } else {
            append("<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"")
            if (sheet.rightToLeft) append(" rightToLeft=\"1\"")
            append("/></sheetViews>")
        }
        append("<sheetFormatPr defaultRowHeight=\"18\"/>")
        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { index, width ->
                append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1)
                    .append("\" width=\"").append(width.coerceIn(4, 80)).append("\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val excelRow = rowIndex + 1
            val rowHeight = rowHeight(sheet, rowIndex)
            append("<row r=\"").append(excelRow).append("\"")
            append(" ht=\"").append(rowHeight.coerceIn(18, 409)).append("\" customHeight=\"1\"")
            append(">")
            row.forEachIndexed { columnIndex, cell ->
                val ref = columnName(columnIndex) + excelRow
                append("<c r=\"").append(ref).append("\" s=\"").append(cell.style.index).append("\"")
                if (cell.type == CellType.NUMBER) {
                    append("><v>").append(cell.value).append("</v></c>")
                } else {
                    append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(cell.value.xml()).append("</t></is></c>")
                }
                cell.hyperlink?.let { hyperlinks += ref to it }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.autoFilter && sheet.rows.isNotEmpty()) {
            append("<autoFilter ref=\"A1:").append(columnName(maxColumns - 1)).append(sheet.rows.size).append("\"/>")
        }
        if (sheet.mergedRows.isNotEmpty() && maxColumns > 1) {
            append("<mergeCells count=\"").append(sheet.mergedRows.size).append("\">")
            sheet.mergedRows.sorted().forEach { row ->
                append("<mergeCell ref=\"A").append(row + 1).append(':')
                    .append(columnName(maxColumns - 1)).append(row + 1).append("\"/>")
            }
            append("</mergeCells>")
        }
        if (hyperlinks.isNotEmpty()) {
            append("<hyperlinks>")
            var externalIndex = 0
            hyperlinks.forEach { (ref, target) ->
                if (target.startsWith("#")) {
                    append("<hyperlink ref=\"").append(ref).append("\" location=\"").append(target.removePrefix("#").xml()).append("\"/>")
                } else {
                    externalIndex++
                    append("<hyperlink ref=\"").append(ref).append("\" r:id=\"rId").append(externalIndex).append("\"/>")
                }
            }
            append("</hyperlinks>")
        }
        append("<printOptions horizontalCentered=\"1\"/>")
        val pageMargin = sheet.marginPoints / 72.0
        append("<pageMargins left=\"$pageMargin\" right=\"$pageMargin\" top=\"$pageMargin\" bottom=\"$pageMargin\" header=\"0.2\" footer=\"0.2\"/>")
        append("<pageSetup paperSize=\"9\" orientation=\"").append(if (sheet.landscape ?: (maxColumns > 4)) "landscape" else "portrait")
            .append("\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        if (sheet.headerText.isNotBlank() || sheet.footerText.isNotBlank() || sheet.showPageNumbers) {
            append("<headerFooter><oddHeader>&amp;C").append(sheet.headerText.replace("&", "&&").xml())
                .append("</oddHeader><oddFooter>&amp;C").append(sheet.footerText.replace("&", "&&").xml())
                .append(if (sheet.showPageNumbers) " &amp;P" else "").append("</oddFooter></headerFooter>")
        }
        val pageBreakRows = sheet.pageBreakRows.ifEmpty { headerPageBreakRows(sheet) }
        if (pageBreakRows.isNotEmpty()) {
            append("<rowBreaks count=\"").append(pageBreakRows.size)
                .append("\" manualBreakCount=\"").append(pageBreakRows.size).append("\">")
            pageBreakRows.sorted().forEach { row ->
                append("<brk id=\"").append(row).append("\" min=\"0\" max=\"16383\" man=\"1\"/>")
            }
            append("</rowBreaks>")
        }
        if (sheet.hasDrawing()) {
            append("<drawing r:id=\"rId").append(hyperlinks.count { !it.second.startsWith("#") } + 1).append("\"/>")
        }
        append("</worksheet>")
    }

    /** 窄表按 A4 纵向可打印高度预留标题与首行，显式照片分页优先。 */
    private fun headerPageBreakRows(sheet: Sheet): List<Int> {
        if ((sheet.rows.maxOfOrNull { it.size } ?: 0) > 2 || sheet.columnWidths.sum() > 90) return emptyList()
        val breaks = mutableListOf<Int>()
        var used = 0
        sheet.rows.indices.forEach { index ->
            val height = rowHeight(sheet, index)
            val nextHeight = if (sheet.rows[index].any { it.style == CellStyle.HEADER }) {
                sheet.rows.getOrNull(index + 1)?.let { rowHeight(sheet, index + 1) } ?: 0
            } else 0
            if (used > 0 && nextHeight > 0 && used + height + nextHeight > 770) {
                breaks += index
                used = 0
            } else if (used + height > 770) {
                used = 0
            }
            used += height
        }
        return breaks
    }

    /** XML 行高与水印打印范围共用同一估算，像素按 96 DPI 转成点。 */
    private fun rowHeight(sheet: Sheet, index: Int): Int {
        if (index !in sheet.rowHeights && sheet.images.none { it.row == index } &&
            sheet.rows.getOrNull(index)?.all { it.value.isBlank() } == true) return 10
        val height = sheet.rowHeights[index] ?: if (sheet.images.any { it.row == index }) 96 else
            sheet.rows.getOrNull(index).orEmpty().mapIndexed { column, cell ->
                val columnWidth = if (index in sheet.mergedRows) sheet.columnWidths.sum() else sheet.columnWidths.getOrElse(column) { 16 }
                val width = ((columnWidth.coerceAtLeast(4) - 3) * 1.2).toInt().coerceAtLeast(1)
                val lineCount = cell.value.lines().sumOf { line ->
                    val weight = line.codePoints().toArray().fold(0) { total, point -> total + if (point > 0x2FF) 2 else 1 }
                    ((weight + width - 1) / width).coerceAtLeast(1)
                }
                lineCount * (when (cell.style) {
                    CellStyle.TITLE -> 23
                    CellStyle.TOTAL -> 21
                    CellStyle.HEADER -> 17
                    else -> 16
                }) + 10
            }.maxOrNull() ?: 18
        return height.coerceIn(18, 409)
    }

    private fun sheetRelationships(
        sheetNumber: Int,
        sheet: Sheet,
        hyperlinks: List<String>
    ): String = buildString {
        append(XML_HEADER).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        hyperlinks.filterNot { it.startsWith("#") }.forEachIndexed { index, target ->
            append("<Relationship Id=\"rId").append(index + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
                .append(target.relationshipTarget().xml()).append("\" TargetMode=\"External\"/>")
        }
        if (sheet.hasDrawing()) {
            append("<Relationship Id=\"rId").append(hyperlinks.count { !it.startsWith("#") } + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing")
                .append(sheetNumber).append(".xml\"/>")
        }
        append("</Relationships>")
    }

    private fun drawing(images: List<Image>, charts: List<Chart>, watermarkImages: List<Image>): String = buildString {
        append(XML_HEADER)
        append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        images.forEachIndexed { index, image ->
            append("<xdr:oneCellAnchor><xdr:from><xdr:col>").append(image.column).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>")
                .append(image.row).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"")
                .append(image.widthPx * EMU_PER_PIXEL).append("\" cy=\"").append(image.heightPx * EMU_PER_PIXEL).append("\"/>")
            append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(index + 1).append("\" name=\"Evidence ").append(index + 1)
                .append("\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId").append(index + 1)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                .append(image.widthPx * EMU_PER_PIXEL).append("\" cy=\"").append(image.heightPx * EMU_PER_PIXEL)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
        }
        charts.forEachIndexed { index, chart ->
            val relationId = images.size + index + 1
            append("<xdr:oneCellAnchor><xdr:from><xdr:col>").append(chart.column).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(chart.row).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
            append("<xdr:ext cx=\"").append(chart.widthPx * EMU_PER_PIXEL).append("\" cy=\"").append(chart.heightPx * EMU_PER_PIXEL).append("\"/>")
            append("<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"").append(images.size + index + 1).append("\" name=\"Chart ").append(index + 1).append("\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rId").append(relationId).append("\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:oneCellAnchor>")
        }
        watermarkImages.forEachIndexed { index, image ->
            val relationId = images.size + charts.size + index + 1
            val drawingId = images.size + charts.size + index + 1
            append("<xdr:oneCellAnchor><xdr:from><xdr:col>").append(image.column).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>")
                .append(image.row).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"")
                .append(image.widthPx * EMU_PER_PIXEL).append("\" cy=\"").append(image.heightPx * EMU_PER_PIXEL).append("\"/>")
            append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(drawingId).append("\" name=\"Watermark ").append(index + 1)
                .append("\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId")
                .append(relationId).append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                .append(image.widthPx * EMU_PER_PIXEL).append("\" cy=\"").append(image.heightPx * EMU_PER_PIXEL)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
        }
        append("</xdr:wsDr>")
    }

    private fun drawingRelationships(
        images: List<Image>,
        charts: List<Chart>,
        watermarkImages: List<Image>,
        mediaOffset: Int,
        chartOffset: Int,
        mediaNames: List<String>
    ): String = buildString {
        append(XML_HEADER).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        images.indices.forEach { index ->
            append("<Relationship Id=\"rId").append(index + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/")
                .append(mediaNames[mediaOffset + index]).append("\"/>")
        }
        charts.indices.forEach { index ->
            append("<Relationship Id=\"rId").append(images.size + index + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart").append(chartOffset + index + 1).append(".xml\"/>")
        }
        watermarkImages.indices.forEach { index ->
            val relationId = images.size + charts.size + index + 1
            val mediaIndex = mediaOffset + images.size + index
            append("<Relationship Id=\"rId").append(relationId)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/")
                .append(mediaNames[mediaIndex]).append("\"/>")
        }
        append("</Relationships>")
    }

    private fun chartXml(chart: Chart): String = buildString {
        append(XML_HEADER)
        append("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:lang val=\"en-US\"/><c:roundedCorners val=\"0\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>").append(chart.title.xml()).append("</a:t></a:r></a:p></c:rich></c:tx></c:title><c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"1\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:v>").append(chart.title.xml()).append("</c:v></c:tx><c:cat><c:strLit><c:ptCount val=\"").append(chart.categories.size).append("\"/>")
        chart.categories.forEachIndexed { index, value -> append("<c:pt idx=\"").append(index).append("\"><c:v>").append(value.xml()).append("</c:v></c:pt>") }
        append("</c:strLit></c:cat><c:val><c:numLit><c:formatCode>0</c:formatCode><c:ptCount val=\"").append(chart.values.size).append("\"/>")
        chart.values.forEachIndexed { index, value -> append("<c:pt idx=\"").append(index).append("\"><c:v>").append(value).append("</c:v></c:pt>") }
        append("</c:numLit></c:val></c:ser><c:dLbls><c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/></c:dLbls><c:axId val=\"123456\"/><c:axId val=\"123457\"/></c:barChart><c:catAx><c:axId val=\"123456\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"123457\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/></c:catAx><c:valAx><c:axId val=\"123457\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"0\" sourceLinked=\"0\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"123456\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx></c:plotArea><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/><c:showDLblsOverMax val=\"0\"/></c:chart></c:chartSpace>")
    }

    private fun Sheet.hasDrawing(): Boolean = images.isNotEmpty() || charts.isNotEmpty() || watermarkImages.isNotEmpty()

    private fun Sheet.mediaCount(): Int = images.size + watermarkImages.size

    private fun File.mediaExtension(): String {
        return extension.lowercase(Locale.US).let { if (it == "jpeg") "jpg" else it }.also { extension ->
            require(extension == "jpg" || extension == "png") { "Unsupported Excel image type: $extension" }
        }
    }

    /** 将模板字号与颜色生成工作簿样式。 */
    private fun styles(theme: WorkbookTheme): String {
        val bodySize = if (theme.compactTables) 10 else theme.bodySize
        val titleSize = if (theme.compactTables) 14 else theme.titleSize
        val headerSize = if (theme.compactTables) 10 else theme.bodySize
        return XML_HEADER + """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <numFmts count="1"><numFmt numFmtId="164" formatCode="${if (theme.currencyCode == "USD") "&quot;$&quot;#,##0.00" else "&quot;${theme.currencyCode} &quot;#,##0" + if (java.util.Currency.getInstance(theme.currencyCode).defaultFractionDigits == 0) "" else ".00"}"/></numFmts>
        <fonts count="5"><font><sz val="$bodySize"/><name val="Arial"/></font><font><b/><sz val="$titleSize"/><color rgb="FF${theme.primaryColor}"/><name val="Arial"/></font><font><b/><sz val="$headerSize"/><color rgb="FF${theme.headerTextColor}"/><name val="Arial"/></font><font><b/><sz val="$bodySize"/><color rgb="FF1D2939"/><name val="Arial"/></font><font><u/><sz val="$bodySize"/><color rgb="FF${theme.accentColor}"/><name val="Arial"/></font></fonts>
        <fills count="7"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF${theme.headerFill}"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEAF8F0"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFDECEC"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF4E5"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF2F4F7"/><bgColor indexed="64"/></patternFill></fill></fills>
        <borders count="2"><border/><border><left style="thin"><color rgb="FFD0D5DD"/></left><right style="thin"><color rgb="FFD0D5DD"/></right><top style="thin"><color rgb="FFD0D5DD"/></top><bottom style="thin"><color rgb="FFD0D5DD"/></bottom></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="13"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="3" fillId="6" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="4" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="4" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="164" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyNumberFormat="1" applyBorder="1"><alignment horizontal="right" vertical="center"/></xf></cellXfs>
        <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>""".trimIndent()
    }

    private fun appProperties(sheets: List<Sheet>): String = XML_HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>RoofQuote</Application><DocSecurity>0</DocSecurity><ScaleCrop>false</ScaleCrop><HeadingPairs><vt:vector size=\"2\" baseType=\"variant\"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>${sheets.size}</vt:i4></vt:variant></vt:vector></HeadingPairs><TitlesOfParts><vt:vector size=\"${sheets.size}\" baseType=\"lpstr\">${sheets.joinToString("") { "<vt:lpstr>${it.name.xml()}</vt:lpstr>" }}</vt:vector></TitlesOfParts><Company></Company><LinksUpToDate>false</LinksUpToDate><SharedDoc>false</SharedDoc><HyperlinksChanged>false</HyperlinksChanged><AppVersion>1.0</AppVersion></Properties>"

    private fun coreProperties(): String = XML_HEADER +
        """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>RoofQuote</dc:creator><cp:lastModifiedBy>RoofQuote</cp:lastModifiedBy></cp:coreProperties>"""

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun String.xml(): String = validXmlText().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun String.relationshipTarget(): String = URI(null, null, replace('\\', '/'), null).toASCIIString()

    /** 写入单个 XML 条目，写入前检查中断。 */
    private fun ZipOutputStream.text(path: String, value: String) {
        checkOfficeActive()
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** 分块写入媒体条目，避免大图片复制期间无法响应中断。 */
    private fun ZipOutputStream.file(path: String, file: File) {
        checkOfficeActive()
        putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkOfficeActive()
                val count = input.read(buffer)
                if (count < 0) break
                write(buffer, 0, count)
            }
        }
        closeEntry()
    }

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val EMU_PER_PIXEL = 9525L
}

