package com.wkq.nas.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectionDraftStoreTest {
    @Test fun interruptedAtomicWriteRestoresCommittedSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SelectionDraftStore(context)
        val paths = listOf("folder/a", "folder/b")
        val token = store.save("owner", paths)
        val file = File(context.noBackupFilesDir, "selection-drafts/$token")
        val atomic = android.util.AtomicFile(file)
        // API 28 将已提交文件移入备份，再产生未完成的新文件；模拟此时进程退出。
        atomic.startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(paths, SelectionDraftStore(context).load(token, "owner"))
        assertEquals(paths, store.load(token, "owner"))
    }
    @Test fun largeSelectionSurvivesNewStoreButCannotBeReadByAnotherOwner() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val paths = (0..10000).map { "目录/file-$it.txt" }
        val token = SelectionDraftStore(context).save("picker:one", paths)
        val recreated = SelectionDraftStore(context)
        assertEquals(paths, recreated.load(token, "picker:one"))
        assertNull(recreated.load(token, "picker:other"))
        assertNull(recreated.load("../secret", "picker:one"))
    }
    @Test fun corruptionTruncationAndExpiryNeverRestorePartialSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var now = 100L
        val store = SelectionDraftStore(context) { now }
        val token = store.save("test", listOf("a", "b"))
        now += 8L * 24 * 60 * 60 * 1000
        assertNull(store.load(token, "test"))
        now = 100L
        val file = File(context.noBackupFilesDir, "selection-drafts/$token")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes); assertNull(store.load(token, "test"))
        file.writeBytes(bytes.copyOf(bytes.size / 2)); assertNull(store.load(token, "test"))
    }
    @Test fun failedSaveDoesNotEvictExistingRecoverableDrafts() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "selection-drafts")
        directory.deleteRecursively()
        val store = SelectionDraftStore(context)
        val tokens = (0 until 32).map { store.save("owner-$it", listOf("file-$it")) }
        val before = directory.listFiles()!!.map { it.name }.toSet()
        assertEquals(32, before.size)
        try { store.save("invalid", listOf("../outside")); fail("invalid path must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, directory.listFiles()!!.map { it.name }.toSet())
        tokens.forEachIndexed { index, token -> assertEquals(listOf("file-$index"), store.load(token, "owner-$index")) }
    }
    @Test fun invalidPathsDuplicatesAndOversizeCannotLeaveUsableDraft() = runBlocking {
        val store = SelectionDraftStore(RuntimeEnvironment.getApplication())
        for (paths in listOf(listOf("../outside"), listOf("same", "same"), listOf("a".repeat(8193)))) {
            try { store.save("invalid", paths); fail("must reject invalid draft") }
            catch (_: IllegalArgumentException) { }
        }
        val token = store.save("root", listOf(""))
        assertEquals(listOf(""), store.load(token, "root"))
    }
}
