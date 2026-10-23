package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.TransferDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileContentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var source: File
    @Before fun setup() = runBlocking {
        source = temporary.newFolder()
        TransferClient.get(context).saveConnection("local", ConnectionConfig(StorageProtocol.LOCAL, source.path), displayName = "Local")
        FileContentCache(context).clear()
    }
    @After fun cleanup() = runBlocking {
        FileContentCache(context).clear()
        TransferDatabase.get(context).close()
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.deleteDatabase("nas-transfers.db")
        Unit
    }
    @Test fun independentPreviewDoesNotWaitForAnotherDownloadAndCleanupProtectsBoth() = runBlocking {
        File(source, "slow.txt").writeText("slow content"); File(source, "fast.txt").writeText("fast content")
        val started = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val slow = async(Dispatchers.IO) { FileContentCache(context).prepare("local", listOf("slow.txt")) { _, _, _ ->
            if (once.compareAndSet(false, true)) { started.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        } }
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val fast = withTimeout(5000) { FileContentCache(context).preparePreview("local", "fast.txt", 1024) }
            assertEquals("fast content", fast.readText()); assertFalse(slow.isCompleted)
            withTimeout(5000) { FileContentCache(context).pruneExpired() }
        } finally { release.countDown(); slow.await() }
    }
    @Test fun duplicateNamesAndZeroByteFilesAreIsolatedAndSourcesRemainUntouched() = runBlocking {
        File(source, "a").mkdir(); File(source, "b").mkdir()
        File(source, "a/test.txt").writeText("first"); File(source, "b/test.txt").writeText("second")
        File(source, "zero.txt").writeBytes(byteArrayOf())
        val result = FileContentCache(context).prepare("local", listOf("a/test.txt", "b/test.txt", "zero.txt"))
        assertEquals(listOf("first", "second", ""), result.map { it.readText() })
        assertTrue(result.all { it.canonicalPath.startsWith(File(context.cacheDir, "shared").canonicalPath + File.separator) })
        assertEquals("first", File(source, "a/test.txt").readText())
        assertNotEquals(result[0].path, result[1].path)
    }
    @Test fun canceledPreparationDeletesItsPartialCache() = runBlocking {
        File(source, "large.txt").writeBytes(ByteArray(200000))
        val request = launch(Dispatchers.IO) {
            FileContentCache(context).prepare("local", listOf("large.txt")) { done, _, _ -> if (done > 0) throw CancellationException() }
        }
        request.join()
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
        assertEquals(200000L, File(source, "large.txt").length())
    }
    @Test fun sourceChangeRejectsCacheRatherThanSharingIncompleteContent() = runBlocking {
        val original = File(source, "change.txt").apply { writeText("before") }
        try {
            FileContentCache(context).prepare("local", listOf("change.txt")) { done, _, _ -> if (done > 0) original.writeText("different contents") }
            fail("Source change must be rejected")
        } catch (e: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, e.error) }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
    @Test fun foldersAndTraversalAreRejectedWithoutPublishingCache() = runBlocking {
        File(source, "folder").mkdir()
        try { FileContentCache(context).prepare("local", listOf("folder")); fail() }
        catch (e: TransferException) { assertEquals(TransferError.UNSUPPORTED, e.error) }
        try { FileContentCache(context).prepare("local", listOf("../secret")); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
    @Test fun cacheFileNamesPreserveExtensionAndUtf8ByteLimit() {
        val name = FileContentCache.safeName("资料".repeat(150) + ".pdf")
        assertTrue(name.endsWith(".pdf")); assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertFalse(FileContentCache.safeName("bad:name?.txt").contains(':'))
    }
    @Test fun interruptedProcessCacheIsCleanedBeforeNextPreparation() = runBlocking {
        val abandoned = File(context.cacheDir, "shared/abandoned").apply { mkdirs() }
        File(abandoned, ".preparing").writeText("")
        File(abandoned, "partial").writeText("incomplete")
        File(source, "ready.txt").writeText("ready")
        val result = FileContentCache(context).prepare("local", listOf("ready.txt"))
        assertFalse(abandoned.exists()); assertEquals("ready", result.single().readText())
    }
    @Test fun startupPruningRemovesExpiredAndInterruptedCopiesButPreservesFreshCopiesAndSources() = runBlocking {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val expired = File(shared, "expired").apply { mkdir(); File(this, "cached.txt").writeText("expired") }
        assertTrue(expired.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
        val interrupted = File(shared, "interrupted").apply { mkdir(); File(this, ".preparing").writeText("") }
        val fresh = File(shared, "fresh").apply { mkdir(); File(this, "cached.txt").writeText("fresh") }
        val original = File(source, "original.txt").apply { writeText("original") }
        FileContentCache(context).pruneExpired()
        assertFalse(expired.exists()); assertFalse(interrupted.exists())
        assertEquals("fresh", File(fresh, "cached.txt").readText())
        assertEquals("original", original.readText())
    }
    @Test fun startupPruningDoesNotCreateAnUnusedCacheDirectory() = runBlocking {
        val shared = File(context.cacheDir, "shared")
        assertTrue(shared.deleteRecursively())
        FileContentCache(context).pruneExpired()
        assertFalse(shared.exists())
    }
    @Test fun startupPruningSkipsActivePreparationWithoutWaitingForNetwork() = runBlocking {
        File(source, "active.txt").writeText("active")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val prepared = async(Dispatchers.IO) {
            FileContentCache(context).prepare("local", listOf("active.txt")) { _, _, _ ->
                if (once.compareAndSet(false, true)) {
                    started.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        }
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val pruning = async(start = CoroutineStart.UNDISPATCHED) { FileContentCache(context).pruneExpired() }
            withTimeout(5000) { pruning.await() }
            assertTrue(File(context.cacheDir, "shared").listFiles()!!.any { File(it, ".preparing").exists() })
            try { FileContentCache(context).clear(); fail("active lease must be protected") }
            catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
            release.countDown()
            val copies = prepared.await()
            pruning.await()
            assertEquals("active", copies.single().readText())
        } finally { release.countDown() }
    }
    @Test fun deviceGroupsKeepConnectionsIndependentAndPersistRenames() = runBlocking {
        val transfer = TransferClient.get(context)
        transfer.saveConnection("nas1", ConnectionConfig(StorageProtocol.SMB, "smb://nas.local/Photos"), displayName = "Photos")
        transfer.saveConnection("nas2", ConnectionConfig(StorageProtocol.WEBDAV, "https://nas.local/Work"), displayName = "Work")
        val before = FileClient(context).observeConnections().first()
        val locations = StorageLocations(context)
        locations.assign("nas1", "Home NAS", StorageKind.NAS, "home")
        locations.assign("nas2", "Home NAS", StorageKind.NAS, "home")
        locations.assign("nas1", "Renamed NAS", StorageKind.NAS, "home")
        assertEquals(setOf("Renamed NAS"), locations.observe().first().values.map { it.name }.toSet())
        assertEquals(before, FileClient(context).observeConnections().first())
        try { locations.assign("local", "Wrong", StorageKind.PHONE, "home"); fail() } catch (_: IllegalArgumentException) { }
        locations.remove("nas1")
        assertEquals(setOf("nas2"), locations.observe().first().keys)
    }
    @Test fun mediaAndDocumentFiltersIncludeSupportedPreviewFormats() = runBlocking {
        val images = listOf("a.BMP", "b.HEIF", "c.heic", "d.jpg")
        val videos = listOf("a.M4V", "b.3GP", "c.mp4")
        val documents = listOf("a.md", "b.yaml", "c.json", "d.pdf", "e.docx")
        (images + videos + documents).forEach { File(source, it).writeText("sample") }
        File(source, "folder").mkdir()
        val client = FileClient(context)
        assertEquals(images.toSet(), client.listPage("local", "", filter = 3).entries.map { it.path }.toSet())
        assertEquals(videos.toSet(), client.listPage("local", "", filter = 4).entries.map { it.path }.toSet())
        assertEquals(documents.toSet(), client.listPage("local", "", filter = 2).entries.map { it.path }.toSet())
    }
    @Test fun fullDirectorySelectionSourceIncludesAllFilteredPages() = runBlocking {
        repeat(450) { File(source, "image$it.jpg").writeText("x") }
        repeat(20) { File(source, "other$it.txt").writeText("y") }
        File(source, ".hidden.jpg").writeText("hidden")
        val client = FileClient(context)
        val first = client.listPage("local", "", 200, filter = 3)
        val all = client.listPage("local", "", 100000, filter = 3)
        assertEquals(200, first.entries.size); assertEquals(450, first.total)
        assertEquals(450, all.entries.size); assertEquals(450, all.total)
        assertTrue(all.entries.all { it.path.endsWith(".jpg") && !it.path.startsWith('.') })
    }
    @Test fun isolatedWriteTestVerifiesAndCleansUpOnlyItsOwnFile() = runBlocking {
        File(source, "keep.txt").writeText("keep")
        val result = FileClient(context).testWrite("local", "")
        assertNull(result.error); assertEquals("", result.remainingPath)
        assertEquals(listOf("keep.txt"), source.listFiles().orEmpty().map { it.name })
        FileClient(context).testRead("local", "keep.txt")
    }
    @Test fun knownOversizedPreviewIsRejectedWithoutLeavingCache() = runBlocking {
        File(source, "oversized.txt").writeText("too large")
        try { FileContentCache(context).preparePreview("local", "oversized.txt", 4); fail() }
        catch (e: TransferException) { assertEquals(TransferError.UNSUPPORTED, e.error) }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
    @Test fun unknownSizePreviewStopsAtLimitAndNeverPublishesPartialFile() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            // 仅用隔离的回环 HTTP 服务验证未知大小；正式连接仍强制 HTTPS。
            TransferDatabase.get(context).dao().saveConnection(com.wkq.nas.transfer.data.ConnectionEntity("dav", "WEBDAV", server.url("/root/").toString(), "", "", ""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/root/file.txt</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(200000)))
            try { FileContentCache(context).preparePreview("dav", "file.txt", 1024); fail() }
            catch (e: TransferException) { assertEquals(TransferError.UNSUPPORTED, e.error) }
            assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun exactLimitAndEmptyPreviewRemainSupportedAndCompletionTimeIsFresh() = runBlocking {
        File(source, "exact.txt").writeText("1234")
        val before = System.currentTimeMillis()
        val file = FileContentCache(context).preparePreview("local", "exact.txt", 4)
        assertEquals("1234", file.readText())
        assertTrue(file.parentFile!!.parentFile!!.lastModified() >= before)
        File(source, "empty.txt").writeText("")
        assertEquals(0L, FileContentCache(context).preparePreview("local", "empty.txt", 4).length())
    }
}
