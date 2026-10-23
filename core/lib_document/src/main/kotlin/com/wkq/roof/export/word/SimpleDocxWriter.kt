package com.wkq.roof.export.word

import com.wkq.roof.export.word.WordPageOrientation
import com.wkq.roof.export.word.WordReportTheme
import java.io.File
import com.wkq.roof.export.validXmlText
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** RoofQuote 离线报价单需要的最小 Word OpenXML 写入器。 */
object SimpleDocxWriter {

    data class Document(
        val blocks: List<Block>,
        val theme: WordReportTheme = WordReportTheme(),
        val orientation: WordPageOrientation = WordPageOrientation.PORTRAIT,
        val marginTwips: Int = 1_080,
        val headerText: String = "",
        val watermarkText: String = "",
        val footerText: String = "",
        val rightToLeft: Boolean = false,
        val showPageNumber: Boolean = true,
        val watermarkOpacityPercent: Int = 12,
        val watermarkRotationDegrees: Int = -35,
        val watermarkRepeatOnEveryPage: Boolean = true,
        val watermarkImage: Image? = null
    )

    sealed interface Block

    data class Paragraph(
        val text: String,
        val style: ParagraphStyle = ParagraphStyle.NORMAL,
        val pageBreakBefore: Boolean = false
    ) : Block

    data class Table(
        val rows: List<List<String>>,
        val headerRows: Int = 1,
        val columnWeights: List<Int> = emptyList(),
        val keepTogether: Boolean = false,
        /** 普通明细行尽量整行换页，长到超过一页时由阅读器处理。 */
        val keepRowsTogether: Boolean = true
    ) : Block

    data class Image(
        val file: File,
        val widthPx: Int,
        val heightPx: Int,
        val description: String = "Report image"
    ) : Block

    /** 用于受控照片版式的图文网格，避免依赖外部 Word 模板的浮动布局。 */
    data class ImageGrid(
        val items: List<Item>,
        val columns: Int
    ) : Block {
        init {
            require(items.isNotEmpty()) { "Word image grid requires at least one item." }
            require(columns in 2..3) { "Word image grid supports two or three columns." }
        }

        data class Item(
            val image: Image,
            val caption: String
        )
    }

    data object PageBreak : Block

    enum class ParagraphStyle {
        NORMAL,
        TITLE,
        HEADING_1,
        HEADING_2,
        CAPTION,
        MUTED
    }

    fun write(output: File, document: Document): File {
        require(document.blocks.isNotEmpty()) { "Word document must contain at least one block." }
        require(document.marginTwips in 360..2_880) { "Word document margin is out of range." }
        require(document.watermarkOpacityPercent in 1..40)
        require(document.watermarkRotationDegrees in -90..90)
        val images = document.blocks.flatMap(::blockImages) + listOfNotNull(document.watermarkImage)
        images.forEach { image ->
            require(image.file.isFile) { "Word image does not exist: ${image.file}" }
            require(image.widthPx > 0 && image.heightPx > 0) { "Word image dimensions must be positive." }
            require(image.extension() in SUPPORTED_IMAGE_EXTENSIONS) {
                "Unsupported Word image type: ${image.file.extension}"
            }
        }

        output.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val temporary = File.createTempFile("docx_", ".tmp", output.absoluteFile.parentFile)
        try {
            val relationships = Relationships(document, images)
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.text("[Content_Types].xml", contentTypes(document, images))
                zip.text("_rels/.rels", rootRelationships())
                zip.text("word/document.xml", documentXml(document, relationships))
                zip.text("word/styles.xml", stylesXml(document.theme, document.rightToLeft))
                zip.text("word/_rels/document.xml.rels", documentRelationships(relationships, images))
                if (relationships.headerId != null) {
                    zip.text(
                        "word/header1.xml",
                        headerXml(document.headerText,
                            if (relationships.firstHeaderId == null) document.watermarkText else "",
                            document, document.watermarkImage.takeIf { relationships.firstHeaderId == null })
                    )
                    if (document.watermarkImage != null && relationships.firstHeaderId == null) {
                        zip.text("word/_rels/header1.xml.rels", watermarkRelationships(images))
                    }
                }
                if (relationships.firstHeaderId != null) {
                    zip.text("word/header2.xml", headerXml(document.headerText, document.watermarkText, document, document.watermarkImage))
                    if (document.watermarkImage != null) zip.text("word/_rels/header2.xml.rels", watermarkRelationships(images))
                }
                if (relationships.footerId != null) {
                    zip.text(
                        "word/footer1.xml",
                        footerXml(document.footerText, document.showPageNumber, document.rightToLeft)
                    )
                }
                images.forEachIndexed { index, image ->
                    zip.file("word/media/image${index + 1}.${image.extension()}", image.file)
                }
            }
            check(temporary.isFile && temporary.length() > 0L)
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            // 同目录替换失败时保留旧报告，不能先删除正式文件。
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
        check(output.isFile && output.length() > 0L)
        return output
    }

