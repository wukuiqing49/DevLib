package com.wkq.iptc.upload

import org.junit.Assert.*
import org.junit.Test

class UploadProtocolValidationTest {
    @Test fun supportedProfilesRemainReadable() {
        validateStoredUploadProtocols("""[{"protocol":"FTP","config":{"configType":"FTP"}},{"protocol":"FTPS"},{"protocol":"HTTP"}]""")
        assertEquals(UploadProtocolType.HTTP, UploadProtocolType.fromValue("http"))
        assertEquals(UploadProtocolType.FTPS, UploadServerProfile().protocol)
        assertTrue(UploadServerProfile().config is FtpsConfig)
    }

    @Test fun retiredProfilesCannotFallBackToAnotherProtocol() {
        for (protocol in listOf("SFTP", "SMB", "WEBDAV")) {
            for (json in listOf("""{"protocol":"$protocol"}""", """[{"protocol":"FTP"},{"config":{"configType":"$protocol"}}]""")) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    validateStoredUploadProtocols(json)
                }
                assertTrue(error.message.orEmpty().contains("lib_transfer"))
            }
        }
    }

    @Test fun unknownProtocolsNeverBecomeFtpUploads() {
        assertThrows(IllegalArgumentException::class.java) { UploadProtocolType.fromValue("UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { UploadProtocolType.fromValue(null) }
    }
}
