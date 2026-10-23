package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class StorageIdentityTest {
    @Test fun defaultPortsAndSmbCaseCannotBypassNestedTargetGuard() {
        val source = ConnectionConfig(StorageProtocol.SMB, "smb://NAS/Share/Photos")
        val target = ConnectionConfig(StorageProtocol.SMB, "smb://nas:445/share/photos")
        assertEquals(StorageIdentity.identity(source, ""), StorageIdentity.identity(target, ""))
        try { StorageIdentity.validate(source, "", target, "backup", true); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun sftpDefaultPortMatchesButPathsRemainCaseSensitive() {
        val source = ConnectionConfig(StorageProtocol.SFTP, "sftp://host/root")
        assertEquals(StorageIdentity.identity(source, "A"), StorageIdentity.identity(source.copy(root = "sftp://HOST:22/root/"), "A"))
        assertNotEquals(StorageIdentity.identity(source, "A"), StorageIdentity.identity(source, "a"))
    }
    @Test fun webdavDefaultPortMatchesAndSiblingIsAllowed() {
        val source = ConnectionConfig(StorageProtocol.WEBDAV, "https://host/dav/source")
        val target = ConnectionConfig(StorageProtocol.WEBDAV, "https://HOST:443/dav/target")
        StorageIdentity.validate(source, "", target, "", true)
        try { StorageIdentity.validate(source, "", source.copy(root = "https://host:443/dav/source"), "", true); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
