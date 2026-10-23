package com.wkq.nas.transfer

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.impl.utils.futures.SettableFuture
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.provider.*
import com.wkq.nas.transfer.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class ReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var source: File
    private lateinit var target: File
    private lateinit var file: File
    @Before fun setup() {
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        source = temporary.newFolder("source"); target = temporary.newFolder("target"); file = File(source, "a").apply { writeText("new-content") }
    }
    @After fun cleanup() {
        val field = TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? TransferDatabase)?.close(); field.set(null, null)
        RuntimeEnvironment.getApplication().deleteDatabase("nas-transfers.db")
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun task() = TaskEntity("replacement", "batch", "COPY", "source", "a", "target", "a", "a", "REPLACE", file.length(), file.lastModified(), false,
        targetExpectedSize = File(target, "a").length(), targetExpectedModified = File(target, "a").lastModified())
    private fun run(task: TaskEntity, storage: StorageProvider = LocalProvider(target.path), strict: Boolean = false) =
        TransferExecutor().execute(task, LocalProvider(source.path), storage, {}, {}, {}, {}, strict)

    @Test fun replacementRetainsOldUntilNewContentIsVerified() {
        File(target, "a").writeText("old-content")
        assertEquals(TaskState.SUCCEEDED, run(task()))
        assertEquals("new-content", File(target, "a").readText())
        assertFalse(File(target, ".nas-replacement.backup").exists())
    }
    @Test fun changedTargetInvalidatesReplacementPreview() {
        File(target, "a").writeText("old-content")
        val planned = task()
        File(target, "a").writeText("changed-after-preview")
        try { run(planned); fail() } catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertEquals("changed-after-preview", File(target, "a").readText())
    }
    @Test fun failedCommitRestoresOldTarget() {
        File(target, "a").writeText("old-content")
        val storage = LocalProvider(target.path)
        val failing = object : StorageProvider by storage {
            override fun moveNew(source: String, target: String, check: () -> Unit) {
                if (source.endsWith(".part")) throw TransferException(TransferError.NETWORK, true)
                storage.moveNew(source, target, check)
            }
        }
        try { run(task(), failing); fail() } catch (_: TransferException) { }
        assertEquals("old-content", File(target, "a").readText())
    }
    @Test fun corruptFinalContentKeepsOldBackup() {
        File(target, "a").writeText("old-content")
        val storage = LocalProvider(target.path)
        val failing = object : StorageProvider by storage {
            override fun moveNew(source: String, target: String, check: () -> Unit) {
                storage.moveNew(source, target, check)
                if (source.endsWith(".part")) File(this@ReliabilityTest.target, target).writeText("bad-content")
            }
        }
        try { run(task(), failing); fail() } catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertEquals("old-content", File(target, ".nas-replacement.backup").readText())
    }
    @Test fun strictHashDetectsSameSizeCorruptTemporaryContent() {
        val storage = LocalProvider(target.path)
        val corrupt = object : StorageProvider by storage {
            override fun write(path: String, input: java.io.InputStream, size: Long, progress: (Long) -> Unit) {
                storage.write(path, input, size, progress)
                File(target, path).writeText("bad-content")
            }
        }
        try { run(task().copy(conflictPolicy = "FAIL"), corrupt, strict = true); fail() }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertFalse(File(target, "a").exists())
    }
    @Test fun insufficientSpaceFailsBeforeWriting() {
        val storage = object : StorageProvider by LocalProvider(target.path) { override fun freeBytes(path: String) = 0L }
        try { run(task().copy(conflictPolicy = "FAIL"), storage); fail() }
        catch (error: TransferException) { assertEquals(TransferError.SPACE, error.error) }
        assertTrue(target.listFiles()!!.isEmpty())
    }
    @Test fun committedReplacementIsRecoveredByDigestAndOldBackupRemoved() {
        File(target, "a").writeText("new-content")
        File(target, ".nas-replacement.backup").writeText("old-content")
        val digest = file.inputStream().use { hash(it) }
        assertEquals(TaskState.SUCCEEDED, run(task().copy(digest = digest)))
        assertFalse(File(target, ".nas-replacement.backup").exists())
    }
    @Test fun boundedListingFiltersBeforeSelectingPage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        TransferDatabase.get(context).dao().saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        repeat(600) { File(source, "entry-${it.toString().padStart(4, '0')}.txt").writeText("data") }
        File(source, ".hidden.txt").writeText("hidden")
        val client = FileClient(context)
        val page = client.listPage("source", "", 25, query = "entry-", filter = 2)
        assertEquals(600, page.total); assertEquals(25, page.entries.size)
        assertEquals("entry-0000.txt", page.entries.first().path)
        assertEquals("entry-0024.txt", page.entries.last().path)
    }
    @Test fun persistedMutationExecutesAndInterruptedMutationIsNotReplayed() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        val preview = FileClient(context).previewRenameTo("source", "a", "renamed")
        dao.insertMutationJob(MutationJobEntity("queued", MutationCodec.encode(preview)))
        dao.insertMutationJob(MutationJobEntity("interrupted", MutationCodec.encode(preview), "RUNNING"))
        val worker = TestListenableWorkerBuilder.from(context, MutationWorker::class.java)
            .setForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }.build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("new-content", File(source, "renamed").readText())
        val jobs = dao.observeMutationJobs().first().associateBy { it.id }
        assertEquals("FAILED", jobs.getValue("interrupted").state)
        assertEquals("SUCCEEDED", jobs.getValue("queued").state)
    }
    @Test fun corruptMutationPlanFailsWithoutBlockingTheNextOperation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        val preview = FileClient(context).previewRenameTo("source", "a", "renamed")
        dao.insertMutationJob(MutationJobEntity("bad", "x".repeat(100000), connectionId = "source"))
        dao.deleteV2Raw(V2Payloads.chunkKind("mutation-plan", "bad"), "000000")
        dao.insertMutationJob(MutationJobEntity("good", MutationCodec.encode(preview), connectionId = "source"))
        val worker = TestListenableWorkerBuilder.from(context, MutationWorker::class.java)
            .setForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }.build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val jobs = dao.observeMutationJobs().first().associateBy { it.id }
        assertEquals("FAILED", jobs.getValue("bad").state)
        assertEquals("VERIFICATION", jobs.getValue("bad").error)
        assertEquals("SUCCEEDED", jobs.getValue("good").state)
        assertEquals("new-content", File(source, "renamed").readText())
    }
    @Test fun strictSyncDetectsSameSizeAndTimestampWithDifferentContents() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        dao.saveConnection(ConnectionEntity("target", "LOCAL", target.path, "", "", ""))
        File(target, "a").apply { writeText("bad-content"); setLastModified(file.lastModified()) }
        TransferPreferences(context).verifyContent = true
        val preview = TransferClient.get(context).previewSync("source", "", "target", "")
        assertEquals(SyncAction.CONFLICT, preview.items.first { it.source.path == "a" }.action)
    }
    @Test fun changedConflictTargetInvalidatesSyncConfirmation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        dao.saveConnection(ConnectionEntity("target", "LOCAL", target.path, "", "", ""))
        File(target, "a").writeText("old")
        val client = TransferClient.get(context)
        val preview = client.previewSync("source", "", "target", "")
        File(target, "a").writeText("changed since preview")
        try { client.enqueueSync(preview, ConflictPolicy.REPLACE); fail() }
        catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertEquals("changed since preview", File(target, "a").readText())
    }
    @Test fun savedSyncProfilesPersistWithoutStartingTransfers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        dao.saveConnection(ConnectionEntity("target", "LOCAL", target.path, "", "", ""))
        val client = TransferClient.get(context)
        val profile = SavedSyncProfile("manual", "Backup", "source", "", "target", "", ConflictPolicy.SKIP)
        client.saveSyncProfile(profile)
        assertEquals(profile, client.observeSyncProfiles().first().single())
        assertTrue(dao.taskPage(200, 0).isEmpty())
        client.deleteSyncProfile("manual")
        assertTrue(client.observeSyncProfiles().first().isEmpty())
    }
    @Test fun batchConflictResolutionPreservesExistingTargets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        TransferDatabase.get(context).dao().saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        File(source, "b").writeText("existing")
        val client = FileClient(context)
        val preview = client.previewRenameTo("source", "a", "b")
        assertTrue(client.resolveConflicts(preview, ConflictPolicy.SKIP).items.isEmpty())
        val resolved = client.resolveConflicts(preview, ConflictPolicy.KEEP_BOTH)
        assertEquals("b (1)", resolved.items.single().targetPath)
        client.execute(resolved)
        assertEquals("existing", File(source, "b").readText())
        assertEquals("new-content", File(source, "b (1)").readText())
    }
    @Test fun taskWindowIsBoundedWhileTotalsCoverAllHistoryAndClearPreservesFailures() = runBlocking {
        val dao = TransferDatabase.get(RuntimeEnvironment.getApplication()).dao()
        dao.insertTasks((0 until 300).map { index -> task().copy(id = "item-$index", conflictPolicy = "FAIL", createdAt = index.toLong(),
            state = listOf("QUEUED", "SUCCEEDED", "FAILED")[index % 3]) })
        assertEquals(25, dao.observeTaskWindow(25, 0).first().size)
        assertTrue(dao.observeTaskWindow(25, 3).first().all { it.state == "FAILED" })
        assertEquals(TaskTotals(300, 100, 100, 100), dao.observeTaskTotals().first())
        dao.clearFinishedTasks()
        assertEquals(TaskTotals(200, 100, 0, 100), dao.observeTaskTotals().first())
    }
    @Test fun corruptBackupCopyDoesNotRemoveOldTarget() {
        File(target, "a").writeText("old-content")
        val storage = LocalProvider(target.path)
        val corrupt = object : StorageProvider by storage {
            override fun write(path: String, input: java.io.InputStream, size: Long, progress: (Long) -> Unit) {
                storage.write(path, input, size, progress)
                if (path.endsWith(".backup")) File(target, path).writeText("bad-content")
            }
        }
        try { run(task(), corrupt); fail() } catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertEquals("old-content", File(target, "a").readText())
    }
    @Test fun manualRestoreRejectsSameSizeCorruptBackup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("target", "LOCAL", target.path, "", "", ""))
        File(target, "a").writeText("old-content")
        val originalHash = File(target, "a").inputStream().use { hash(it) }
        File(target, ".nas-replacement.backup").writeText("bad-content")
        dao.insertTasks(listOf(task().copy(state = "FAILED", backupDigest = originalHash)))
        try { TransferClient.get(context).restoreReplacement("replacement"); fail() }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertEquals("old-content", File(target, "a").readText())
        assertEquals(2, target.listFiles()!!.size)
    }
}
