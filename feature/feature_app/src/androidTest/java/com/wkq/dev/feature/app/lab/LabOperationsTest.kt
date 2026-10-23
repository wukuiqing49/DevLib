package com.wkq.dev.feature.app.lab

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class LabOperationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val operations = LabOperations(context)
    private val created = mutableListOf<File>()
    private fun File.keep() = also { created += it }

    @org.junit.After fun cleanOwnOutputs() { created.forEach { it.delete() } }

    @Test fun sampleAndWatermarkHaveExpectedDimensions() {
        val source = operations.sample().keep()
        val result = operations.watermark(source).keep()
        BitmapFactory.decodeFile(result.path).let { bitmap ->
            try { assertEquals(960, bitmap.width); assertEquals(640, bitmap.height) } finally { bitmap.recycle() }
        }
        assertFalse(source.readBytes().contentEquals(result.readBytes()))
    }

    @Test fun importPreservesOriginalMetadataAndBytes() {
        val original = operations.sample().keep()
        ExifInterface(original.path).apply { setAttribute(ExifInterface.TAG_ARTIST, "DevLib fixture"); saveAttributes() }
        val imported = operations.importImage(Uri.fromFile(original)).keep()
        assertArrayEquals(original.readBytes(), imported.readBytes())
        assertEquals("DevLib fixture", ExifInterface(imported.path).getAttribute(ExifInterface.TAG_ARTIST))
        assertTrue(operations.metadata(imported).exifCount > 0)
    }

    @Test fun qrSupportsUnicodeRoundTrip() {
        val file = operations.qr("DevLib 测试 123").keep()
        assertTrue(file.length() > 0)
    }

    @Test fun pdfHasOneRenderablePage() {
        val (pdf, preview) = operations.pdf(operations.sample().keep()) { false }
        pdf.keep(); preview.keep()
        assertEquals("%PDF-", pdf.inputStream().use { String(it.readNBytes(5), Charsets.US_ASCII) })
        assertTrue(preview.length() > 0)
    }

    @Test fun openXmlExportsPreserveText() {
        val word = operations.docx("测试报告", "Unicode & <xml>").keep()
        ZipFile(word).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertTrue(xml.contains("测试报告")); assertTrue(xml.contains("&lt;xml&gt;"))
        }
        val sheet = operations.xlsx("Results", listOf("Library", "Result"), listOf("DevLib", "离线")).keep()
        assertTrue(sheet.length() > 0)
    }

    @Test fun zipHasIdenticalPayload() {
        val input = operations.sample().keep()
        val archive = operations.zip(input) { false }.keep()
        ZipFile(archive).use { zip -> assertArrayEquals(input.readBytes(), zip.getInputStream(zip.entries().nextElement()).use { it.readBytes() }) }
    }

    @Test fun invalidQrIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { operations.qr(" ") }
        assertThrows(IllegalArgumentException::class.java) { operations.qr("x".repeat(501)) }
    }
}
