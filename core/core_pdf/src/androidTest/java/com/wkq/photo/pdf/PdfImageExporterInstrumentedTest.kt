package com.wkq.photo.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.imagerender.ImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImageExporterInstrumentedTest {

    @Test
    fun createsReadablePdfWithOnePagePerImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("pdf-real-test").apply { mkdirs() }
        val sources = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            root.resolve("source-$index.jpg").also { file ->
                Bitmap.createBitmap(80 + index * 20, 60, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(color)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bitmap.recycle()
                }
            }
        }
        val output = root.resolve("result.pdf").apply { delete() }

        val result = PdfImageExporter.execute(
            PdfOperationRequest(
                imageSources = sources.map { ImageSource.FileSource(it) },
                outputTarget = PdfOutputTarget.FileTarget(output)
            )
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        assertTrue(output.inputStream().use { input -> ByteArray(5).also(input::read).contentEquals("%PDF-".toByteArray()) })
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(2, renderer.pageCount) }
        }
        assertEquals(2, (result as OperationResult.Success).statistics.pageCount)
    }

    @Test
    fun rejectsPageBudgetBeforeCreatingOutput() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("pdf-page-budget-test").apply { mkdirs() }
        val source = root.resolve("source.jpg").apply { writeText("not decoded") }
        val output = root.resolve("result.pdf").apply { delete() }

        val result = PdfImageExporter.execute(
            request = PdfOperationRequest(
                imageSources = listOf(ImageSource.FileSource(source), ImageSource.FileSource(source)),
                outputTarget = PdfOutputTarget.FileTarget(output)
            ),
            budget = OperationBudget(maxWidth = 4_096, maxHeight = 4_096, maxTotalPixels = 20_000_000, maxPages = 1, timeoutMillis = 10_000)
        )

        assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
        assertTrue(!output.exists())
    }

    @Test
    fun createsGridPdfWithFourImagesPerPage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("pdf-grid-test").apply { mkdirs() }
        val source = root.resolve("source.png").also { file ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.GREEN)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val output = root.resolve("grid.pdf").apply { delete() }

        val result = PdfImageExporter.executeDocument(
            PdfDocumentRequest(
                imageSources = List(5) { ImageSource.FileSource(source) },
                outputTarget = PdfOutputTarget.FileTarget(output),
                layout = PdfDocumentLayout.Grid(columns = 2, rows = 2),
                page = PdfPageConfig(
                    orientation = PdfPageOrientation.LANDSCAPE,
                    marginPoints = 30,
                ),
                decoration = PdfPageDecoration(
                    header = "Product labels",
                    footer = "Batch Photo",
                    showPageNumbers = true,
                ),
            )
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(2, renderer.pageCount) }
        }
        assertEquals(2, (result as OperationResult.Success).statistics.pageCount)
    }
}
