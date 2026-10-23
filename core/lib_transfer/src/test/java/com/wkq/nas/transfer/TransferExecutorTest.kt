package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.TaskEntity
import com.wkq.nas.transfer.provider.*
import com.wkq.nas.transfer.work.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun task(file: File, policy: ConflictPolicy = ConflictPolicy.FAIL) = TaskEntity("task-1", "batch", "COPY", "source", file.name,
        "target", "nested/${file.name}", "nested/${file.name}", policy.name, file.length(), file.lastModified(), false, state = "RUNNING")
    private fun execute(task: TaskEntity, source: StorageProvider, target: StorageProvider,
        check: () -> Unit = {}, progress: (Long) -> Unit = {}, commit: (String) -> Unit = {}): TaskState =
        TransferExecutor().execute(task, source, target, check, progress, {}, commit)

    @Test fun copyCommitsVerifiedFileAndRemovesTemporaryFile() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.bin").apply { writeBytes(ByteArray(200000) { (it % 251).toByte() }) }
        var savedDigest = ""
        assertEquals(TaskState.SUCCEEDED, execute(task(file), LocalProvider(source.path), LocalProvider(target.path), commit = { savedDigest = it }))
        assertArrayEquals(file.readBytes(), File(target, "nested/a.bin").readBytes())
        assertEquals(savedDigest, File(target, "nested/a.bin").inputStream().use { hash(it) })
        assertFalse(File(target, "nested/.nas-task-1.part").exists())
    }
    @Test fun emptyFileSucceeds() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "empty").apply { createNewFile() }
        assertEquals(TaskState.SUCCEEDED, execute(task(file), LocalProvider(source.path), LocalProvider(target.path)))
        assertEquals(0, File(target, "nested/empty").length())
    }
    @Test fun conflictNeverDeletesExistingTarget() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.txt").apply { writeText("new") }
        File(target, "nested").mkdir()
        val old = File(target, "nested/a.txt").apply { writeText("old") }
        try { execute(task(file), LocalProvider(source.path), LocalProvider(target.path)); fail() }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        assertEquals("old", old.readText())
        assertEquals(TaskState.SKIPPED, execute(task(file, ConflictPolicy.SKIP), LocalProvider(source.path), LocalProvider(target.path)))
    }
    @Test fun keepBothUsesAnotherName() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.txt").apply { writeText("new") }
        File(target, "nested").mkdir()
        File(target, "nested/a.txt").writeText("old")
        assertEquals(TaskState.SUCCEEDED, execute(task(file, ConflictPolicy.KEEP_BOTH), LocalProvider(source.path), LocalProvider(target.path)))
        assertEquals("new", File(target, "nested/a (1).txt").readText())
        assertEquals("old", File(target, "nested/a.txt").readText())
    }
    @Test fun cancellationDuringCopyDoesNotCreateFinalFile() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.bin").apply { writeBytes(ByteArray(200000)) }
        try {
            execute(task(file), LocalProvider(source.path), LocalProvider(target.path), progress = { if (it > 65536) throw TaskStopped() })
            fail()
        } catch (_: TaskStopped) { }
        assertFalse(File(target, "nested/a.bin").exists())
    }
    @Test fun changedSourceDoesNotUpload() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.txt").apply { writeText("old") }
        val before = task(file)
        file.writeText("new content")
        try { execute(before, LocalProvider(source.path), LocalProvider(target.path)); fail() }
        catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertFalse(File(target, "nested").exists())
    }
    @Test fun interruptedCommitRequiresMatchingHash() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.txt").apply { writeText("data") }
        val digest = file.inputStream().use { hash(it) }
        File(target, "nested").mkdir()
        val final = File(target, "nested/a.txt").apply { writeText("data") }
        assertEquals(TaskState.SUCCEEDED, execute(task(file).copy(digest = digest), LocalProvider(source.path), LocalProvider(target.path)))
        final.writeText("evil")
        try { execute(task(file).copy(digest = digest), LocalProvider(source.path), LocalProvider(target.path)); fail() }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
    }
    @Test fun corruptTemporaryWriteCannotBeCompleted() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        val file = File(source, "a.txt").apply { writeText("data") }
        val storage = LocalProvider(target.path)
        val corrupt = object : StorageProvider by storage {
            override fun stat(path: String): FileMetadata? = storage.stat(path)?.let { if (path.endsWith(".part")) it.copy(size = 1) else it }
        }
        try { execute(task(file), LocalProvider(source.path), corrupt); fail() }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertFalse(File(target, "nested/a.txt").exists())
    }
    @Test fun symlinkCannotEscapeLocalRoot() {
        val source = temporary.newFolder("source")
        val outside = temporary.newFolder("outside")
        val link = File(source, "link").toPath()
        try { java.nio.file.Files.createSymbolicLink(link, outside.toPath()) }
        catch (_: Exception) { org.junit.Assume.assumeTrue("Host does not permit symlinks", false) }
        try { LocalProvider(source.path).stat("link/file"); fail() } catch (_: IllegalArgumentException) { }
    }
}
