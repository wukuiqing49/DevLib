package com.wkq.google.billing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 单类 Play 请求独立截止；某一类型失败不能取消另一类型已经取得的结果。 */
internal suspend fun <T> boundedBillingQuery(operation: String, block: suspend () -> Result<T>): Result<T> =
    withTimeoutOrNull(6_000L) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    } ?: Result.failure(GoogleBillingException(GoogleBillingIssue(operation, -3)))
