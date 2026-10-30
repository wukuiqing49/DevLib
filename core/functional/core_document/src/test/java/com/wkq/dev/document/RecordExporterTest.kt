package com.wkq.dev.document

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** 记录导出契约回归：真实 Office 产物、批量事务、中断以及业务内容保留。 */
class RecordExporterTest {
    /** 相同记录的标题、时间、备注、字段与明细应同时进入 Word 和 Excel，数字类型不丢失。 */
    @Test fun sameRecordReachesBothOfficeFormats() {
        val root = Files.createTempDirectory("record-content").toFile()
        try {
            val record = ExportRecord("巡检 / [记录]", "2026-10-27", "备注 & <安全>\n第二行",
                fields = listOf(RecordField("编号", "00123")),
                rows = listOf(listOf(RecordCell("项目"), RecordCell("数量")), listOf(RecordCell("设备"), RecordCell("12.5", true))))
            val files = RecordExporter.export(record, root, setOf(RecordFormat.EXCEL, RecordFormat.WORD))
            assertEquals(setOf("xlsx", "docx"), files.map { it.extension }.toSet())
            val word = entry(files.first { it.extension == "docx" }, "word/document.xml")
            val excel = files.first { it.extension == "xlsx" }
            val summary = ZipFile(excel).use { zip -> zip.entries().asSequence().filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }.joinToString("\n") { zip.getInputStream(it).reader(Charsets.UTF_8).readText() } }
            val details = summary
            listOf(word, summary).forEach { xml ->
                listOf("巡检 / [记录]", "2026-10-27", "00123", "第二行", "&amp;", "&lt;安全&gt;").forEach { assertTrue(it, xml.contains(it)) }
            }
            assertTrue(word.contains("Title"))
            assertTrue(details.contains("<v>12.5</v>"))
            assertTrue(details.contains("设备"))
            files.forEach { file -> ZipFile(file).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.forEach {
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip.getInputStream(it))
                }
            } }
        } finally { root.deleteRecursively() }
    }

    /** 后续格式失败时删除所有本批临时文件，先前导出保持原样。 */
    @Test fun failedBatchDoesNotPublishEarlierFormat() {
        val root = Files.createTempDirectory("record-rollback").toFile()
        try {
            val old = root.resolve("previous.txt").apply { writeText("keep") }
            val error = runCatching {
                RecordExporter.export(ExportRecord("记录"), root, setOf(RecordFormat.EXCEL, RecordFormat.WORD)) { _, _ -> error("consumer failed") }
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(listOf(old), root.listFiles()!!.toList())
            assertEquals("keep", old.readText())
        } finally { root.deleteRecursively() }
    }

    /** 格式之间收到中断不得发布半批结果，线程中断状态由调用方负责清理。 */
    @Test fun cancelledBatchCleansTemporaryFiles() {
        val root = Files.createTempDirectory("record-cancel").toFile()
        try {
            assertTrue(runCatching {
                RecordExporter.export(ExportRecord("记录"), root, setOf(RecordFormat.EXCEL, RecordFormat.WORD)) { _, _ -> Thread.currentThread().interrupt() }
            }.isFailure)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { Thread.interrupted(); root.deleteRecursively() }
    }

    /** 高精度编号必须作为文本保留，无效数字、非矩形表格和空格式明确拒绝。 */
    @Test fun invalidRecordNeverCreatesOutput() {
        val root = Files.createTempDirectory("record-invalid").toFile()
        try {
            val bad = listOf(ExportRecord(" "),
                ExportRecord("记录", rows = listOf(listOf(RecordCell("NaN", true)))),
                ExportRecord("记录", rows = listOf(listOf(RecordCell("1234567890123456", true)))),
                ExportRecord("记录", rows = listOf(listOf(RecordCell("a")), emptyList())))
            bad.forEach { assertTrue(runCatching { RecordExporter.export(it, root, setOf(RecordFormat.EXCEL)) }.isFailure) }
            assertTrue(runCatching { RecordExporter.export(ExportRecord("记录"), root, emptySet()) }.isFailure)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    /** 解包读取真实 XML，断言面向产物而非仅比较映射对象。 */
    private fun entry(file: File, name: String): String = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(name)).reader(Charsets.UTF_8).readText()
    }
}
