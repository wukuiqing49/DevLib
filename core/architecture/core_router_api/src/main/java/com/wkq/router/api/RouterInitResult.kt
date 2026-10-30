package com.wkq.router.api

data class RouterInitResult(
    val successCount: Int,
    val failureCount: Int,
    val initialized: Boolean
) {
    val success: Boolean
        get() = initialized && failureCount == 0
}
