package com.wkq.iptc.upload

import org.junit.Assert.*
import org.junit.Test

/** 检查历史协议配置的兼容读取与明确拒绝策略。 */
class UploadProtocolValidationTest {
    @Test fun supportedProfilesRemainReadable() {
        validateStoredUploadProtocols("""[{"protocol":"FTP","config":{"configType":"FTP"}},{"protocol":"FTPS"},{"protocol":"HTTP"}]""")
        assertEquals(UploadProtocolType.HTTP, UploadProtocolType.fromValue("http"))
        assertEquals(UploadProtocolType.FTPS, UploadServerProfile().protocol)
        assertTrue(UploadServerProfile().config is FtpsConfig)
    }

    /** 旧 NAS 协议明确指向 core_nas，不回退为其他上传方式。 */
    @Test fun retiredProfilesCannotFallBackToAnotherProtocol() {
        for (protocol in listOf("SFTP", "SMB", "WEBDAV")) {
            for (json in listOf("""{"protocol":"$protocol"}""", """[{"protocol":"FTP"},{"config":{"configType":"$protocol"}}]""")) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    validateStoredUploadProtocols(json)
                }
                assertTrue(error.message.orEmpty().contains("core_nas"))
            }
        }
    }

    @Test fun unknownProtocolsNeverBecomeFtpUploads() {
        assertThrows(IllegalArgumentException::class.java) { UploadProtocolType.fromValue("UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { UploadProtocolType.fromValue(null) }
    }
}
