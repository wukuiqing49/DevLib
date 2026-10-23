package com.wkq.dev.feature.app.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.wkq.photo.archive.ZipArchiveCreator
import com.wkq.photo.archive.ZipArchiveRequest
import com.wkq.photo.imagerender.BitmapFileDecoder
import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.imagerender.WatermarkContent
import com.wkq.photo.imagerender.WatermarkPosition
import com.wkq.photo.imagerender.WatermarkRenderer
import com.wkq.photo.imagerender.WatermarkSpec
import com.wkq.photo.pdf.PdfImageExportRequest
import com.wkq.photo.pdf.PdfImageExporter
import com.wkq.photo.qrcode.QrCodeKit
import com.wkq.roof.export.excel.SimpleXlsxWriter
import com.wkq.roof.export.word.SimpleDocxWriter
import com.wkq.util.exif.ImageMetadataInspector
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** 只编排已有库；示例和产物限定在宿主可分享目录，不修改用户原图。 */
class LabOperations(private val context: Context) {
    private val directory = File(context.filesDir, "shared/testlab").apply { mkdirs() }
    private fun output(extension: String) = File(directory, "${UUID.randomUUID()}.$extension")

    fun sample(): File {
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(25, 62, 108))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 207, 185) }
                drawCircle(730f, 180f, 140f, paint)
                paint.color = Color.WHITE
                paint.textSize = 78f
                drawText("DevLib", 70f, 330f, paint)
                paint.textSize = 32f
                drawText("IMAGE / PDF / DOCUMENT", 76f, 408f, paint)
            }
            return output("jpg").also { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
        } finally { bitmap.recycle() }
    }

    fun importImage(uri: Uri): File {
        // 原样复制才能真实读取原图元数据；限制输入体积和像素，且不持有长期 SAF 权限。
        val file = output("image")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                file.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32L * 1024 * 1024) { "Image exceeds 32 MiB" }
                        out.write(buffer, 0, count)
                    }
                }
            }
            val (width, height) = BitmapFileDecoder.readOrientedDimensions(ImageSource.FileSource(file))
            require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000) { "Image exceeds 16 megapixels" }
            return file
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun metadata(file: File): ImageMetadataInspector.MetadataSummary = ImageMetadataInspector.inspect(file).getOrThrow()

    fun watermark(source: File): File = output("jpg").also {
        WatermarkRenderer.render(source, it, WatermarkContent.Text("DevLib"), WatermarkSpec(WatermarkPosition.BOTTOM_END, 0.12f, 220))
        check(it.length() > 0)
    }

    fun qr(content: String): File {
        require(content.isNotBlank() && content.length <= 500)
        val bitmap = QrCodeKit.create(content)
        try {
            check(QrCodeKit.recognize(bitmap) == content) { "QR round-trip failed" }
            return output("png").also { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        } finally { bitmap.recycle() }
    }

    fun pdf(source: File, cancelled: () -> Boolean): Pair<File, File> {
        val pdf = output("pdf")
        PdfImageExporter.create(PdfImageExportRequest(listOf(source), pdf), isCancelled = cancelled)
        val preview = output("png")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == 1)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
            }
        }
        return pdf to preview
    }

    fun docx(title: String, body: String): File = output("docx").also {
        SimpleDocxWriter.write(it, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Paragraph(title), SimpleDocxWriter.Paragraph(body))))
        ZipFile(it).use { zip -> check(zip.getEntry("word/document.xml") != null) }
    }

    fun xlsx(title: String, header: List<String>, body: List<String>): File = output("xlsx").also {
        SimpleXlsxWriter.write(it, listOf(SimpleXlsxWriter.Sheet(title, listOf(header, body).map { row -> row.map { cell -> SimpleXlsxWriter.Cell(cell) } })))
        ZipFile(it).use { zip -> check(zip.getEntry("xl/worksheets/sheet1.xml") != null) }
    }

    fun zip(source: File, cancelled: () -> Boolean): File = output("zip").also {
        ZipArchiveCreator.create(ZipArchiveRequest(listOf(source), it), isCancelled = cancelled)
        ZipFile(it).use { zip ->
            check(zip.size() == 1)
            zip.getInputStream(zip.entries().nextElement()).use { input ->
                source.inputStream().use { original ->
                    val a = ByteArray(8192)
                    val b = ByteArray(8192)
                    while (true) {
                        val count = original.read(a)
                        if (count < 0) { check(input.read() == -1); break }
                        var read = 0
                        while (read < count) { val n = input.read(b, read, count - read); check(n > 0); read += n }
                        check(a.copyOf(count).contentEquals(b.copyOf(count)))
                    }
                }
            }
        }
    }
}
