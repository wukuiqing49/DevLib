package com.wkq.naslib

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SmbPathTest {
    @Test fun smbPrefersSigningAndNeverNegotiatesSmb1() {
        SmbProvider("smb://localhost/share/", "", "").use { provider ->
            val field = SmbProvider::class.java.getDeclaredField("cifs").apply { isAccessible = true }
            val config = (field.get(provider) as jcifs.context.BaseContext).config
            org.junit.Assert.assertTrue(config.isSigningEnabled)
            assertEquals(jcifs.DialectVersion.SMB202, config.minimumVersion)
            // 访客兼容策略不等同于强制签名或强制加密。
            org.junit.Assert.assertFalse(config.isSigningEnforced)
            org.junit.Assert.assertFalse(config.isEncryptionEnabled)
        }
    }
    @Test fun percentEncodedNamesRemainLiteralUrlSegments() {
        SmbProvider("smb://localhost/share/", "", "").use { provider ->
            val method = SmbProvider::class.java.getDeclaredMethod("file", String::class.java).apply { isAccessible = true }
            (method.invoke(provider, "%2e%2e/file name.txt") as jcifs.smb.SmbFile).use { file ->
                assertEquals("smb://localhost/share/%252e%252e/file%20name.txt", file.url.toString())
            }
        }
    }
    @Test fun urlDelimitersAreRejectedBeforeNetworkAccess() {
        SmbProvider("smb://localhost/share/", "", "").use { provider ->
            listOf("file#suffix", "file?query").forEach { path ->
                try {
                    provider.stat(path)
                    fail("Expected invalid path")
                } catch (error: TransferException) {
                    assertEquals(TransferError.INVALID_PATH, error.error)
                }
            }
        }
    }
}
