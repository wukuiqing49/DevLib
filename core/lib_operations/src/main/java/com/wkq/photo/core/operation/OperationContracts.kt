package com.wkq.photo.core.operation

import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max

enum class OperationErrorCode {
    INVALID_INPUT,
    SOURCE_NOT_FOUND,
    UNSUPPORTED_FORMAT,
    BUDGET_EXCEEDED,
    CANCELLED,
    OUTPUT_CONFLICT,
    IO_ERROR,
    ENCODE_FAILED,
    NATIVE_FAILED,
    INTERNAL_ERROR
}

data class OperationBudget(
    val maxWidth: Int = DEFAULT_MAX_DIMENSION,
    val maxHeight: Int = DEFAULT_MAX_DIMENSION,
    val maxTotalPixels: Long = DEFAULT_MAX_TOTAL_PIXELS,
    val maxPages: Int = DEFAULT_MAX_PAGES,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
) {
    init {
        require(maxWidth > 0)
        require(maxHeight > 0)
        require(maxTotalPixels > 0)
        require(maxPages > 0)
        require(timeoutMillis > 0)
        require(maxInputBytes > 0)
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 32_768
        const val DEFAULT_MAX_TOTAL_PIXELS = 512L * 1024L * 1024L
        const val DEFAULT_MAX_PAGES = 500
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_MAX_INPUT_BYTES = 256L * 1024L * 1024L

        val DEFAULT = OperationBudget()
    }
}

enum class OperationStage {
    VALIDATING,
    DECODING,
    PROCESSING,
    ENCODING,
    PUBLISHING,
    COMPLETED
}

data class OperationProgress(
    val stage: OperationStage,
    val completedUnits: Int,
    val totalUnits: Int
) {
    init {
        require(completedUnits >= 0)
        require(totalUnits >= 0)
        require(completedUnits <= totalUnits || totalUnits == 0)
    }

    val fraction: Float
        get() = if (totalUnits == 0) 0f else completedUnits.toFloat() / totalUnits
}

fun interface OperationCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = OperationCancellation { false }
    }
}

data class OperationStatistics(
    val durationMillis: Long,
    val inputCount: Int = 0,
    val outputCount: Int = 0,
    val inputBytes: Long = 0,
    val outputBytes: Long = 0,
    val inputPixels: Long = 0,
    val outputPixels: Long = 0,
    val pageCount: Int = 0,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null
) {
    init {
        require(durationMillis >= 0)
        require(inputCount >= 0 && outputCount >= 0)
        require(inputBytes >= 0 && outputBytes >= 0)
        require(inputPixels >= 0 && outputPixels >= 0)
        require(pageCount >= 0)
    }
}

sealed interface OperationResult<out T> {
    data class Success<T>(
        val value: T,
        val statistics: OperationStatistics
    ) : OperationResult<T>

    data class Failure(
        val code: OperationErrorCode,
        val message: String,
        val cause: Throwable? = null,
        val statistics: OperationStatistics? = null
    ) : OperationResult<Nothing>
}

