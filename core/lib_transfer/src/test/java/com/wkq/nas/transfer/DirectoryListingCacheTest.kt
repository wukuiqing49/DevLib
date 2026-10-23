package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.ConnectionEntity
import org.junit.Assert.*
import org.junit.Test

class DirectoryListingCacheTest {
    @Test fun oversizedMetadataIsNotCachedEvenWhenCountFits() {
        val cache = DirectoryListingCache({ 100 }, capacity = 10, maxChars = 100)
        val connection = ConnectionEntity("a", "LOCAL", "/root", "", "", "")
        cache.put(cache.version(), connection, "", listOf(FileMetadata("a".repeat(101), false, 1, 1)))
        assertNull(cache.get(connection, ""))
    }
    @Test fun isolatesIdentityExpiresAndRejectsLateWritesAfterInvalidation() {
        var now = 100L
        val cache = DirectoryListingCache({ now }, 2, 5000)
        val connection = ConnectionEntity("a", "LOCAL", "/root", "", "", "")
        val files = listOf(FileMetadata("a", false, 1, 1))
        cache.put(cache.version(), connection, "", files)
        assertEquals(files, cache.get(connection, ""))
        assertNull(cache.get(connection.copy(passwordEncrypted = "changed"), ""))
        assertNull(cache.get(connection, "other"))
        now += 5000; assertNull(cache.get(connection, ""))
        val oldVersion = cache.version(); cache.invalidate(); cache.put(oldVersion, connection, "", files)
        assertNull(cache.get(connection, ""))
        cache.put(cache.version(), connection, "", files + files + files)
        assertNull(cache.get(connection, ""))
    }
}
