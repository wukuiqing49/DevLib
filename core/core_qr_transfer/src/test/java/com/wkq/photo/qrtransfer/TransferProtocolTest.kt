package com.wkq.photo.qrtransfer

import com.wkq.photo.qrtransfer.model.TransferFile
import com.wkq.photo.qrtransfer.model.TransferProgress
import com.wkq.photo.qrtransfer.protocol.ManifestChunker
import com.wkq.photo.qrtransfer.protocol.TransferMessage
import com.wkq.photo.qrtransfer.protocol.TransferMessageCodec
import com.wkq.photo.qrtransfer.protocol.TransferMessageType
import com.wkq.photo.qrtransfer.protocol.TransferProtocolValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {
    @Test
    fun manifestChunker_keepsHundredFilesBelowSafeBytes() {
        val files = (1..100).map { index ->
            TransferFile(
                fileId = "file-$index",
                name = "photo_${index}_" + "long-name-".repeat(20) + ".jpg",
                mimeType = "image/jpeg",
                size = 10_000_000L + index,
                sha256 = "ab".repeat(32),
                relativePath = "BatchPhoto/Output",
            )
        }

        val chunks = ManifestChunker.chunk("session", "transfer", files)

        assertTrue(chunks.size > 1)
        assertEquals(files, chunks.flatMap { it.files.orEmpty() })
        assertTrue(chunks.all { TransferMessageCodec.encode(it).size <= TransferMessageCodec.SAFE_BYTES })
    }

    @Test
    fun manifestValidator_rejectsOversizedOrInconsistentManifest() {
        val tooMany = TransferMessage(
            type = TransferMessageType.MANIFEST_BEGIN,
            sessionId = "session",
            transferId = "transfer",
            totalChunks = 1,
            totalFiles = 101,
            totalBytes = 101L,
        )
        val file = validFile()

        assertFalse(TransferProtocolValidator.isValidManifestBegin(tooMany))
        assertFalse(TransferProtocolValidator.isValidManifest(listOf(file), expectedFiles = 1, expectedBytes = 2L))
    }

    @Test
    fun manifestValidator_rejectsInvalidFileMetadata() {
        assertFalse(TransferProtocolValidator.isValidFile(validFile().copy(size = -1L)))
        assertFalse(TransferProtocolValidator.isValidFile(validFile().copy(sha256 = "not-a-sha256")))
        assertFalse(TransferProtocolValidator.isValidFile(validFile().copy(name = "x".repeat(256))))
    }

    @Test
    fun transferProgress_doesNotOverflowForLargeValues() {
        val progress = TransferProgress(
            transferredBytes = Long.MAX_VALUE - 1L,
            totalBytes = Long.MAX_VALUE,
        )

        assertEquals(100, progress.percent)
    }

    private fun validFile() = TransferFile(
        fileId = "file-1",
        name = "photo.jpg",
        mimeType = "image/jpeg",
        size = 1L,
        sha256 = "ab".repeat(32),
    )
}
