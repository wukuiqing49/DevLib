package com.wkq.dev.document

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** 设备端验证记录 PDF 可由系统阅读器打开，长备注和明细确实产生分页；默认不自动执行。 */
class RecordPdfInstrumentedTest {
    /** 使用真实 PNG 验证 Logo、签名和两种水印可进入三格式，并能由系统打开 PDF。 */
    @Test fun profileImagesAndWatermarksReachAllFormats() {
        val root = Files.createTempDirectory("record-branded").toFile()
        try {
            val image = root.resolve("signature.png")
            val bitmap = android.graphics.Bitmap.createBitmap(300, 120, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.TRANSPARENT)
                canvas.drawLine(10f, 90f, 270f, 20f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE; strokeWidth = 8f })
                image.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            for (kind in listOf(WatermarkKind.TEXT, WatermarkKind.IMAGE)) {
                val record = ExportRecord("记录", notes = "内容\n".repeat(200),
                    company = RecordCompany("Company", logo = image), person = RecordPerson("Person", avatar = image),
                    signatures = listOf(RecordSignature("Signer", "Reviewer", "2026-10-27", "Approved", image)),
                    images = listOf(RecordImage(image, "附件")),
                    template = RecordTemplate(imageColumns = 2, watermark = RecordWatermark(kind, "草稿", image, everyPage = false)))
                val files = RecordExporter.export(record, root.resolve(kind.name))
                assertEquals(3, files.size)
                ParcelFileDescriptor.open(files.first { it.extension == "pdf" }, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount > 1) }
                }
                for (file in files.filter { it.extension != "pdf" }) java.util.zip.ZipFile(file).use { zip ->
                    assertTrue(zip.entries().asSequence().any { it.name.contains("media/") })
                }
                val restored = RecordSnapshot.restore(checkNotNull(files.first().parentFile))
                assertArrayEquals(image.readBytes(), restored.signatures.single().image!!.readBytes())
                assertEquals(kind, restored.template.watermark.kind)
            }
        } finally { root.deleteRecursively() }
    }

    /** 生成同一记录的三种文件，验证 PDF 多页有效且每种产物非空。 */
    @Test fun longRecordProducesThreeReadableFiles() {
        val root = Files.createTempDirectory("record-pdf").toFile()
        try {
            val record = ExportRecord("中文记录 / Export", "2026-10-27", "长备注内容\n".repeat(300),
                rows = (0..80).map { listOf(RecordCell("项目 $it"), RecordCell("长明细\n".repeat(8))) })
            val files = RecordExporter.export(record, root)
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() > 0 })
            ParcelFileDescriptor.open(files.first { it.extension == "pdf" }, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue(renderer.pageCount > 1)
                    for (index in 0 until renderer.pageCount) renderer.openPage(index).use { assertEquals(595, it.width) }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
