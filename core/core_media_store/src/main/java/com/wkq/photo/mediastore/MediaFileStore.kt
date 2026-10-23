package com.wkq.photo.mediastore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.operation.getOrThrow
import com.wkq.photo.core.utils.AtomicFileOutput
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed interface MediaInputSource {
    data class FileSource(val file: File) : MediaInputSource
    data class UriSource(val uri: Uri) : MediaInputSource
    data class ParcelFileDescriptorSource(val descriptor: ParcelFileDescriptor) : MediaInputSource
}

/** 跨功能的临时文件与系统媒体库导出能力，不持有页面状态。 */
object MediaFileStore {

    fun copyToCache(context: Context, uri: Uri, prefix: String): File {
        return materialize(context, MediaInputSource.UriSource(uri), prefix).getOrThrow()
    }

    fun materialize(
        context: Context,
        source: MediaInputSource,
        prefix: String,
        extensionHint: String? = null,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { operation ->
        operation.report(OperationStage.VALIDATING, 0, 1)
        if (source is MediaInputSource.FileSource && !source.file.isFile) {
            throw java.io.FileNotFoundException("Input file does not exist: ${source.file.absolutePath}")
        }
        val extension = resolveExtension(context, source, extensionHint)
        val target = File(context.cacheDir, "media-input").apply { mkdirs() }
            .resolve("${safeName(prefix)}_${UUID.randomUUID()}.$extension")
        val knownInputBytes = inputSize(source)
        if (knownInputBytes > budget.maxInputBytes) {
            throw OperationException(
                OperationErrorCode.BUDGET_EXCEEDED,
                "Input file exceeds ${budget.maxInputBytes} bytes: $knownInputBytes",
            )
        }
        var copiedBytes = 0L
        AtomicFileOutput.write(target) { temporary ->
            openInputStream(context, source).buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        operation.checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > budget.maxInputBytes) {
                            throw OperationException(
                                OperationErrorCode.BUDGET_EXCEEDED,
                                "Input file exceeds ${budget.maxInputBytes} bytes",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        operation.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            target,
            OperationStatistics(
                durationMillis = operation.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = copiedBytes,
                outputBytes = target.length()
            )
        )
    }

    fun newOutput(context: Context, prefix: String, extension: String): File {
        val directory = File(context.cacheDir, "media-output").apply { mkdirs() }
        return File(directory, "${safeName(prefix)}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${extension.removePrefix(".")}")
    }

    fun newWorkingFile(context: Context, prefix: String, extension: String): File {
        val directory = File(context.cacheDir, "media-work").apply { mkdirs() }
        val safePrefix = safeName(prefix).padEnd(3, '_').take(64)
        return File.createTempFile("${safePrefix}_", ".${extension.removePrefix(".")}", directory)
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "BatchPhoto" }

    private fun resolveExtension(
        context: Context,
        source: MediaInputSource,
        extensionHint: String?
    ): String {
        val candidate = extensionHint ?: when (source) {
            is MediaInputSource.FileSource -> source.file.extension
            is MediaInputSource.UriSource -> context.contentResolver.getType(source.uri)?.substringAfter('/')
            is MediaInputSource.ParcelFileDescriptorSource -> null
        }
        return candidate
            ?.replace(Regex("[^a-zA-Z0-9]"), "")
            ?.ifBlank { "bin" }
            ?: "bin"
    }

    private fun openInputStream(context: Context, source: MediaInputSource): InputStream = when (source) {
        is MediaInputSource.FileSource -> source.file.inputStream()
        is MediaInputSource.UriSource -> context.contentResolver.openInputStream(source.uri)
            ?: throw java.io.FileNotFoundException("Unable to read URI: ${source.uri}")
        is MediaInputSource.ParcelFileDescriptorSource -> ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.dup(source.descriptor.fileDescriptor)
        )
    }

    private fun inputSize(source: MediaInputSource): Long = when (source) {
        is MediaInputSource.FileSource -> source.file.length()
        is MediaInputSource.UriSource -> 0L
        is MediaInputSource.ParcelFileDescriptorSource -> source.descriptor.statSize.coerceAtLeast(0L)
    }
}

enum class MediaExportCollection { IMAGE, DOWNLOAD }

data class MediaExportRequest(
    val source: File,
    val displayName: String = source.name,
    val mimeType: String = MediaMimeTypes.forFile(source),
    val collection: MediaExportCollection = MediaMimeTypes.collectionFor(mimeType),
    val relativePath: String? = null,
    /** 产生该文件的功能类型，例如 pdf、zip_encrypt、batch_resize。 */
    val operationType: String = "unknown",
    /** 可选的业务任务 ID，后续页面可用它关联任务和产物。 */
    val taskId: String? = null,
    val archiveInternally: Boolean = true
)

/** 将输出发布为可持久访问的 content URI，并在失败时清理未完成的条目。 */
object MediaStorePublisher {

