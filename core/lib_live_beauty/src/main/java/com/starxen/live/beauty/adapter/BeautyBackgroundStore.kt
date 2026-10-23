package com.starxen.live.beauty.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID

/** Blocking import: run on IO. Only this private directory is eligible for cleanup. */
class BeautyBackgroundStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "live_beauty_backgrounds")

    fun import(uri: Uri, video: Boolean, keepPaths: Set<String>, checkCancelled: () -> Unit,
        progress: (Long) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Background directory unavailable")
        val limit = if (video) 128L * 1024 * 1024 else 16L * 1024 * 1024
        if (directory.usableSpace < limit + 16L * 1024 * 1024) throw IOException("Insufficient storage")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.filter { it.isFile && it.absolutePath !in keepPaths && it.lastModified() < cutoff }
            ?.forEach { if (!it.delete()) throw IOException("Unable to clean background file") }
        val source = File(directory, "${UUID.randomUUID()}.part")
        val output = File(directory, "${UUID.randomUUID()}.${if (video) "mp4" else "png"}")
        var complete = false
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Background cannot be read")
            input.use { stream -> source.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val size = stream.read(buffer)
                    if (size < 0) break
                    total += size
                    if (total > limit) throw IOException("Background exceeds size limit")
                    target.write(buffer, 0, size)
                    progress(total)
                }
                if (total == 0L) throw IOException("Empty background")
            } }
            checkCancelled()
            if (video) {
                validateVideo(source)
                if (!source.renameTo(output)) throw IOException("Unable to save background")
            } else normalizeImage(source, output, checkCancelled)
            checkCancelled()
            complete = true
            return output
        } finally {
            source.delete()
            if (!complete) output.delete()
        }
    }

    private fun validateVideo(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IOException("Video track unavailable")
            if (video.getInteger(MediaFormat.KEY_WIDTH) !in 1..1920 ||
                video.getInteger(MediaFormat.KEY_HEIGHT) !in 1..1920) throw IOException("Video dimensions exceed limit")
        } finally { extractor.release() }
    }

    private fun normalizeImage(source: File, output: File, checkCancelled: () -> Unit) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Image cannot be decoded")
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 1920 || options.outHeight / options.inSampleSize > 1920)
            options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: throw IOException("Image cannot be decoded")
        var rotated: Bitmap? = null
        try {
            checkCancelled()
            val orientation = if (options.outMimeType == "image/jpeg")
                ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            else ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
            rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            output.outputStream().use { if (!rotated.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Unable to save image") }
        } finally {
            if (rotated !== bitmap) rotated?.recycle()
            bitmap.recycle()
        }
    }
}
