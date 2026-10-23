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
import java.io.File

object ZipArchiveOperations {
    fun createEncrypted(
        request: ZipArchiveRequest,
        password: CharArray,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = createEncryptedInternal(
        entries = request.sourceFiles.map(::ZipArchiveEntry),
        outputFile = request.outputFile,
        password = password,
        budget = budget,
        cancellation = cancellation,
        onProgress = onProgress,
    )

    fun createEncrypted(
        entries: List<ZipArchiveEntry>,
        outputFile: File,
        password: CharArray,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = createEncryptedInternal(
        entries = entries,
        outputFile = outputFile,
        password = password,
        budget = budget,
        cancellation = cancellation,
        onProgress = onProgress,
    )

    private fun createEncryptedInternal(
        entries: List<ZipArchiveEntry>,
        outputFile: File,
        password: CharArray,
        budget: OperationBudget,
        cancellation: OperationCancellation,
        onProgress: (OperationProgress) -> Unit,
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        require(entries.isNotEmpty()) { "Archive must contain at least one file" }
        require(outputFile.extension.equals("zip", true)) { "Archive output must use .zip" }
        if (entries.size > budget.maxPages) {
            throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Archive contains too many files")
        }
        context.report(OperationStage.VALIDATING, 0, entries.size)
        val output = ZipArchiveCreator.createEncrypted(
            entries = entries,
            outputFile = outputFile,
            password = password,
            onProgress = { progress ->
                context.checkActive()
                context.report(OperationStage.PROCESSING, progress.completedFiles, progress.totalFiles)
            },
            isCancelled = cancellation::isCancelled,
        )
        context.report(OperationStage.COMPLETED, entries.size, entries.size)
        OperationResult.Success(
            output,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = entries.size,
                outputCount = 1,
                inputBytes = entries.sumOf { it.sourceFile.length() },
                outputBytes = output.length(),
            ),
        )
    }
}