class OperationException(
    val code: OperationErrorCode,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class OperationContext internal constructor(
    val budget: OperationBudget,
    private val cancellation: OperationCancellation,
    private val onProgress: (OperationProgress) -> Unit,
    private val startedNanos: Long = System.nanoTime()
) {
    private val timeoutNanos = budget.timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L

    fun checkActive() {
        if (cancellation.isCancelled()) {
            throw OperationException(OperationErrorCode.CANCELLED, "Operation cancelled")
        }
        if (System.nanoTime() - startedNanos >= timeoutNanos) {
            throw OperationException(OperationErrorCode.BUDGET_EXCEEDED, "Operation time budget exceeded")
        }
    }

    fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            throw OperationException(OperationErrorCode.UNSUPPORTED_FORMAT, "Image dimensions are unavailable")
        }
        if (width > budget.maxWidth || height > budget.maxHeight) {
            throw OperationException(
                OperationErrorCode.BUDGET_EXCEEDED,
                "Image dimensions ${width}x${height} exceed ${budget.maxWidth}x${budget.maxHeight}"
            )
        }
    }

    fun checkTotalPixels(totalPixels: Long) {
        if (totalPixels < 0 || totalPixels > budget.maxTotalPixels) {
            throw OperationException(
                OperationErrorCode.BUDGET_EXCEEDED,
                "Pixel budget exceeded: $totalPixels > ${budget.maxTotalPixels}"
            )
        }
    }

    fun checkPages(pageCount: Int) {
        if (pageCount > budget.maxPages) {
            throw OperationException(
                OperationErrorCode.BUDGET_EXCEEDED,
                "Page budget exceeded: $pageCount > ${budget.maxPages}"
            )
        }
    }

    fun report(stage: OperationStage, completedUnits: Int, totalUnits: Int) {
        checkActive()
        onProgress(OperationProgress(stage, completedUnits, totalUnits))
    }

    fun elapsedMillis(): Long = max(0L, (System.nanoTime() - startedNanos) / 1_000_000L)
}

object OperationRunner {
    fun <T> run(
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
        block: (OperationContext) -> OperationResult.Success<T>
    ): OperationResult<T> {
        val context = OperationContext(budget, cancellation, onProgress)
        return try {
            context.checkActive()
            block(context)
        } catch (error: OperationException) {
            logOperationFailure("operation failed: code=${error.code} message=${error.message.orEmpty()}", error)
            OperationResult.Failure(
                code = error.code,
                message = error.message.orEmpty(),
                cause = error.cause,
                statistics = OperationStatistics(durationMillis = context.elapsedMillis())
            )
        } catch (error: FileNotFoundException) {
            logOperationFailure("operation failed: code=${OperationErrorCode.SOURCE_NOT_FOUND} message=${error.message.orEmpty()}", error)
            OperationResult.Failure(OperationErrorCode.SOURCE_NOT_FOUND, error.message.orEmpty(), error)
        } catch (error: IllegalArgumentException) {
            logOperationFailure("operation failed: code=${OperationErrorCode.INVALID_INPUT} message=${error.message.orEmpty()}", error)
            OperationResult.Failure(OperationErrorCode.INVALID_INPUT, error.message.orEmpty(), error)
        } catch (error: IOException) {
            logOperationFailure("operation failed: code=${OperationErrorCode.IO_ERROR} message=${error.message.orEmpty()}", error)
            OperationResult.Failure(OperationErrorCode.IO_ERROR, error.message.orEmpty(), error)
        } catch (error: Exception) {
            logOperationFailure("operation failed: code=${OperationErrorCode.INTERNAL_ERROR} message=${error.message.orEmpty()}", error)
            OperationResult.Failure(OperationErrorCode.INTERNAL_ERROR, error.message.orEmpty(), error)
        }
    }
}

private const val OPERATION_LOG_TAG = "PhotoOperation"

private fun logOperationFailure(message: String, error: Throwable) {
    // JVM 单元测试没有 Android Log 实现，日志失败不能覆盖原始操作结果。
    runCatching { Log.e(OPERATION_LOG_TAG, message, error) }
}

fun <T> OperationResult<T>.getOrThrow(): T = when (this) {
    is OperationResult.Success -> value
    is OperationResult.Failure -> throw OperationException(code, message, cause)
}

enum class ImageOutputFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    TIFF("tiff")
}

enum class MetadataPolicy {
    PRESERVE,
    STRIP_SENSITIVE,
    STRIP_ALL
}

data class ImageEncodeOptions(
    val format: ImageOutputFormat = ImageOutputFormat.JPEG,
    val quality: Int = 94,
    val metadataPolicy: MetadataPolicy = MetadataPolicy.STRIP_SENSITIVE
) {
    init { require(quality in 1..100) }
}
