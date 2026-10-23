package com.wkq.dev.demo.common

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object DemoFiles {
    fun output(context: Context, extension: String): File = File(context.filesDir, "shared/demo/${UUID.randomUUID()}.$extension").apply { parentFile!!.mkdirs() }
    suspend fun importFiles(context: Context, uris: List<Uri>): List<File> {
        require(uris.size <= 20)
        val created = mutableListOf<File>()
        var aggregate = 0L
        try {
            uris.forEach { uri ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val extension = name?.substringAfterLast('.', "bin")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                val file = output(context, extension); created += file
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            total += n; aggregate += n
                            require(total <= 128L * 1024 * 1024 && aggregate <= 256L * 1024 * 1024)
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            return created
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }
    fun sample(context: Context): File {
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(19, 42, 73))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(57, 207, 187) }
            canvas.drawCircle(740f, 180f, 145f, paint)
            paint.color = Color.WHITE; paint.textSize = 80f; canvas.drawText("DevLib", 65f, 350f, paint)
            return output(context, "jpg").also { it.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) } }
        } finally { bitmap.recycle() }
    }
    fun pdfPreview(context: Context, file: File): File = output(context, "png").also { preview ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount > 0)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
