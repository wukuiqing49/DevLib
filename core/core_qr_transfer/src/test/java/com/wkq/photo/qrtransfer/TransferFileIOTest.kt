package com.wkq.photo.qrtransfer

import com.wkq.photo.qrtransfer.file.IntegrityException
import com.wkq.photo.qrtransfer.file.sanitizeFileName
import com.wkq.photo.qrtransfer.file.verifyAndCopy
import com.wkq.photo.qrtransfer.model.TransferFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferFileIOTest {
    @Test
    fun verifyAndCopy_acceptsMatchingFileAndRejectsWrongHash() {
        val bytes = "qr-transfer".toByteArray()
        val valid = file(bytes, bytes.sha256())
        val output = ByteArrayOutputStream()

        verifyAndCopy(valid, ByteArrayInputStream(bytes), output)

        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(IntegrityException::class.java) {
            verifyAndCopy(valid.copy(sha256 = "00".repeat(32)), ByteArrayInputStream(bytes), ByteArrayOutputStream())
        }
    }

    @Test
    fun sanitizeFileName_removesRemotePathAndTraversalCharacters() {
        assertEquals("photo_.jpg", sanitizeFileName("../../folder/photo?.jpg"))
        assertEquals("received_file", sanitizeFileName("..."))
    }

    private fun file(bytes: ByteArray, sha: String) = TransferFile(
        fileId = "file",
        name = "file.bin",
        mimeType = "application/octet-stream",
        size = bytes.size.toLong(),
        sha256 = sha,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }
}
