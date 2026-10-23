package com.wkq.nas.transfer

data class StorageWriteTestResult(val error: TransferError? = null, val remainingPath: String = "")
