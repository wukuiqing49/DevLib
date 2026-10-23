package com.wkq.photo.archive

import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationResult
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EncryptedArchiveOperationsTest {

    @Test
    fun rejectsPasswordShorterThanExtractorContract() {
        val root = Files.createTempDirectory("zip-short-password-test").toFile()
        val source = File(root, "source.txt").apply { writeText("source") }
        val output = File(root, "result.zip")

        try {
            ZipArchiveCreator.createEncrypted(
                ZipArchiveRequest(listOf(source), output),
                "123".toCharArray(),
            )
            fail("Expected a short password to fail")
        } catch (_: IllegalArgumentException) {
            assertFalse(output.exists())
        }
    }

    @Test
    fun operationPreservesCustomEntryNameAndCallerPassword() {
        val root = Files.createTempDirectory("zip-operation-name-test").toFile()
        val source = File(root, "staged-random-name.jpg").apply { writeText("image") }
        val output = File(root, "result.zip")
        val password = "correct-password".toCharArray()
        val originalPassword = password.copyOf()

        val result = ZipArchiveOperations.createEncrypted(
            entries = listOf(ZipArchiveEntry(source, "Original Photo.jpg")),
            outputFile = output,
            password = password,
        )

        assertTrue(result is OperationResult.Success)
        assertArrayEquals(originalPassword, password)
        ZipFile(output, password).use { archive ->
            assertEquals("Original Photo.jpg", archive.fileHeaders.single().fileName)
        }
    }

    @Test
    fun extractorRejectsEntryEscapingOutputDirectory() {
        val root = Files.createTempDirectory("zip-slip-extractor-test").toFile()
        val archive = File(root, "unsafe.zip")
        val password = "password".toCharArray()
        ZipFile(archive, password).addStream(
            ByteArrayInputStream("unsafe".toByteArray()),
            ZipParameters().apply {
                fileNameInZip = "../outside.jpg"
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            },
        )
        val outputDirectory = File(root, "output")

        val result = EncryptedArchiveExtractor.extract(
            EncryptedArchiveExtractRequest(
                archive,
                outputDirectory,
                password,
            ),
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.INVALID_INPUT, (result as OperationResult.Failure).code)
        assertFalse(File(root, "outside.jpg").exists())
        assertFalse(outputDirectory.exists())
    }

    @Test
    fun extractorRejectsArchiveWithUnencryptedEntries() {
        val root = Files.createTempDirectory("zip-unencrypted-test").toFile()
        val archive = File(root, "plain.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("photo.jpg"))
            output.write("plain".toByteArray())
            output.closeEntry()
        }

        val result = EncryptedArchiveExtractor.extract(
            EncryptedArchiveExtractRequest(
                archive,
                File(root, "output"),
                "password".toCharArray(),
            ),
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.INVALID_INPUT, (result as OperationResult.Failure).code)
    }
}
