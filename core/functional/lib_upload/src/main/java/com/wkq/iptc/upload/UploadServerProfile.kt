package com.wkq.iptc.upload

import java.util.UUID

data class UploadServerProfile(
    /** 自动生成的唯一标识。 */
    val id: String = UUID.randomUUID().toString(),
    /** 用户自定义名称；为空时界面显示协议和主机。 */
    val name: String = "",
    val protocol: UploadProtocolType = UploadProtocolType.FTPS,
    val autoUploadEnabled: Boolean = false,
    val keepLocalCopyAfterUpload: Boolean = true,
    val config: UploadServerConfig = FtpsConfig()
) {

    fun isReady(): Boolean {
        val commonReady = config.host.isNotBlank() && config.remoteDir.isNotBlank()
        if (!commonReady) {
            return false
        }
        return when (val current = config) {
            is FtpConfig -> current.username.isNotBlank() && current.password.isNotBlank()
            is FtpsConfig -> current.username.isNotBlank() && current.password.isNotBlank()
            is HttpConfig -> true
        }
    }

    fun defaultPort(): Int {
        return when (protocol) {
            UploadProtocolType.FTP -> 21
            UploadProtocolType.FTPS -> 21
            UploadProtocolType.HTTP -> 80
        }
    }
}

