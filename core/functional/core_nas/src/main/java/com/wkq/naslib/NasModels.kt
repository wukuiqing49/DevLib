package com.wkq.naslib

import java.io.IOException

enum class NasProtocol { SMB, WEBDAV, SFTP }
enum class NasError { AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, SOURCE_CHANGED, NETWORK, SPACE,

    UNSUPPORTED, HOST_KEY, CERTIFICATE, STORAGE_UNAVAILABLE, VERIFICATION, INVALID_PATH, UNKNOWN }

data class NasEntry(val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long)
data class NasCapabilities(val renameDirectories: Boolean, val deleteDirectories: Boolean,

    val atomicRename: Boolean, val reliableModifiedTime: Boolean, val quota: Boolean,

    val seekRead: Boolean = false, val seekWrite: Boolean = false,

    val serverSideCopy: Boolean = false, val serverSideMove: Boolean = false,

    val overwrite: Boolean = false, val pagedListing: Boolean = false)

class NasException(val error: NasError, val retryable: Boolean = false) : IOException(error.name)

/** 凭据仅供当前会话使用；持久化与加密由宿主负责，诊断输出不包含凭据和地址。 */
class NasConnection(val protocol: NasProtocol, val root: String, val username: String = "",
    val password: String = "", val hostKeySha256: String = "") {
    init {
        val uri = java.net.URI(root)
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.scheme in when (protocol) {
            NasProtocol.SMB -> setOf("smb")
            NasProtocol.WEBDAV -> setOf("http", "https")
            NasProtocol.SFTP -> setOf("sftp")
        })
    }
    override fun toString() = "NasConnection(protocol=$protocol, credentials=redacted)"
}
