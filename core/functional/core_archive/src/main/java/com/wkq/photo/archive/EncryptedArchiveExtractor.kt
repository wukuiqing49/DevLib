package com.wkq.photo.archive

import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import net.lingala.zip4j.ZipFile
import java.io.File
import java.util.UUID

data class EncryptedArchiveExtractRequest(
    val archive: File,
    val outputDirectory: File,
    val password: CharArray,
) {
    init {
        require(archive.isFile)
        require(archive.extension.equals("zip", true))
        require(password.size in MIN_ZIP_PASSWORD_LENGTH..MAX_ZIP_PASSWORD_LENGTH)
    }
}

data class EncryptedArchiveExtractOutput(
    val files: List<File>,
    val totalBytes: Long,
)

/** AES ZIP 解密入口，限制条目数量、解压大小并阻止路径穿越。 */
object EncryptedArchiveExtractor {
    private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L

    /** 在隔离目录解压并完整验证路径、大小和取消状态，成功后一次发布。 */
    fun extract(
        request: EncryptedArchiveExtractRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<EncryptedArchiveExtractOutput> = OperationRunner.run(
        budget,
        cancellation,
        onProgress,
    ) { context ->
        val password = request.password.copyOf()
        val staging = File(
            request.outputDirectory.parentFile ?: request.outputDirectory,
            ".${request.outputDirectory.name}.extract-${UUID.randomUUID()}",
        )
        try {
            context.report(OperationStage.VALIDATING, 0, 1)
            if (request.archive.length() > budget.maxInputBytes) {
                throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive input exceeds byte budget")
            }
            val archive = ZipFile(request.archive, password)
            val headers = archive.fileHeaders.filterNot { it.isDirectory }
            if (headers.isEmpty()) {
                throw OperationException(OperationErrorCode.INVALID_INPUT, "Archive contains no files")
            }
            if (headers.size > budget.maxPages) {
                throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive contains too many files")
            }
            if (headers.any { !it.isEncrypted }) {
                throw OperationException(OperationErrorCode.INVALID_INPUT, "Archive contains unencrypted files")
            }
            var declaredTotal = 0L
            headers.forEach { header ->
                if (header.uncompressedSize < 0 || header.uncompressedSize > MAX_ENTRY_BYTES) {
                    throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive entry is too large")
                }
                declaredTotal += header.uncompressedSize
                if (declaredTotal > MAX_TOTAL_BYTES) {
                    throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive output is too large")
                }
            }
            val storageRoot = request.outputDirectory.parentFile ?: request.outputDirectory
            val availableBytes = storageRoot.usableSpace
            val requiredBytes = declaredTotal.coerceAtMost(Long.MAX_VALUE - STORAGE_RESERVE_BYTES) +
                STORAGE_RESERVE_BYTES
            if (availableBytes > 0L && availableBytes < requiredBytes) {
                throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Not enough storage to extract archive")
            }
            check(staging.mkdirs()) { "Unable to create extraction directory" }
            val root = staging.canonicalFile
            val outputs = mutableListOf<File>()
            var written = 0L
            val seenPaths = hashSetOf<String>()
            headers.forEachIndexed { index, header ->
                context.checkActive()
                val normalizedName = header.fileName.replace('\\', '/')
                if (normalizedName.startsWith('/') || normalizedName.contains(':') ||
                    normalizedName.contains('\u0000') || normalizedName.split('/').any { it == "." || it == ".." }) {
                    throw OperationException(OperationErrorCode.INVALID_INPUT, "Invalid archive entry path")
                }
                val target = File(root, normalizedName).canonicalFile
                if (!seenPaths.add(target.path)) {
                    throw OperationException(OperationErrorCode.OUTPUT_CONFLICT, "Duplicate archive entry path")
                }
                if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                    throw OperationException(OperationErrorCode.INVALID_INPUT, "Archive entry escapes output directory")
                }
                target.parentFile?.mkdirs()
                archive.getInputStream(header).buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            context.checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            written += count
                            if (entryBytes > MAX_ENTRY_BYTES || written > MAX_TOTAL_BYTES) {
                                throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive output is too large")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                outputs += target
                context.report(OperationStage.PROCESSING, index + 1, headers.size)
            }
            context.checkActive()
            if (request.outputDirectory.exists()) {
                if (!request.outputDirectory.isDirectory || request.outputDirectory.listFiles()?.isNotEmpty() == true) {
                    throw OperationException(OperationErrorCode.OUTPUT_CONFLICT, "Extraction directory is not empty")
                }
                check(request.outputDirectory.delete()) { "Unable to prepare extraction directory" }
            }
            if (!staging.renameTo(request.outputDirectory)) {
                throw OperationException(OperationErrorCode.IO_ERROR, "Unable to publish extracted files")
            }
            val published = outputs.map { file ->
                File(request.outputDirectory, file.relativeTo(staging).path)
            }
            context.report(OperationStage.COMPLETED, headers.size, headers.size)
            OperationResult.Success(
                EncryptedArchiveExtractOutput(published, written),
                OperationStatistics(
                    durationMillis = context.elapsedMillis(),
                    inputCount = 1,
                    outputCount = published.size,
                    inputBytes = request.archive.length(),
                    outputBytes = written,
                ),
            )
        } finally {
            password.fill('\u0000')
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
}
