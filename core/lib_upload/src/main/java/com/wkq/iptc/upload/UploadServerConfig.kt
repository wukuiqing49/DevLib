package com.wkq.iptc.upload

sealed interface UploadServerConfig {
    val host: String
    val port: Int
    val username: String
    val remoteDir: String
    val connectTimeoutMs: Int
    val readTimeoutMs: Int
}

data class FtpConfig(
    override val host: String = "",
    override val port: Int = 21,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "/upload",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000,
    val passiveMode: Boolean = true,
    val binaryTransfer: Boolean = true
) : UploadServerConfig

data class FtpsConfig(
    override val host: String = "",
    override val port: Int = 21,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "/upload",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000,
    val passiveMode: Boolean = true,
    val binaryTransfer: Boolean = true,
    val securityMode: FtpsSecurityMode = FtpsSecurityMode.EXPLICIT,
    val allowInsecureCertificate: Boolean = false
) : UploadServerConfig

data class SftpConfig(
    override val host: String = "",
    override val port: Int = 22,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "/upload",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000,
    val allowInsecureHostKey: Boolean = false
) : UploadServerConfig

data class HttpConfig(
    override val host: String = "",
    override val port: Int = 80,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000,
    val headers: Map<String, String> = emptyMap(),
    val extraParams: Map<String, String> = emptyMap()
) : UploadServerConfig

data class SmbConfig(
    override val host: String = "",
    override val port: Int = 445,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "/upload",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000
) : UploadServerConfig


data class WebDavConfig(
    override val host: String = "",
    override val port: Int = 80,
    override val username: String = "",
    val password: String = "",
    override val remoteDir: String = "/upload",
    override val connectTimeoutMs: Int = 15_000,
    override val readTimeoutMs: Int = 30_000,
    /** true 使用 HTTPS，false 使用 HTTP。 */
    val useHttps: Boolean = false,
    /** 允许自签名或不可信证书，仅在 useHttps=true 时生效。 */
    val allowInsecureCert: Boolean = false
) : UploadServerConfig

