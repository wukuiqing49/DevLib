package com.wkq.iptc.upload

class UploadDispatcher(
    clients: List<UploadClient>
) {
    private val clientMap = clients.associateBy { it.protocol }

    suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val client = clientMap[profile.protocol]
            ?: return UploadResult.Failure(
                UploadFailure(
                    code = UploadErrorCode.UNSUPPORTED_PROTOCOL,
                    message = "Unsupported protocol: ${profile.protocol}",
                    retryable = false
                )
            )
        return client.upload(task, profile, onProgress)
    }

    suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val client = clientMap[profile.protocol]
            ?: return UploadResult.Failure(
                UploadFailure(
                    code = UploadErrorCode.UNSUPPORTED_PROTOCOL,
                    message = "Unsupported protocol: ${profile.protocol}",
                    retryable = false
                )
            )
        return client.testConnection(profile)
    }
}

