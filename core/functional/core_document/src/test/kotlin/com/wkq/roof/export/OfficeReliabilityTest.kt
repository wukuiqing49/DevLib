package com.wkq.roof.export

import com.wkq.roof.export.word.SimpleDocxWriter
import com.wkq.roof.export.excel.SimpleXlsxWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

/** 验证已有输出替换、容量拒绝和取消保留，均只使用隔离临时目录。 */
class OfficeReliabilityTest {
    /** 连续导出到相同文件时应替换为完整新文档。 */
    @Test fun replacesExistingWordAndExcelOutputs() {
        val root = Files.createTempDirectory("office-replace").toFile()
        try {
            val word = root.resolve("test.docx")
            val excel = root.resolve("test.xlsx")
            for (text in listOf("first", "第二次 & <更新>")) {
                SimpleDocxWriter.write(word, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Paragraph(text))))
                SimpleXlsxWriter.write(excel, listOf(SimpleXlsxWriter.Sheet("Sheet", listOf(listOf(SimpleXlsxWriter.Cell(text))))))
            }
            for ((file, entry) in listOf(word to "word/document.xml", excel to "xl/worksheets/sheet1.xml")) {
                ZipFile(file).use { zip ->
                    val xml = zip.getInputStream(zip.getEntry(entry)).reader(Charsets.UTF_8).readText()
                    assertTrue(xml.contains("第二次"))
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
                }
            }
        } finally { root.deleteRecursively() }
    }
    /** 超长单元格不得静默截断或覆盖旧文件。 */
    @Test fun rejectsOversizedCellAndKeepsOldOutput() {
        val root = Files.createTempDirectory("office-cell").toFile()
        try {
            val output = root.resolve("test.xlsx").apply { writeText("old") }
            val sheet = SimpleXlsxWriter.Sheet("Sheet", listOf(listOf(SimpleXlsxWriter.Cell("x".repeat(32768)))))
            assertTrue(runCatching { SimpleXlsxWriter.write(output, listOf(sheet)) }.isFailure)
            assertEquals("old", output.readText())
        } finally { root.deleteRecursively() }
    }
    /** 中断时旧文档不变，线程中断标记不被库清除。 */
    @Test fun interruptionNeverPublishesPartialDocument() {
        val root = Files.createTempDirectory("office-cancel").toFile()
        try {
            val output = root.resolve("test.docx").apply { writeText("old") }
            Thread.currentThread().interrupt()
            assertTrue(runCatching { SimpleDocxWriter.write(output, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Paragraph("new")))) }.isFailure)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals("old", output.readText())
        } finally { Thread.interrupted(); root.deleteRecursively() }
    }
}
