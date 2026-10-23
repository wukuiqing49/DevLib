package com.wkq.iptc.upload

import java.util.UUID

data class UploadServerProfile(
    /** 自动生成的唯一标识。 */
    val id: String = UUID.randomUUID().toString(),
    /** 用户自定义名称；为空时界面显示协议和主机。 */
    val name: String = "",
    val protocol: UploadProtocolType = UploadProtocolType.SFTP,
    val autoUploadEnabled: Boolean = false,
    val keepLocalCopyAfterUpload: Boolean = true,
    val config: UploadServerConfig = SftpConfig()
) {

    fun isReady(): Boolean {
        val commonReady = config.host.isNotBlank() && config.remoteDir.isNotBlank()
        if (!commonReady) {
            return false
        }
        return when (val current = config) {
            is FtpConfig -> current.username.isNotBlank() && current.password.isNotBlank()
            is FtpsConfig -> current.username.isNotBlank() && current.password.isNotBlank()
            is SftpConfig -> current.username.isNotBlank() && current.password.isNotBlank()
            is HttpConfig -> true
            is SmbConfig -> true
            is WebDavConfig -> true
        }
    }

    fun defaultPort(): Int {
        return when (protocol) {
            UploadProtocolType.FTP -> 21
            UploadProtocolType.FTPS -> 21
            UploadProtocolType.SFTP -> 22
            UploadProtocolType.HTTP -> 80
            UploadProtocolType.SMB -> 445
            // WebDAV 默认端口：HTTP 80，HTTPS 443。
            UploadProtocolType.WEBDAV -> if ((config as? WebDavConfig)?.useHttps == true) 443 else 80
        }
    }
}