    /** 批量发布具备补偿删除语义，避免中途失败后相册残留半批结果。 */
    fun publishAllOrRollback(
        context: Context,
        requests: List<MediaExportRequest>,
        beforePublish: (index: Int) -> Unit = {},
    ): List<Uri> {
        val published = mutableListOf<Uri>()
        try {
            requests.forEachIndexed { index, request ->
                beforePublish(index)
                published += publish(context, request)
            }
            return published
        } catch (error: Exception) {
            published.asReversed().forEach { uri ->
                runCatching { deletePublished(context, uri) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    fun deletePublished(context: Context, uri: Uri) {
        context.contentResolver.delete(uri, null, null)
        InternalArtifactStore.deleteByPublishedUri(context, uri.toString())
    }

    fun publish(context: Context, request: MediaExportRequest): Uri {
        request.taskId?.let { taskId ->
            val existing = InternalArtifactStore.findPublished(context, taskId, safeDisplayName(request.displayName))
            val existingUri = existing?.mediaStoreUri?.let(Uri::parse)
            if (existingUri != null) {
                val stillExists = runCatching {
                    context.contentResolver.query(
                        existingUri,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        cursor.moveToFirst() && cursor.getLong(1) > 0L
                    } == true
                }.getOrDefault(false)
                if (stillExists) return existingUri
                InternalArtifactStore.markUnpublished(context, existing.id)
            }
        }
        MediaOutputVerifier.verifySource(request.source, request.mimeType)
        val artifact = if (request.archiveInternally) {
            InternalArtifactStore.archive(context, request)
        } else {
            null
        }
        var publishedUri: Uri? = null
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishScoped(context, request)
            else publishLegacy(context, request)
            publishedUri = uri
            MediaOutputVerifier.verifyPublished(
                context.contentResolver,
                uri,
                request.source.length(),
                request.mimeType,
            )
            artifact?.let { InternalArtifactStore.markPublished(context, it.id, uri.toString()) }
            uri
        } catch (error: Exception) {
            // 内部归档保留为未发布记录，便于后续重试和问题追踪。
            publishedUri?.let { uri ->
                runCatching { deletePublished(context, uri) }
                    .onFailure(error::addSuppressed)
            }
            artifact?.let { InternalArtifactStore.markUnpublished(context, it.id) }
            throw error
        }
    }

    private fun publishScoped(context: Context, request: MediaExportRequest): Uri {
        val resolver = context.contentResolver
        val displayName = safeDisplayName(request.displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, request.relativePath ?: defaultRelativePath(request.collection))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri(request.collection), values)
            ?: error("Unable to create media export")
        try {
            copyToResolver(resolver, request.source, uri)
            check(
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1
            ) { "Unable to finalize media export: $uri" }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, request: MediaExportRequest): Uri {
        val displayName = safeDisplayName(request.displayName)
        val directory = Environment.getExternalStoragePublicDirectory(
            if (request.collection == MediaExportCollection.IMAGE) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        ).resolve((request.relativePath ?: defaultRelativePath(request.collection)).substringAfterLast('/')).apply { mkdirs() }
        val target = File(directory, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collectionUri(request.collection), values)
            ?: error("Unable to create legacy media export")
        try {
            copyToResolver(resolver, request.source, uri)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(request.mimeType), null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun collectionUri(collection: MediaExportCollection): Uri = when (collection) {
        MediaExportCollection.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaExportCollection.DOWNLOAD -> MediaStore.Files.getContentUri("external")
    }

    private fun copyToResolver(resolver: ContentResolver, source: File, uri: Uri) {
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().buffered().use { input ->
                copyExactly(input, output, source.length())
            }
        } ?: error("Unable to open media export")
    }

    private fun defaultRelativePath(collection: MediaExportCollection): String = when (collection) {
        MediaExportCollection.IMAGE -> "Pictures/Batch Photo"
        MediaExportCollection.DOWNLOAD -> "Download/Batch Photo"
    }

    private fun safeDisplayName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "BatchPhoto" }
}

internal fun copyExactly(input: InputStream, output: OutputStream, expectedBytes: Long) {
    val copied = input.copyTo(output)
    if (copied != expectedBytes) {
        throw IOException("Copied $copied bytes, expected $expectedBytes bytes")
    }
}

object MediaMimeTypes {
    fun forFile(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun collectionFor(mimeType: String): MediaExportCollection =
        if (mimeType.startsWith("image/")) MediaExportCollection.IMAGE else MediaExportCollection.DOWNLOAD
}
