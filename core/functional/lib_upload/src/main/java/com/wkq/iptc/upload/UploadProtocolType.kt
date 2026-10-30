package com.wkq.iptc.upload

/** SMB、SFTP、WebDAV 统一由 core_nas 提供，不在上传库重复实现。 */
enum class UploadProtocolType {
    FTP, FTPS, HTTP;

    /** 将持久化协议值转换为本库支持的上传方式。 */
    companion object {
        /** 拒绝未知和已拆出的协议，避免静默回退或误投递文件。 */
        fun fromValue(value: String?): UploadProtocolType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported upload protocol: $value. SMB/SFTP/WebDAV are provided by core_nas")
    }
}
