package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.StorageProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class SyncScopeScannerTest {
    private class Tree : StorageProvider {
        val visited = mutableListOf<String>()
        var forbidden = true
        override fun stat(path: String) = FileMetadata(path, true, 0, 0)
        override fun list(path: String): List<FileMetadata> {
            visited += path
            return when (path) {
                "root" -> listOf(directory("root/keep"), directory("root/other"), directory("root/.nas-recycle"))
                "root/keep" -> listOf(directory("root/keep/nested"), file("root/keep/a.txt"))
                "root/keep/nested" -> listOf(file("root/keep/nested/b.txt"))
                "root/other" -> { check(!forbidden) { "Unselected subtree was visited" }; listOf(file("root/other/c.txt")) }
                else -> error("Unexpected path: $path")
            }
        }
        override fun read(path: String): InputStream = error("Scanner must not read content")
        override fun mkdirs(path: String): Unit = error("Read only")
        override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit): Unit = error("Read only")
        override fun moveNew(source: String, target: String, check: () -> Unit): Unit = error("Read only")
        override fun delete(path: String): Unit = error("Read only")
        private fun directory(path: String) = FileMetadata(path, true, 0, 0)
        private fun file(path: String) = FileMetadata(path, false, 1, 1)
    }
    @Test fun selectedDirectorySkipsUnrelatedSubtreesAndKeepsDescendants() = runBlocking {
        val provider = Tree()
        val result = scanSyncScope(provider, "root", setOf("keep"))
        assertEquals(setOf("keep", "keep/a.txt", "keep/nested", "keep/nested/b.txt"), result.keys)
        assertEquals(listOf("root", "root/keep", "root/keep/nested"), provider.visited)
    }
    @Test fun selectedFileKeepsAncestorsWithoutVisitingSiblingDirectories() = runBlocking {
        val provider = Tree()
        assertEquals(setOf("keep", "keep/a.txt"), scanSyncScope(provider, "root", setOf("keep/a.txt")).keys)
        assertEquals(listOf("root", "root/keep"), provider.visited)
    }
    @Test fun emptySelectionScansAllPublicDescendants() = runBlocking {
        val provider = Tree().apply { forbidden = false }
        val result = scanSyncScope(provider, "root")
        assertTrue("other/c.txt" in result)
        assertFalse(result.keys.any { it.startsWith(".nas-recycle") })
        assertEquals(4, provider.visited.size)
    }
    @Test fun traversalSelectionIsRejectedBeforeProviderAccess() = runBlocking {
        val provider = Tree()
        try { scanSyncScope(provider, "root", setOf("../other")); fail("Invalid path") }
        catch (_: IllegalArgumentException) { }
        assertTrue(provider.visited.isEmpty())
    }

    private class StreamingTree(val count: Int, val beforeAccept: (Int) -> Unit = {}) : StorageProvider {
        var emitted = 0
        override fun stat(path: String) = FileMetadata(path,true,0,0)
        override fun list(path: String): List<FileMetadata> = error("Must use streaming visits")
        override fun visit(path: String, accept: (FileMetadata) -> Unit) {
            repeat(count) { emitted++; beforeAccept(emitted); accept(FileMetadata("root/file-$it",false,1,1)) }
        }
        override fun read(path: String): InputStream = error("Metadata only")
        override fun mkdirs(path: String): Unit = error("Read only")
        override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit): Unit = error("Read only")
        override fun moveNew(source: String, target: String, check: () -> Unit): Unit = error("Read only")
        override fun delete(path: String): Unit = error("Read only")
    }
    @Test fun oversizedStreamStopsAtLimitAndNeverReturnsATruncatedSuccessfulPlan() = runBlocking {
        val provider = StreamingTree(100002)
        try { scanSyncScope(provider,"root"); fail("Oversized scan must fail") }
        catch (error: TransferException) { assertEquals(TransferError.UNSUPPORTED,error.error) }
        assertEquals(100001,provider.emitted)
    }
    @Test fun cancellationStopsWithinTheCurrentStreamedDirectory() = runBlocking {
        val job = kotlinx.coroutines.Job()
        val provider = StreamingTree(100) { if (it == 3) job.cancel() }
        try { kotlinx.coroutines.withContext(job) { scanSyncScope(provider,"root") }; fail("Must cancel") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(3,provider.emitted)
    }
}
