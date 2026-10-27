package com.wkq.photo.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.wkq.photo.imagerender.ImageSource
import java.nio.file.Files

class PdfImageExportRequestTest {

    /** 网格边距与间隔的极大值不能溢出成可绘制区域。 */
    @org.junit.Test fun rejectsOverflowingGridParameters() {
        org.junit.Assert.assertTrue(runCatching {
            PdfGridOperationRequest(emptyList(), PdfOutputTarget.FileTarget(java.io.File("test.pdf")), marginPoints = Int.MAX_VALUE)
        }.isFailure)
        org.junit.Assert.assertTrue(runCatching {
            PdfGridOperationRequest(emptyList(), PdfOutputTarget.FileTarget(java.io.File("test.pdf")), gapPoints = Int.MAX_VALUE)
        }.isFailure)
        org.junit.Assert.assertTrue(runCatching { PdfPageConfig(marginPoints = Int.MAX_VALUE) }.isFailure)
    }


    @Test
    fun keepsInputOrderAndAcceptsValidPageConfiguration() {
        val root = Files.createTempDirectory("pdf-request-test").toFile()
        val first = root.resolve("first.jpg").apply { writeText("first") }
        val second = root.resolve("second.jpg").apply { writeText("second") }

        val request = PdfImageExportRequest(
            imageFiles = listOf(first, second),
            outputFile = root.resolve("result.pdf"),
            pageSize = PdfPageSize.LETTER,
            landscape = true,
            marginPoints = 32
        )

        assertEquals(listOf(first, second), request.imageFiles)
        assertEquals(PdfPageSize.LETTER, request.pageSize)
    }

    @Test
    fun rejectsMarginThatConsumesThePage() {
        val root = Files.createTempDirectory("pdf-margin-test").toFile()
        val source = root.resolve("source.jpg").apply { writeText("source") }

        try {
            PdfImageExportRequest(
                imageFiles = listOf(source),
                outputFile = root.resolve("result.pdf"),
                pageSize = PdfPageSize.A4,
                marginPoints = 298
            )
            fail("Expected invalid PDF margin to fail")
        } catch (_: IllegalArgumentException) {
            // 预期参数校验失败。
        }
    }

    @Test
    fun supportsCommercialPageAndRenderingOptions() {
        val options = PdfRenderOptions(
            customPageSize = PdfPageDimensions(720, 720),
            scaleMode = PdfImageScaleMode.FILL_CROP,
            backgroundColor = 0xFFEEEEEE.toInt(),
            showPageNumbers = true,
            pageNumberColor = 0xFF333333.toInt(),
            rasterScale = 4
        )

        assertEquals(720, options.customPageSize?.widthPoints)
        assertEquals(PdfImageScaleMode.FILL_CROP, options.scaleMode)
        assertTrue(options.showPageNumbers)
        assertTrue(PdfPageSize.entries.containsAll(listOf(PdfPageSize.A3, PdfPageSize.A5, PdfPageSize.LEGAL)))
    }

    @Test
    fun rejectsExcessiveRasterScale() {
        try {
            PdfRenderOptions(rasterScale = 7)
            fail("Expected invalid PDF raster scale to fail")
        } catch (_: IllegalArgumentException) {
            // 预期参数校验失败。
        }
    }

    @Test
    fun modelsSingleAndGridDocumentsWithSharedPageDecoration() {
        val root = Files.createTempDirectory("pdf-document-request").toFile()
        val source = root.resolve("source.jpg").apply { writeText("source") }
        val request = PdfDocumentRequest(
            imageSources = listOf(ImageSource.FileSource(source)),
            outputTarget = PdfOutputTarget.FileTarget(root.resolve("result.pdf")),
            layout = PdfDocumentLayout.Grid(columns = 3, rows = 2, gapPoints = 8),
            page = PdfPageConfig(
                size = PdfPageSize.LETTER,
                orientation = PdfPageOrientation.LANDSCAPE,
                marginPoints = 30,
            ),
            decoration = PdfPageDecoration(
                header = "Catalog",
                footer = "Confidential",
                showPageNumbers = true,
            ),
        )

        assertEquals(PdfPageOrientation.LANDSCAPE, request.page.orientation)
        assertEquals("Catalog", request.decoration.header)
        assertTrue(request.decoration.showPageNumbers)
    }
}
