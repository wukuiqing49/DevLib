package com.wkq.dev.document

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

/** 模板、公司个人资料、签署信息及历史快照的真实 Office 产物回归。 */
class RecordTemplateTest {
    /** 模板完整往返保留标签、字段选择、版式、水印和版本，不带入资料。 */
    @Test fun templateRoundTripPreservesConfiguration() {
        val root = Files.createTempDirectory("template-codec").toFile()
        try {
            val template = RecordTemplate(id = "custom", revision = 3, name = "自定义",
                blocks = listOf(RecordBlock.SIGNATURES, RecordBlock.TITLE, RecordBlock.DETAILS),
                labels = mapOf(RecordBlock.SIGNATURES to "签署确认"), fieldLabels = mapOf("signer" to "签署人"),
                detailColumns = listOf(1, 0), fieldNames = listOf("编号"), imageColumns = 3,
                style = RecordStyle("223344", "DDEEFF", 13, 24, 48, true, "页眉", "页脚", false),
                watermark = RecordWatermark(WatermarkKind.TEXT, "草稿", opacityPercent = 20, rotationDegrees = 25, widthPercent = 70, xPercent = 40, yPercent = 60, everyPage = false))
            val json = RecordTemplateCodec.encode(template)
            assertEquals(template, RecordTemplateCodec.decode(json, root))
            assertFalse(json.contains("company")); assertFalse(json.contains("signatureImage"))
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    /** 图片模板携带图片字节，导入只写散列命名资产，拒绝未知版本及路径注入。 */
    @Test fun templateImageIsPortableAndRejectsPathInjection() {
        val root = Files.createTempDirectory("template-image").toFile()
        try {
            val image = root.resolve("logo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val template = RecordTemplate(watermark = RecordWatermark(WatermarkKind.IMAGE, image = image))
            val json = RecordTemplateCodec.encode(template)
            assertFalse(json.contains(root.absolutePath.replace("\\", "\\\\")))
            val restored = RecordTemplateCodec.decode(json, root.resolve("imported"))
            assertArrayEquals(image.readBytes(), restored.watermark.image!!.readBytes())
            assertTrue(runCatching { RecordTemplateCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), root) }.isFailure)
            assertTrue(runCatching { RecordTemplateCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.5"), root) }.isFailure)
            assertTrue(runCatching { RecordTemplateCodec.decode(json.replace("\"kind\": \"IMAGE\"", "\"kind\": \"IMAGE\", \"image\": \"../private.png\""), root) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    /** 公司个人与两位签署人进入两种 Office，模板顺序、列筛选和字体落实在 XML。 */
    @Test fun profilesSignersOrderAndStylesReachOffice() {
        val root = Files.createTempDirectory("record-profiles").toFile()
        try {
            val template = RecordTemplate(blocks = listOf(RecordBlock.SIGNATURES, RecordBlock.COMPANY, RecordBlock.PERSON, RecordBlock.TITLE, RecordBlock.DETAILS),
                detailColumns = listOf(1), style = RecordStyle(bodySize = 13, titleSize = 24, landscape = true, header = "Header &", footer = "Footer &", showPageNumbers = false))
            val record = ExportRecord("Record", company = RecordCompany("ACME", "Address", "123", "a@example.test", "https://example.test", "ID001"),
                person = RecordPerson("Person", "Engineer", "ACME"), signatures = listOf(RecordSignature("Alice", "Reviewer", "2026-10-27", "Approved"), RecordSignature("Bob")),
                rows = listOf(listOf(RecordCell("omit"), RecordCell("keep"))), template = template)
            val outputs = RecordExporter.export(record, root, setOf(RecordFormat.EXCEL, RecordFormat.WORD))
            val word = xml(outputs.first { it.extension == "docx" }, "word/document.xml")
            val excelFile = outputs.first { it.extension == "xlsx" }
            val excel = xml(excelFile, "xl/worksheets/")
            listOf(word, excel).forEach { text ->
                listOf("Alice", "Bob", "ACME", "Person", "ID001", "Approved", "keep").forEach { assertTrue(it, text.contains(it)) }
                assertFalse(text.contains("omit"))
            }
            assertTrue(word.indexOf("Alice") < word.indexOf("ACME"))
            assertTrue(word.contains("landscape"))
            assertTrue(xml(outputs.first { it.extension == "docx" }, "word/styles.xml").contains("w:val=\"26\""))
            assertTrue(xml(excelFile, "xl/styles.xml").contains("sz val=\"13\""))
            assertTrue(excel.contains("orientation=\"landscape\""))
            assertTrue(excel.contains("Header &amp;&amp;"))
            assertFalse(excel.contains("&amp;P"))
        } finally { root.deleteRecursively() }
    }

    /** 默认资料或模板后续变化不能改变历史快照，恢复后可重新导出同一份内容。 */
    @Test fun snapshotRestoresOriginalProfilesAndTemplate() {
        val root = Files.createTempDirectory("record-history").toFile()
        try {
            val record = ExportRecord("Original", company = RecordCompany("Before"), signatures = listOf(RecordSignature("Signer")),
                template = RecordTemplate(id = "v1", revision = 7))
            val files = RecordExporter.export(record, root, setOf(RecordFormat.WORD))
            val restored = RecordSnapshot.restore(checkNotNull(files.single().parentFile))
            assertEquals(record, restored)
            val again = RecordExporter.export(restored, root, setOf(RecordFormat.WORD))
            assertTrue(xml(again.single(), "word/document.xml").contains("Before"))
            assertFalse(checkNotNull(files.single().parentFile).resolve("record-snapshot.json").readText().contains(root.absolutePath.replace("\\", "\\\\")))
        } finally { root.deleteRecursively() }
    }

    /** 无效模板与签署人明确拒绝；全隐藏模板不发布空文件。 */
    @Test fun invalidOrEmptyLayoutDoesNotPublish() {
        val root = Files.createTempDirectory("record-hidden").toFile()
        try {
            val invalid = listOf(RecordTemplate(blocks = emptyList()), RecordTemplate(blocks = listOf(RecordBlock.TITLE, RecordBlock.TITLE)),
                RecordTemplate(detailColumns = listOf(0, 0)), RecordTemplate(style = RecordStyle(bodySize = 100)),
                RecordTemplate(watermark = RecordWatermark(WatermarkKind.TEXT, "")))
            invalid.forEach { assertTrue(runCatching { it.validate() }.isFailure) }
            assertTrue(runCatching { ExportRecord("Title", signatures = listOf(RecordSignature(" "))).validate() }.isFailure)
            assertTrue(runCatching { RecordExporter.export(ExportRecord("Title", template = RecordTemplate(blocks = listOf(RecordBlock.SIGNATURES))), root, setOf(RecordFormat.WORD)) }.isFailure)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    /** 汇总指定部件的真实 XML 内容，避免只验证内存中的映射对象。 */
    private fun xml(file: java.io.File, prefix: String): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.joinToString("\n") {
            zip.getInputStream(it).reader(Charsets.UTF_8).readText()
        }
    }
}