    private fun contentTypes(document: Document, images: List<Image>): String {
        val imageDefaults = images.map { it.extension() }.distinct().joinToString("") { extension ->
            val contentType = if (extension == "png") "image/png" else "image/jpeg"
            "<Default Extension=\"$extension\" ContentType=\"$contentType\"/>"
        }
        val headerOverride = if (document.headerText.isNotBlank() || document.watermarkText.isNotBlank() || document.watermarkImage != null) {
            "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
        } else {
            ""
        }
        val firstHeaderOverride = if ((document.watermarkText.isNotBlank() || document.watermarkImage != null) && !document.watermarkRepeatOnEveryPage) {
            "<Override PartName=\"/word/header2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
        } else {
            ""
        }
        val footerOverride = if (document.footerText.isNotBlank() || document.showPageNumber) {
            "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>"
        } else {
            ""
        }
        return xml(
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                imageDefaults +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                headerOverride + firstHeaderOverride + footerOverride +
                "</Types>"
        )
    }

    private fun rootRelationships(): String = xml(
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
    )

    private fun documentRelationships(relationships: Relationships, images: List<Image>): String {
        val content = buildString {
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            relationships.headerId?.let {
                append("<Relationship Id=\"").append(it)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>")
            }
            relationships.firstHeaderId?.let {
                append("<Relationship Id=\"").append(it)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header2.xml\"/>")
            }
            relationships.footerId?.let {
                append("<Relationship Id=\"").append(it)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>")
            }
            images.forEachIndexed { index, image ->
                append("<Relationship Id=\"").append(relationships.imageIds[index])
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                    .append(index + 1).append('.').append(image.extension()).append("\"/>")
            }
            append("</Relationships>")
        }
        return xml(content)
    }

    private fun documentXml(document: Document, relationships: Relationships): String {
        var imageIndex = 0
        val blocks = buildString {
            document.blocks.forEachIndexed { index, block ->
                // 只隔开相邻表格，末尾不追加可能挤出空白页的段落。
                if (index > 0 && (block is Table || block is ImageGrid) &&
                    (document.blocks[index - 1] is Table || document.blocks[index - 1] is ImageGrid)) {
                    append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>")
                }
                when (block) {
                    is Paragraph -> append(paragraphXml(block, document.rightToLeft))
                    is Table -> append(tableXml(block, document.theme, document.rightToLeft,
                        (if (document.orientation == WordPageOrientation.LANDSCAPE) A4_HEIGHT_TWIPS else A4_WIDTH_TWIPS) - document.marginTwips * 2))
                    is Image -> append(
                        imageParagraphXml(
                            block,
                            relationships.imageIds[imageIndex],
                            imageIndex++ + 1,
                            document.rightToLeft
                        )
                    )
                    is ImageGrid -> {
                        append(imageGridXml(block, relationships.imageIds, imageIndex, document.rightToLeft))
                        imageIndex += block.items.size
                    }
                    PageBreak -> append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                }
            }
        }
        return xml(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<w:body>" + blocks + sectionProperties(document, relationships) + "</w:body></w:document>"
        )
    }

    private fun paragraphXml(paragraph: Paragraph, rightToLeft: Boolean): String {
        val style = when (paragraph.style) {
            ParagraphStyle.NORMAL -> "Normal"
            ParagraphStyle.TITLE -> "Title"
            ParagraphStyle.HEADING_1 -> "Heading1"
            ParagraphStyle.HEADING_2 -> "Heading2"
            ParagraphStyle.CAPTION -> "Caption"
            ParagraphStyle.MUTED -> "Muted"
        }
        val pageBreak = if (paragraph.pageBreakBefore) "<w:pageBreakBefore/>" else ""
        val bidi = if (rightToLeft) "<w:bidi/>" else ""
        val rtl = if (rightToLeft) "<w:rtl/>" else ""
        return "<w:p><w:pPr><w:pStyle w:val=\"$style\"/>$bidi$pageBreak</w:pPr>" +
            textRunsXml(paragraph.text, rtl) + "</w:p>"
    }

    private fun tableXml(table: Table, theme: WordReportTheme, rightToLeft: Boolean, contentWidth: Int): String {
        if (table.rows.isEmpty()) return ""
        val columns = table.rows.maxOf { it.size }
        require(columns > 0)
        val weights = table.columnWeights.ifEmpty { List(columns) { 1 } }
        require(weights.size == columns && weights.all { it > 0 })
        val widths = weights.map { (contentWidth.toLong() * it / weights.sum()).toInt() }
        val grid = widths.joinToString("") { "<w:gridCol w:w=\"$it\"/>" }
        val rows = table.rows.mapIndexed { rowIndex, row ->
            val cells = (0 until columns).joinToString("") { column ->
                val value = row.getOrNull(column).orEmpty()
                val fill = if (rowIndex < table.headerRows) {
                    "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${theme.tableHeaderFill}\"/>"
                } else {
                    ""
                }
                val bold = if (rowIndex < table.headerRows) "<w:b/>" else ""
                val keepNext = if ((table.keepTogether || rowIndex < table.headerRows) && rowIndex < table.rows.lastIndex) "<w:keepNext/>" else ""
                "<w:tc><w:tcPr><w:tcW w:w=\"${widths[column]}\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/>$fill</w:tcPr>" +
                    "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"276\" w:lineRule=\"auto\"/>$keepNext${if (rightToLeft) "<w:bidi/>" else ""}</w:pPr>" +
                    textRunsXml(value, bold + "<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>" + if (rightToLeft) "<w:rtl/>" else "") + "</w:p></w:tc>"
            }
            val rowProperties = if (rowIndex < table.headerRows) "<w:tblHeader/><w:cantSplit/>" else if (table.keepTogether || table.keepRowsTogether) "<w:cantSplit/>" else ""
            "<w:tr><w:trPr>$rowProperties</w:trPr>$cells</w:tr>"
        }.joinToString("")
        return "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$contentWidth\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>" +
            (if (rightToLeft) "<w:bidiVisual/>" else "") +
            "<w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>$grid</w:tblGrid>$rows</w:tbl>"
    }

    private fun imageParagraphXml(image: Image, relationshipId: String, imageId: Int, rightToLeft: Boolean): String {
        val widthEmu = image.widthPx.toLong() * EMU_PER_PIXEL
        val heightEmu = image.heightPx.toLong() * EMU_PER_PIXEL
        val description = image.description.escapeXml()
        val bidi = if (rightToLeft) "<w:pPr><w:bidi/></w:pPr>" else ""
        return "<w:p>$bidi<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$widthEmu\" cy=\"$heightEmu\"/><wp:docPr id=\"$imageId\" name=\"Picture $imageId\" descr=\"$description\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$imageId\" name=\"Picture $imageId\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$relationshipId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$widthEmu\" cy=\"$heightEmu\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private fun imageGridXml(
        grid: ImageGrid,
        relationshipIds: List<String>,
        startImageIndex: Int,
        rightToLeft: Boolean
    ): String = buildString {
        append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
        if (rightToLeft) append("<w:bidiVisual/>")
        append("<w:tblLook w:val=\"04A0\"/></w:tblPr>")
        grid.items.chunked(grid.columns).forEachIndexed { rowIndex, row ->
            append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            repeat(grid.columns) { columnIndex ->
                val item = row.getOrNull(columnIndex)
                append("<w:tc><w:tcPr/>")
                if (item != null) {
                    val imageIndex = startImageIndex + rowIndex * grid.columns + columnIndex
                    append(imageParagraphXml(item.image, relationshipIds[imageIndex], imageIndex + 1, rightToLeft))
                    append(paragraphXml(Paragraph(item.caption, ParagraphStyle.CAPTION), rightToLeft))
                } else {
                    append("<w:p/>")
                }
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
    }

    private fun textRunsXml(value: String, runProperties: String = ""): String {
        val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return lines.joinToString("<w:r><w:br/></w:r>") { line ->
            "<w:r><w:rPr>$runProperties</w:rPr><w:t xml:space=\"preserve\">${line.escapeXml()}</w:t></w:r>"
        }
    }

    private fun blockImages(block: Block): List<Image> = when (block) {
        is Image -> listOf(block)
        is ImageGrid -> block.items.map(ImageGrid.Item::image)
        else -> emptyList()
    }

    private fun sectionProperties(document: Document, relationships: Relationships): String {
        val header = relationships.headerId?.let { "<w:headerReference w:type=\"default\" r:id=\"$it\"/>" }.orEmpty()
        val firstHeader = relationships.firstHeaderId?.let { "<w:headerReference w:type=\"first\" r:id=\"$it\"/>" }.orEmpty()
        val footer = relationships.footerId?.let { "<w:footerReference w:type=\"default\" r:id=\"$it\"/>" }.orEmpty()
        val firstFooter = if (relationships.firstHeaderId != null) relationships.footerId
            ?.let { "<w:footerReference w:type=\"first\" r:id=\"$it\"/>" }.orEmpty() else ""
        val landscape = document.orientation == WordPageOrientation.LANDSCAPE
        val width = if (landscape) A4_HEIGHT_TWIPS else A4_WIDTH_TWIPS
        val height = if (landscape) A4_WIDTH_TWIPS else A4_HEIGHT_TWIPS
        val orientation = if (landscape) " w:orient=\"landscape\"" else ""
        val titlePage = if (relationships.firstHeaderId != null) "<w:titlePg/>" else ""
        return "<w:sectPr>$header$firstHeader$footer$firstFooter<w:pgSz w:w=\"$width\" w:h=\"$height\"$orientation/>" +
            "<w:pgMar w:top=\"${document.marginTwips}\" w:right=\"${document.marginTwips}\" " +
            "w:bottom=\"${document.marginTwips}\" w:left=\"${document.marginTwips}\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/>" +
            titlePage + "</w:sectPr>"
    }

    private fun stylesXml(theme: WordReportTheme, rightToLeft: Boolean): String = xml(
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            style("Normal", "Normal", 22, theme.bodyColor, after = 100, rightToLeft = rightToLeft) +
            style("Title", "Title", 40, theme.primaryColor, bold = true, after = 240, rightToLeft = rightToLeft) +
            style("Heading1", "heading 1", 28, theme.primaryColor, bold = true, before = 280, after = 140, rightToLeft = rightToLeft) +
            style("Heading2", "heading 2", 24, theme.titleColor, bold = true, before = 180, after = 80, rightToLeft = rightToLeft) +
            style("Caption", "Caption", 18, theme.mutedColor, after = 120, rightToLeft = rightToLeft) +
            style("Muted", "Muted", 18, theme.mutedColor, rightToLeft = rightToLeft) +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>" +
            "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/>" +
            "<w:left w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/>" +
            "<w:right w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/>" +
            "<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/></w:tblBorders>" +
            "<w:tblCellMar><w:top w:w=\"100\" w:type=\"dxa\"/><w:left w:w=\"120\" w:type=\"dxa\"/>" +
            "<w:bottom w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"120\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "</w:styles>"
    )

    private fun style(
        id: String,
        name: String,
        size: Int,
        color: String,
        bold: Boolean = false,
        before: Int = 0,
        after: Int = 80,
        rightToLeft: Boolean = false
    ): String {
        val boldXml = if (bold) "<w:b/>" else ""
        return "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/>" +
            "<w:pPr>${if (rightToLeft) "<w:bidi/>" else ""}${if (id == "Title" || id.startsWith("Heading")) "<w:keepNext/><w:keepLines/>" else "<w:widowControl/>"}<w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"${if (id == "Normal") 312 else 276}\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>$boldXml${if (rightToLeft) "<w:rtl/>" else ""}<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
    }

    private fun headerXml(text: String, watermarkText: String, document: Document, watermarkImage: Image? = null): String = xml(
        "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" " +
            "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w10=\"urn:schemas-microsoft-com:office:word\">" +
            (watermarkImage?.let(::watermarkImageXml) ?: watermarkXml(watermarkText, document)) +
            (if (text.isNotBlank()) {
                "<w:p><w:pPr><w:pStyle w:val=\"Muted\"/>${if (document.rightToLeft) "<w:bidi/>" else ""}</w:pPr>" +
                    "<w:r><w:rPr>${if (document.rightToLeft) "<w:rtl/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${text.escapeXml()}</w:t></w:r></w:p>"
            } else {
                "<w:p/>"
            }) +
            "</w:hdr>"
    )

    private fun watermarkRelationships(images: List<Image>): String = xml(
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdWatermark\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
            "Target=\"media/image${images.size}.${images.last().extension()}\"/></Relationships>"
    )

    /** 透明度及角度已绘入 PNG，页眉锚点重复显示，不参与正文分页。 */
    private fun watermarkImageXml(image: Image): String {
        val width = image.widthPx.toLong() * EMU_PER_PIXEL
        val height = image.heightPx.toLong() * EMU_PER_PIXEL
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr>" +
            "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"0\" " +
            "behindDoc=\"1\" locked=\"0\" layoutInCell=\"0\" allowOverlap=\"1\">" +
            "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:align>center</wp:align></wp:positionH>" +
            "<wp:positionV relativeFrom=\"page\"><wp:align>center</wp:align></wp:positionV><wp:extent cx=\"$width\" cy=\"$height\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/><wp:docPr id=\"2147483646\" name=\"Watermark\" descr=\"${image.description.escapeXml()}\"/>" +
            "<wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"Watermark\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdWatermark\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$width\" cy=\"$height\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>"
    }

    private fun watermarkXml(text: String, document: Document): String {
        if (text.isBlank()) return ""
        val rotation = (document.watermarkRotationDegrees + 360) % 360
        return "<w:p><w:r><w:pict><v:shape id=\"RoofQuoteWatermark\" type=\"#_x0000_t136\" " +
            "style=\"position:absolute;width:360pt;height:90pt;rotation:$rotation;z-index:-251654144;mso-wrap-style:none;" +
            "mso-position-horizontal:center;mso-position-horizontal-relative:margin;" +
            "mso-position-vertical:center;mso-position-vertical-relative:margin\" fillcolor=\"#${document.theme.primaryColor}\" stroked=\"f\">" +
            "<v:fill opacity=\"${document.watermarkOpacityPercent}%\"/>" +
            "<v:textpath style=\"font-family:&quot;Arial&quot;;font-size:1pt\" string=\"${text.escapeXml()}\"/>" +
            "<w10:wrap anchorx=\"margin\" anchory=\"margin\"/></v:shape></w:pict></w:r></w:p>"
    }

    private fun footerXml(text: String, showPageNumber: Boolean, rightToLeft: Boolean): String {
        val separator = if (text.isNotBlank() && showPageNumber) " | " else ""
        val page = if (showPageNumber) {
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
        } else {
            ""
        }
        return xml(
            "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:p><w:pPr><w:jc w:val=\"center\"/><w:pStyle w:val=\"Muted\"/>${if (rightToLeft) "<w:bidi/>" else ""}</w:pPr>" +
                "<w:r><w:rPr>${if (rightToLeft) "<w:rtl/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${(text + separator).escapeXml()}</w:t></w:r>$page</w:p></w:ftr>"
        )
    }

    private fun xml(body: String): String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>$body"

    private fun String.escapeXml(): String = validXmlText().replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun Image.extension(): String {
        return file.extension.lowercase(Locale.US).let { if (it == "jpeg") "jpg" else it }
    }

    private fun ZipOutputStream.text(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.file(path: String, file: File) {
        putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { it.copyTo(this) }
        closeEntry()
    }

    private class Relationships(document: Document, images: List<Image>) {
        private var nextId = 2
        val headerId: String? = document.takeIf {
            it.headerText.isNotBlank() || it.watermarkText.isNotBlank() || it.watermarkImage != null
        }?.let { nextRelationshipId() }
        val firstHeaderId: String? = ((document.watermarkText.isNotBlank() || document.watermarkImage != null) && !document.watermarkRepeatOnEveryPage)
            .takeIf { it }?.let { nextRelationshipId() }
        val footerId: String? = (document.footerText.isNotBlank() || document.showPageNumber)
            .takeIf { it }
            ?.let { nextRelationshipId() }
        val imageIds: List<String> = images.map { nextRelationshipId() }

        private fun nextRelationshipId(): String = "rId${nextId++}"
    }

    private const val EMU_PER_PIXEL = 9_525L
    private const val A4_WIDTH_TWIPS = 11_906
    private const val A4_HEIGHT_TWIPS = 16_838
    private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "png")
}

