package com.wkq.photo.archive

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ZipArchiveCreatorTest {

    @Test
    fun createsArchiveAndMakesDuplicateNamesUnique() {
        val root = Files.createTempDirectory("zip-archive-test").toFile()
        val first = File(root, "one/report.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("first") }
        val second = File(root, "two/report.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("second") }
        val output = File(root, "result.zip")

        ZipArchiveCreator.create(ZipArchiveRequest(listOf(first, second), output))

        ZipFile(output).use { archive ->
            assertEquals(2, archive.size())
            assertTrue(archive.getEntry("report.txt") != null)
            assertTrue(archive.getEntry("report (2).txt") != null)
        }
    }

    @Test
    fun cancellationDuringLargeFilePreservesExistingOutput() {
        val root = Files.createTempDirectory("zip-cancellation-test").toFile()
        val source = File(root, "source.bin").apply { writeBytes(ByteArray(256 * 1024) { 7 }) }
        val output = File(root, "result.zip").apply { writeText("existing-output") }
        val original = output.readBytes()
        var cancellationChecks = 0

        try {
            ZipArchiveCreator.create(
                request = ZipArchiveRequest(listOf(source), output),
                isCancelled = { ++cancellationChecks >= 3 }
            )
            fail("Expected archive creation to be cancelled")
        } catch (_: IllegalStateException) {
            // 预期取消。
        }

        assertArrayEquals(original, output.readBytes())
        assertTrue(cancellationChecks >= 3)
    }

    @Test
    fun rejectsOutputThatIsAlsoASource() {
        val root = Files.createTempDirectory("zip-self-source-test").toFile()
        val output = File(root, "result.zip").apply { writeText("existing-output") }

        try {
            ZipArchiveCreator.create(ZipArchiveRequest(listOf(output), output))
            fail("Expected self-referencing archive request to fail")
        } catch (_: IllegalArgumentException) {
            // 预期参数校验失败。
        }

        assertEquals("existing-output", output.readText())
    }

    @Test
    fun supportsDirectoryEntriesCommentAndByteProgress() {
        val root = Files.createTempDirectory("zip-options-test").toFile()
        val first = File(root, "first.txt").apply { writeText("first") }
        val second = File(root, "second.txt").apply { writeText("second") }
        val output = File(root, "result.zip")
        val progress = mutableListOf<ZipArchiveProgress>()

        ZipArchiveCreator.create(
            entries = listOf(
                ZipArchiveEntry(first, "reports/2026/report.txt"),
                ZipArchiveEntry(second, "reports/2026/report.txt")
            ),
            outputFile = output,
            options = ZipArchiveOptions(ZipCompressionLevel.SMALLEST, comment = "Batch Photo export"),
            onProgress = progress::add
        )

        ZipFile(output).use { archive ->
            assertEquals("Batch Photo export", archive.comment)
            assertTrue(archive.getEntry("reports/2026/report.txt") != null)
            assertTrue(archive.getEntry("reports/2026/report (2).txt") != null)
        }
        val completed = progress.last()
        assertEquals(2, completed.completedFiles)
        assertEquals(first.length() + second.length(), completed.processedBytes)
        assertEquals(completed.totalBytes, completed.processedBytes)
    }

    @Test
    fun rejectsUnsafeEntryPath() {
        val root = Files.createTempDirectory("zip-entry-validation-test").toFile()
        val source = File(root, "source.txt").apply { writeText("source") }

        try {
            ZipArchiveEntry(source, "../outside.txt")
            fail("Expected unsafe ZIP entry path to fail")
        } catch (_: IllegalArgumentException) {
            // 预期阻止 Zip Slip 路径。
        }
    }

    @Test
    fun createsAes256ArchiveAndExtractsWithCorrectPassword() {
        val root = Files.createTempDirectory("zip-aes256-test").toFile()
        val first = File(root, "one/report.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("first") }
        val second = File(root, "two/report.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("second") }
        val output = File(root, "encrypted.zip")
        val password = "correct-password".toCharArray()
        val originalPassword = password.copyOf()
        val progress = mutableListOf<ZipArchiveProgress>()

        ZipArchiveCreator.createEncrypted(
            entries = listOf(ZipArchiveEntry(first), ZipArchiveEntry(second)),
            outputFile = output,
            password = password,
            options = ZipArchiveOptions(comment = "Encrypted export"),
            onProgress = progress::add
        )

        assertArrayEquals(originalPassword, password)
        Zip4jFile(output, password).use { archive ->
            assertTrue(archive.isEncrypted)
            assertEquals("Encrypted export", archive.comment)
            assertEquals(2, archive.fileHeaders.size)
            assertEquals("report.txt", archive.fileHeaders[0].fileName)
            assertEquals("report (2).txt", archive.fileHeaders[1].fileName)
            archive.fileHeaders.forEach { header ->
                assertTrue(header.isEncrypted)
                assertEquals(EncryptionMethod.AES, header.encryptionMethod)
                assertEquals(AesKeyStrength.KEY_STRENGTH_256, header.aesExtraDataRecord.aesKeyStrength)
            }
            val extracted = File(root, "extracted")
            archive.extractAll(extracted.absolutePath)
            assertEquals("first", File(extracted, "report.txt").readText())
            assertEquals("second", File(extracted, "report (2).txt").readText())
        }
        assertEquals(first.length() + second.length(), progress.last().processedBytes)
    }

    @Test
    fun supportsAes128AndRejectsWrongPassword() {
        val root = Files.createTempDirectory("zip-aes128-test").toFile()
        val source = File(root, "source.txt").apply { writeText("private") }
        val output = File(root, "encrypted.zip")

        ZipArchiveCreator.createEncrypted(
            request = ZipArchiveRequest(listOf(source), output),
            password = "correct-password".toCharArray(),
            encryptionOptions = ZipEncryptionOptions(ZipEncryptionStrength.AES_128)
        )

        Zip4jFile(output).use { archive ->
            assertTrue(archive.isEncrypted)
            assertEquals(AesKeyStrength.KEY_STRENGTH_128, archive.fileHeaders.single().aesExtraDataRecord.aesKeyStrength)
        }
        try {
            Zip4jFile(output, "wrong-password".toCharArray()).extractAll(File(root, "wrong").absolutePath)
            fail("Expected extraction with a wrong password to fail")
        } catch (error: Exception) {
            assertFalse(error.message.orEmpty().contains("wrong-password"))
            assertFalse(error.message.orEmpty().contains("correct-password"))
        }
    }

    @Test
    fun encryptedCancellationPreservesExistingOutputAndPassword() {
        val root = Files.createTempDirectory("zip-encrypted-cancellation-test").toFile()
        val source = File(root, "source.bin").apply { writeBytes(ByteArray(256 * 1024) { 7 }) }
        val output = File(root, "result.zip").apply { writeText("existing-output") }
        val originalOutput = output.readBytes()
        val password = "not-logged-password".toCharArray()
        val originalPassword = password.copyOf()
        var cancellationChecks = 0

        try {
            ZipArchiveCreator.createEncrypted(
                request = ZipArchiveRequest(listOf(source), output),
                password = password,
                isCancelled = { ++cancellationChecks >= 3 }
            )
            fail("Expected encrypted archive creation to be cancelled")
        } catch (error: IllegalStateException) {
            assertFalse(error.message.orEmpty().contains(String(password)))
        }

        assertArrayEquals(originalOutput, output.readBytes())
        assertArrayEquals(originalPassword, password)
        assertTrue(cancellationChecks >= 3)
    }

    @Test
    fun rejectsEmptyEncryptionPasswordWithoutChangingOutput() {
        val root = Files.createTempDirectory("zip-empty-password-test").toFile()
        val source = File(root, "source.txt").apply { writeText("source") }
        val output = File(root, "result.zip").apply { writeText("existing-output") }

        try {
            ZipArchiveCreator.createEncrypted(ZipArchiveRequest(listOf(source), output), CharArray(0))
            fail("Expected an empty password to fail")
        } catch (_: IllegalArgumentException) {
            // 预期拒绝空密码。
        }

        assertEquals("existing-output", output.readText())
    }
}
