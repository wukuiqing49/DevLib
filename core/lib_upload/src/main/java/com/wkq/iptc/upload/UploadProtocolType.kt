package com.wkq.iptc.upload

/** SMB、SFTP、WebDAV 统一由 lib_transfer 提供，不在上传库重复实现。 */
enum class UploadProtocolType {
    FTP, FTPS, HTTP;

    companion object {
        fun fromValue(value: String?): UploadProtocolType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported upload protocol: $value. SMB/SFTP/WebDAV are provided by lib_transfer")
    }
}
