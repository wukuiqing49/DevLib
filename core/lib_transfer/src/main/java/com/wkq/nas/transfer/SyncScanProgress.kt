package com.wkq.nas.transfer

enum class SyncScanStage { LISTING, VERIFYING }

/** 仅传计数，不携带文件名或凭据；total为0表示尚在枚举。 */
data class SyncScanProgress(val side: Int, val stage: SyncScanStage, val completed: Int, val total: Int)
