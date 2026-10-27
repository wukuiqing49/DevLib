package com.wkq.roof.export

import com.wkq.roof.export.excel.SimpleXlsxWriter
import com.wkq.roof.export.word.SimpleDocxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class OfficeWriterTest {
    @Test fun manualPhotoPageBreaksAreSerializedAndValidated() {
        val directory = Files.createTempDirectory("roofquote-photo-breaks").toFile()
        try {
            val sheet = SimpleXlsxWriter.Sheet("Photos", (1..12).map { listOf(SimpleXlsxWriter.Cell("Photo $it")) },
                pageBreakRows = listOf(4, 8))
            val output = directory.resolve("photos.xlsx")
            SimpleXlsxWriter.write(output, listOf(sheet))
            ZipFile(output).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
                assertTrue(xml.contains("<rowBreaks count=\"2\" manualBreakCount=\"2\">"))
                assertTrue(xml.contains("<brk id=\"4\""))
                assertTrue(xml.contains("<brk id=\"8\""))
            }
            listOf(listOf(0), listOf(12), listOf(4, 4)).forEach { breaks ->
                assertTrue(runCatching { SimpleXlsxWriter.write(output, listOf(sheet.copy(pageBreakRows = breaks))) }.isFailure)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun longTableHeadersRepeatAndMoneyRemainsNumericForPrinting() {
        val directory = Files.createTempDirectory("roofquote-print").toFile()
        try {
            val docx = directory.resolve("long.docx")
            val xlsx = directory.resolve("money.xlsx")
            SimpleDocxWriter.write(docx, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Table(
                listOf(listOf("Description", "Total"), listOf("Long scope 屋顶 🏠 ".repeat(600), "1234.50"))
            ))))
            ZipFile(docx).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
                val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(xml.byteInputStream())
                val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                org.junit.Assert.assertEquals(1, document.getElementsByTagNameNS(namespace, "tblHeader").length)
                org.junit.Assert.assertEquals(2, document.getElementsByTagNameNS(namespace, "cantSplit").length)
                assertTrue(document.documentElement.textContent.contains("Long scope 屋顶 🏠 ".repeat(600)))
            }
            SimpleXlsxWriter.write(xlsx, listOf(SimpleXlsxWriter.Sheet("Quote", listOf(
                listOf(SimpleXlsxWriter.Cell.number(1234.5, SimpleXlsxWriter.CellStyle.MONEY))
            ))))
            ZipFile(xlsx).use { zip ->
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().use { it.readText() }
                val styles = zip.getInputStream(zip.getEntry("xl/styles.xml")).bufferedReader().use { it.readText() }
                assertTrue(sheet.contains("<v>1234.5</v>"))
                assertTrue(sheet.contains("s=\"10\""))
                assertTrue(sheet.contains("fitToWidth=\"1\""))
                assertTrue(sheet.contains("fitToHeight=\"0\""))
                val styleDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(styles.byteInputStream())
                val elements = styleDocument.documentElement.childNodes.let { nodes ->
                    (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
                }
                assertEquals("numFmts", elements[0].localName)
                assertEquals("fonts", elements[1].localName)
                val currencyFormat = elements[0].getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "numFmt"
                ).item(0) as org.w3c.dom.Element
                assertEquals("\"$\"#,##0.00", currencyFormat.getAttribute("formatCode"))
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun pastedControlCharactersDoNotCorruptOfficeDocuments() {
        val directory = Files.createTempDirectory("roofquote-xml").toFile()
        val dirty = "Roof\u0000\u000B & 屋顶 🏠\uD800"
        try {
            val docx = directory.resolve("quote.docx")
            val xlsx = directory.resolve("quote.xlsx")
            SimpleDocxWriter.write(docx, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Paragraph(dirty))))
            SimpleXlsxWriter.write(xlsx, listOf(SimpleXlsxWriter.Sheet("Quote", listOf(listOf(SimpleXlsxWriter.Cell(dirty))))))
            listOf(docx, xlsx).forEach { file ->
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.forEach { entry ->
                        zip.getInputStream(entry).use {
                            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                        }
                    }
                    val part = if (file == docx) "word/document.xml" else "xl/worksheets/sheet1.xml"
                    val xml = zip.getInputStream(zip.getEntry(part)).bufferedReader().use { it.readText() }
                    assertTrue(xml.contains("屋顶 🏠"))
                    assertTrue(xml.contains("&amp;"))
                }
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun invalidNumericCellsDoNotOverwriteExistingExport() {
        val directory = Files.createTempDirectory("roofquote-number").toFile()
        val output = directory.resolve("quote.xlsx")
        try {
            output.writeText("previous export")
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
                val result = runCatching {
                    SimpleXlsxWriter.write(output, listOf(SimpleXlsxWriter.Sheet("Quote", listOf(listOf(SimpleXlsxWriter.Cell.number(value))))))
                }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                org.junit.Assert.assertEquals("previous export", output.readText())
            }
        } finally {
            output.delete()
            directory.delete()
        }
    }

    @Test
    fun xlsxContainsNumericCellsAndRoofQuoteMetadata() {
        val directory = Files.createTempDirectory("roofquote-xlsx").toFile()
        val output = directory.resolve("estimate.xlsx")
        try {
            SimpleXlsxWriter.write(output, listOf(SimpleXlsxWriter.Sheet(
                name = "Overview",
                rows = listOf(
                    listOf(SimpleXlsxWriter.Cell("Price")),
                    listOf(SimpleXlsxWriter.Cell.number(1234.56))
                )
            )))
            ZipFile(output).use { zip ->
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
                val properties = zip.getInputStream(zip.getEntry("docProps/core.xml")).bufferedReader().readText()
                assertTrue(sheet.contains("<v>1234.56</v>"))
                assertTrue(properties.contains("RoofQuote"))
            }
        } finally {
            output.delete()
            directory.delete()
        }
    }

    @Test
    fun docxEscapesUnicodeAndProducesRequiredParts() {
        val directory = Files.createTempDirectory("roofquote-docx").toFile()
        val output = directory.resolve("estimate.docx")
        try {
            SimpleDocxWriter.write(output, SimpleDocxWriter.Document(listOf(
                SimpleDocxWriter.Paragraph("屋顶报价 & RoofQuote", SimpleDocxWriter.ParagraphStyle.TITLE)
            )))
            ZipFile(output).use { zip ->
                val document = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
                assertTrue(document.contains("屋顶报价 &amp; RoofQuote"))
                assertTrue(zip.getEntry("[Content_Types].xml") != null)
            }
        } finally {
            output.delete()
            directory.delete()
        }
    }
}
