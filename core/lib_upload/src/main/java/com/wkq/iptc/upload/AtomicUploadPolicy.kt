package com.wkq.iptc.upload

import java.security.MessageDigest

object AtomicUploadPolicy {
    fun validate(task: UploadTask, profile: UploadServerProfile): UploadResult.Failure? {
        if (!task.createOnly) return null
        if (task.transferId.isBlank()) return failure(UploadErrorCode.INVALID_CONFIG, "Missing persistent transfer identifier")
        val supported = when (profile.protocol) {
            UploadProtocolType.FTP, UploadProtocolType.FTPS -> false
            UploadProtocolType.HTTP -> (profile.config as? HttpConfig)?.extraParams?.get("__create_only_put__") == "true"
            else -> true
        }
        return if (supported) null else failure(UploadErrorCode.ATOMIC_CREATE_UNSUPPORTED,
            "This destination cannot guarantee create-only upload. Use SFTP, SMB, WebDAV or a conditional HTTP PUT endpoint")
    }

    fun temporaryName(fileName: String, createOnly: Boolean, transferId: String): String {
        if (!createOnly) return "$fileName.part"
        require(transferId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(transferId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
        return ".captionmeta-$digest.part"
    }

    fun remoteExists() = failure(UploadErrorCode.REMOTE_EXISTS, "A file already exists at this destination; the original was not replaced")

    private fun failure(code: UploadErrorCode, message: String) =
        UploadResult.Failure(UploadFailure(code, message, retryable = false))
}
