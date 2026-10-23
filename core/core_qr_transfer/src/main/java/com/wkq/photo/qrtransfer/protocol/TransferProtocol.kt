package com.wkq.photo.qrtransfer.protocol

import com.google.gson.Gson
import com.wkq.photo.qrtransfer.model.QrTransferSession
import com.wkq.photo.qrtransfer.model.TransferFile
import java.nio.charset.StandardCharsets

enum class TransferMessageType {
    AUTH_PROOF,
    HELLO,
    MANIFEST_BEGIN,
    MANIFEST_CHUNK,
    MANIFEST_END,
    TRANSFER_READY,
    FILE_BINDING,
    TRANSFER_CANCEL,
    TRANSFER_COMPLETE,
    TRANSFER_FAILED,
    RECEIVE_CONFIRMED,
}

data class TransferMessage(
    val type: TransferMessageType,
    val sessionId: String,
    val transferId: String? = null,
    val sequence: Int? = null,
    val totalChunks: Int? = null,
    val totalFiles: Int? = null,
    val totalBytes: Long? = null,
    val files: List<TransferFile>? = null,
    val fileId: String? = null,
    val payloadId: Long? = null,
    val role: String? = null,
    val proof: String? = null,
    val deviceName: String? = null,
    val reason: String? = null,
)

object QrSessionCodec {
    private val gson = Gson()

    fun encode(session: QrTransferSession): String = gson.toJson(session)

    fun decode(value: String, now: Long = System.currentTimeMillis()): QrTransferSession {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 2_048) { "QR payload is too large" }
        val session = gson.fromJson(value, QrTransferSession::class.java)
            ?: throw IllegalArgumentException("Missing QR session")
        require(session.protocol == QrTransferSession.PROTOCOL)
        require(session.version == QrTransferSession.VERSION)
        require(session.serviceId.isNotBlank() && session.serviceId.length <= 160)
        require(session.sessionId.isNotBlank() && session.secret.isNotBlank() && session.receiverNonce.isNotBlank())
        require(session.expiresAt > now) { "QR session expired" }
        return session
    }
}

object TransferMessageCodec {
    const val MAX_BYTES = 32 * 1024
    const val SAFE_BYTES = 24 * 1024
    private val gson = Gson()

    fun encode(message: TransferMessage): ByteArray = gson.toJson(message)
        .toByteArray(StandardCharsets.UTF_8)
        .also { require(it.size <= MAX_BYTES) { "Control message exceeds Nearby BYTES limit" } }

    fun decode(bytes: ByteArray): TransferMessage {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        return gson.fromJson(String(bytes, StandardCharsets.UTF_8), TransferMessage::class.java)
            ?: throw IllegalArgumentException("Missing control message")
    }
}

object TransferProtocolValidator {
    const val MAX_FILES = 100
    const val MAX_CHUNKS = 100
    const val MAX_TOTAL_BYTES = 1L shl 40
    private const val MAX_ID_LENGTH = 80
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_MIME_LENGTH = 127
    private const val MAX_PATH_LENGTH = 512
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun isValidManifestBegin(message: TransferMessage): Boolean {
        val chunks = message.totalChunks ?: return false
        val files = message.totalFiles ?: return false
        val bytes = message.totalBytes ?: return false
        return !message.transferId.isNullOrBlank() &&
            message.transferId.length <= MAX_ID_LENGTH &&
            chunks in 1..MAX_CHUNKS &&
            files in 1..MAX_FILES &&
            chunks <= files &&
            bytes in 0L..MAX_TOTAL_BYTES
    }

    fun isValidManifestChunk(message: TransferMessage, transferId: String, totalChunks: Int): Boolean {
        val sequence = message.sequence ?: return false
        val files = message.files ?: return false
        return message.transferId == transferId &&
            message.totalChunks == totalChunks &&
            sequence in 0 until totalChunks &&
            files.isNotEmpty() &&
            files.size <= MAX_FILES &&
            files.all(::isValidFile)
    }

    fun isValidManifest(files: List<TransferFile>, expectedFiles: Int, expectedBytes: Long): Boolean {
        if (files.size != expectedFiles || files.size !in 1..MAX_FILES || files.any { !isValidFile(it) }) return false
        if (files.map(TransferFile::fileId).distinct().size != files.size) return false
        return files.sumOf(TransferFile::size) == expectedBytes
    }

    fun isValidFile(file: TransferFile): Boolean =
        file.fileId.isNotBlank() && file.fileId.length <= MAX_ID_LENGTH &&
            file.name.isNotBlank() && file.name.length <= MAX_NAME_LENGTH &&
            file.mimeType.isNotBlank() && file.mimeType.length <= MAX_MIME_LENGTH &&
            file.size in 0L..MAX_TOTAL_BYTES &&
            sha256Pattern.matches(file.sha256) &&
            (file.relativePath?.length ?: 0) <= MAX_PATH_LENGTH
}

object ManifestChunker {
    fun chunk(sessionId: String, transferId: String, files: List<TransferFile>): List<TransferMessage> {
        require(files.size <= TransferProtocolValidator.MAX_FILES)
        require(files.all(TransferProtocolValidator::isValidFile))
        require(files.sumOf(TransferFile::size) <= TransferProtocolValidator.MAX_TOTAL_BYTES)
        if (files.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<TransferFile>>()
        files.forEach { file ->
            val current = groups.lastOrNull() ?: mutableListOf<TransferFile>().also(groups::add)
            current += file
            val probe = TransferMessage(
                type = TransferMessageType.MANIFEST_CHUNK,
                sessionId = sessionId,
                transferId = transferId,
                sequence = groups.lastIndex,
                totalChunks = Int.MAX_VALUE,
                files = current,
            )
            if (TransferMessageCodec.encode(probe).size > TransferMessageCodec.SAFE_BYTES) {
                current.removeAt(current.lastIndex)
                require(current.isNotEmpty()) { "Single manifest item is too large" }
                groups += mutableListOf(file)
            }
        }
        return groups.mapIndexed { index, group ->
            TransferMessage(
                type = TransferMessageType.MANIFEST_CHUNK,
                sessionId = sessionId,
                transferId = transferId,
                sequence = index,
                totalChunks = groups.size,
                files = group,
            )
        }
    }
}
