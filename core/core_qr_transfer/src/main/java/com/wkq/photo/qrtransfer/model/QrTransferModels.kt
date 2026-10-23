package com.wkq.photo.qrtransfer.model

import android.net.Uri

enum class TransferRole { SENDER, RECEIVER }

enum class TransferPhase {
    IDLE,
    PREPARING,
    WAITING_FOR_SCAN,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    AWAITING_USER_CONFIRMATION,
    WAITING_FOR_MANIFEST,
    SENDING,
    RECEIVING,
    VERIFYING,
    WAITING_FOR_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class QrTransferSession(
    val protocol: String = PROTOCOL,
    val version: Int = VERSION,
    val serviceId: String,
    val sessionId: String,
    val secret: String,
    val expiresAt: Long,
    val receiverNonce: String,
) {
    companion object {
        const val PROTOCOL = "qr-transfer"
        const val VERSION = 1
    }
}

data class TransferFile(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val relativePath: String? = null,
)

data class PreparedTransferSource(
    val uri: Uri,
    val file: TransferFile,
)

data class TransferProgress(
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentBytes: Long = 0,
    val currentTotalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) {
            0
        } else {
            ((transferredBytes.coerceIn(0L, totalBytes).toDouble() / totalBytes.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        }
}

data class TransferState(
    val role: TransferRole? = null,
    val phase: TransferPhase = TransferPhase.IDLE,
    val sessionQrContent: String? = null,
    val peerName: String? = null,
    val authenticationDigits: String? = null,
    val progress: TransferProgress = TransferProgress(),
    val files: List<TransferFile> = emptyList(),
    val completedFiles: List<String> = emptyList(),
    val error: TransferError? = null,
)

enum class TransferError {
    INVALID_QR,
    EXPIRED_QR,
    UNSUPPORTED_DEVICE,
    PERMISSION_DENIED,
    ADVERTISING_FAILED,
    DISCOVERY_FAILED,
    ENDPOINT_NOT_FOUND,
    AUTHENTICATION_FAILED,
    CONNECTION_FAILED,
    FILE_READ_FAILED,
    STORAGE_FAILED,
    INTEGRITY_FAILED,
    TRANSFER_FAILED,
    DISCONNECTED,
}
