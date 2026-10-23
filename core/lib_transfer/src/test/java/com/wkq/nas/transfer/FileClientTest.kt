package com.wkq.nas.transfer

import android.content.Context
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.data.TaskEntity
import com.wkq.nas.transfer.data.OperationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileClientTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var client: FileClient
    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor())
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters): androidx.work.ListenableWorker =
                        object : androidx.work.Worker(appContext, workerParameters) { override fun doWork() = Result.success() }
                }).build())
        root = Files.createTempDirectory("file-client").toFile()
        context.deleteDatabase("nas-transfers.db")
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        client = FileClient(context)
        TransferClient.get(context).saveConnection("local", ConnectionConfig(StorageProtocol.LOCAL, root.path), displayName = "Test files")
    }
    @After fun teardown() {
        TransferDatabase.get(context).close()
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        root.deleteRecursively()
    }
    @Test fun hiddenRecordCanBeRestoredWithoutRunningTaskOrChangingBackup() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        val backup = File(root, ".nas-hidden.backup").apply { writeText("original") }
        dao.insertTasks(listOf(TaskEntity("hidden", "hidden-batch", "COPY", "local", "source", "local", "target", "target", "REPLACE", 10, 0, false, state = "FAILED", backupDigest = backup.inputStream().use { com.wkq.nas.transfer.provider.hash(it) })))
        val history = TaskHistory(context)
        history.hide(setOf("transfer:hidden-batch"))
        assertEquals(listOf("transfer:hidden-batch"), history.observeHiddenRecords(1).first().map { it.key })
        history.restore(setOf("transfer:hidden-batch"))
        assertTrue(history.observeHiddenRecords().first().isEmpty())
        assertEquals("FAILED", dao.task("hidden")!!.state)
        assertTrue(client.observeUserTaskGroups(10).first().any { it.id == "hidden-batch" })
        assertEquals("original", backup.readText())
        assertEquals("target", TransferClient.get(context).restoreReplacement("hidden"))
        assertEquals("original", File(root, "target").readText())
    }
    @Test fun clearingHistoryPreservesUnverifiedCleanupAndRecoveryBackups() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        val backup = File(root, ".nas-replacement.backup").apply { writeText("backup") }
        File(root, ".nas-regular.part").writeText("partial")
        dao.insertTasks(listOf(
            TaskEntity("regular", "regular-batch", "COPY", "local", "a", "local", "b", "b", "FAIL", 1, 0, false, state = "CANCELED"),
            TaskEntity("replacement", "replacement-batch", "COPY", "local", "a", "local", "c", "c", "REPLACE", 1, 0, false, state = "CANCELED"),
            TaskEntity("offline", "offline-batch", "COPY", "local", "a", "missing", "d", "d", "FAIL", 1, 0, false, state = "CANCELED")))
        val transfers = TransferClient.get(context)
        transfers.clearFinishedTasks(); assertNotNull(dao.task("regular"))
        com.wkq.nas.transfer.work.TransferArtifactCleanup(dao, com.wkq.nas.transfer.provider.ProviderFactory(context)::open).run(false)
        transfers.clearFinishedTasks()
        assertNull(dao.task("regular")); assertNotNull(dao.task("replacement")); assertNotNull(dao.task("offline"))
        assertEquals("backup", backup.readText()); assertFalse(File(root, ".nas-regular.part").exists())
        assertEquals(1, dao.pendingCleanupCount(false))
    }
    @Test fun pickerSearchAndMoreReuseBoundedSnapshotButRefreshAndMutationSeeNewFiles() = runBlocking {
        repeat(450) { File(root, "item-%03d.txt".format(it)).writeText("x") }
        assertEquals(200, client.listPickerPage("local", "").entries.size)
        File(root, "new.txt").writeText("external")
        assertEquals(450, client.listPickerPage("local", "", 600).total)
        assertEquals(100, client.listPickerPage("local", "", 200, "item-1").total)
        client.invalidateListingCache()
        assertEquals(1, client.listPickerPage("local", "", 200, "new").total)
        client.createDirectory("local", "", "created")
        assertEquals(listOf("created"), client.listPickerPage("local", "", filter = 1).entries.map { it.path })
    }
    @Test fun completionSignalIncludesOldTaskOutsideDisplayWindowAndGroupQueriesAreScoped() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        val rows = (0..30).map { i -> TaskEntity("signal-$i", "batch-$i", "COPY", "local", "source-$i", "local", "target-$i", "target-$i", "FAIL", 10, 0, false, createdAt = i.toLong()) }
        dao.insertTasks(rows)
        val before = client.observeStorageCompletion("local").first()
        assertFalse(dao.observeTaskWindow(20, 0).first().any { it.id == "signal-0" })
        dao.claim("signal-0"); dao.finish("signal-0", "SUCCEEDED")
        assertNotEquals(before, client.observeStorageCompletion("local").first())
        assertEquals(listOf("batch-0"), client.observeTransferGroups(listOf("batch-0")).first().map { it.id })
        assertTrue(client.observeTransferGroups(emptyList()).first().isEmpty())
    }
    @Test fun boundedHistoryCandidatesDoNotLetAdvancedOrHiddenBatchesStarveVisibleRows() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks((0..40).map { i -> TaskEntity("candidate-$i", "candidate-batch-$i", "COPY", "local", "s", "local", "t$i", "t$i", "FAIL", 10, 0, false, createdAt=i.toLong()) })
        (21..40).forEach { i -> dao.saveV2(com.wkq.nas.transfer.data.V2Record("job-status", "candidate-batch-$i", "{}")) }
        (11..20).forEach { i -> dao.saveV2(com.wkq.nas.transfer.data.V2Record("hidden-task", "transfer:candidate-batch-$i", "{}")) }
        assertEquals(listOf("candidate-batch-10", "candidate-batch-9"), client.observeUserTaskGroups(2).first().map { it.id })
    }
    @Test fun metadataReadsPreserveBrowsingSnapshotsButMutationAndExplicitRefreshInvalidateThem() = runBlocking {
        File(root, "original.txt").writeText("original")
        assertEquals(1, client.listPickerPage("local", "").total)
        assertEquals(1, client.listPageCached("local", "").total)
        File(root, "external.txt").writeText("external")
        client.capabilities("local")
        assertNotNull(client.stat("local", "external.txt"))
        client.freeBytes("local", "")
        client.testRead("local", "original.txt")
        assertEquals(1, client.listPickerPage("local", "").total)
        assertEquals(1, client.listPageCached("local", "").total)
        // 实时规划仍看到新文件，不从展示快照生成变更。
        assertEquals(2, client.listPage("local", "").total)
        client.createDirectory("local", "", "created")
        assertEquals(3, client.listPickerPage("local", "").total)
        assertEquals(3, client.listPageCached("local", "").total)
        File(root, "refreshed.txt").writeText("refresh")
        client.invalidateListingCache()
        assertEquals(4, client.listPickerPage("local", "").total)
    }
    @Test fun browserCacheReusesDirectoryButFreshReadsAndMutationsBypassIt() = runBlocking {
        repeat(250) { File(root, "item-%03d.txt".format(it)).writeText("safe") }
        val first = client.listPageCached("local", "", 200)
        File(root, "new-file.txt").writeText("external update")
        val more = client.listPageCached("local", "", 400)
        assertEquals(250, first.total); assertEquals(250, more.total); assertEquals(250, more.entries.size)
        assertEquals(251, client.listPage("local", "", 400).total)
        client.invalidateListingCache(); assertEquals(251, client.listPageCached("local", "", 400).total)
        client.createDirectory("local", "", "created")
        assertEquals(252, client.listPageCached("local", "", 400).total)
    }
    @Test fun boundedListingKeepsDirectoryPriorityAndStableOrderingAcrossSortModes() = runBlocking {
        File(root, "z-folder").mkdir()
        File(root, "a.txt").writeText("123")
        File(root, "b.txt").writeText("123")
        File(root, "c.txt").writeText("12345")
        File(root, ".hidden.txt").writeText("123456789")
        assertTrue(File(root, "a.txt").setLastModified(100000))
        assertTrue(File(root, "b.txt").setLastModified(100000))
        assertTrue(File(root, "c.txt").setLastModified(200000))
        val expected = listOf(listOf("z-folder", "a.txt", "b.txt", "c.txt"),
            listOf("z-folder", "c.txt", "a.txt", "b.txt"), listOf("z-folder", "c.txt", "a.txt", "b.txt"))
        expected.forEachIndexed { sort, paths ->
            for (limit in 1..4) {
                val page = client.listPageCached("local", "", limit, sort = sort)
                assertEquals(4, page.total)
                assertEquals(paths.take(limit), page.entries.map { it.path })
                assertEquals(page, client.listPage("local", "", limit, sort = sort))
            }
        }
        assertEquals(listOf("z-folder", ".hidden.txt"), client.listPage("local", "", 2, sort = 1, showHidden = true).entries.map { it.path })
    }
    @Test fun listingSearchAndTypeFiltersHaveSameResultsFromCacheAndFreshEnumeration() = runBlocking {
        File(root, "album").mkdir()
        listOf("Report.txt", "photo.JPG", "movie.mp4", "other.bin", ".secret.txt").forEach { File(root, it).writeText("x") }
        val expected = listOf(listOf("album", "movie.mp4", "other.bin", "photo.JPG", "Report.txt"),
            listOf("album"), listOf("Report.txt"), listOf("photo.JPG"), listOf("movie.mp4"))
        expected.forEachIndexed { filter, paths ->
            val cached = client.listPageCached("local", "", 20, filter = filter)
            assertEquals(paths, cached.entries.map { it.path })
            assertEquals(paths.size, cached.total)
            assertEquals(cached, client.listPage("local", "", 20, filter = filter))
        }
        assertEquals(listOf("Report.txt"), client.listPageCached("local", "", 20, query = "REPORT").entries.map { it.path })
        assertEquals(listOf("album"), client.listPickerPage("local", "", 20, filter = 1).entries.map { it.path })
    }
    @Test fun removingRecordsKeepsBackupAndRejectsActiveBatchAtomically() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        val backup = File(root,".nas-done.backup").apply { writeText("original") }
        dao.insertTasks(listOf(
            TaskEntity("done","done-batch","COPY","local","a","local","b","b","REPLACE",1,0,false,state="FAILED"),
            TaskEntity("active","active-batch","COPY","local","c","local","d","d","FAIL",1,0,false,state="PAUSED")))
        val history = TaskHistory(context)
        try { history.hide(setOf("transfer:done-batch","transfer:active-batch")); fail("active batch must reject transaction") }
        catch (e: TransferException) { assertEquals(TransferError.CONFLICT,e.error) }
        assertTrue(history.observeHidden().first().isEmpty())
        history.hide(setOf("transfer:done-batch"))
        assertFalse(client.observeUserTaskGroups(100).first().any { it.id == "done-batch" })
        assertNotNull(dao.task("done")); assertEquals("original",backup.readText())
        assertTrue(client.observeUserTaskGroups(100).first().any { it.id == "active-batch" })
    }
    @Test fun historyRemovalChecksWholeBatchesAndPreservesAtomicRejection() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks((0..1200).map { i -> TaskEntity("history-$i", "large-history", "COPY", "local", "a", "local", "b", "b", "FAIL", 1, 0, false, state = if (i == 1200) "COMMITTING" else "SUCCEEDED") })
        dao.insertOperations(listOf(OperationEntity("history-operation", "operation-history", "local", "DELETE", "a", "", "RUNNING", null, 1)))
        val history = TaskHistory(context)
        try { history.hide(setOf("transfer:large-history")); fail("Committing batch hidden") }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        try { history.hide(setOf("operation:operation-history")); fail("Running operation hidden") }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        assertTrue(history.observeHidden().first().isEmpty())
        assertEquals(1201, dao.tasksForBatch("large-history").size)
        try { history.hide(setOf("transfer:missing-history")); fail("Missing batch hidden") }
        catch (error: TransferException) { assertEquals(TransferError.NOT_FOUND, error.error) }
    }
    @Test fun removingV2RecordDoesNotRemoveProfileOrBaseline() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.saveV2(com.wkq.nas.transfer.data.V2Record("job-status","history-job", """{"state":"SUCCEEDED","name":"sync"}"""))
        dao.saveV2(com.wkq.nas.transfer.data.V2Record("profile","history-profile","{}"))
        dao.saveV2(com.wkq.nas.transfer.data.V2Record("baseline","history-profile","{}"))
        TaskHistory(context).hide(setOf("v2:history-job"))
        assertTrue(V2Client(context).observeJobs().first().isEmpty())
        assertNotNull(dao.v2Record("profile","history-profile")); assertNotNull(dao.v2Record("baseline","history-profile"))
        assertNotNull(dao.v2Record("job-status","history-job"))
        TaskHistory(context).restore(setOf("v2:history-job"))
        assertEquals("history-job", V2Client(context).observeJobs().first().single().id)
        assertNotNull(dao.v2Record("baseline","history-profile"))
    }
    @Test fun hiddenRecordsDoNotConsumeVisiblePaginationWindow() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks((0..4).map { i -> TaskEntity("page$i","page-batch$i","COPY","local","a$i","local","b$i","b$i","FAIL",1,0,false,state="SUCCEEDED",createdAt=i.toLong()) })
        TaskHistory(context).hide(setOf("transfer:page-batch4","transfer:page-batch3"))
        assertEquals(listOf("page-batch2","page-batch1"),client.observeUserTaskGroups(2).first().map { it.id })
    }
    @Test fun batchOutcomeCountsAllStatesRegardlessOfFailurePagination() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks((0 until 300).map { i ->
            val status = if (i < 100) "SUCCEEDED" else if (i < 220) "SKIPPED" else "FAILED"
            TaskEntity("out$i", "aggregate", "COPY", "local", "a$i", "local", "b$i", "b$i", "SKIP", 10, 0, false,
                state = status, bytesTransferred = if (status == "SUCCEEDED") 10 else 0)
        })
        val transfers = TransferClient.get(context)
        val outcome = transfers.observeBatchOutcome("aggregate").first()
        assertEquals(300, outcome.total); assertEquals(100, outcome.succeeded); assertEquals(120, outcome.skipped)
        assertEquals(80, outcome.failed); assertEquals(0, outcome.canceled); assertEquals(1000L, outcome.transferredBytes)
        assertEquals(5, transfers.observeBatchFiltered("aggregate",5,true).first().size)
        assertEquals(outcome, transfers.observeBatchOutcome("aggregate").first())
    }
    @Test fun browserStatAndPagedListingExposeTypedConnectionFailure() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        TransferClient.get(context).saveConnection("offline",ConnectionConfig(StorageProtocol.WEBDAV,"https://127.0.0.1:$port/root"))
        val reads: List<suspend () -> Unit> = listOf({ client.stat("offline",""); Unit },
            { client.listPage("offline",""); Unit }, { client.listPageCached("offline",""); Unit })
        reads.forEach { read ->
            try { read(); fail("Network failure must be typed") }
            catch (error: TransferException) { assertEquals(TransferError.NETWORK,error.error); assertTrue(error.retryable) }
        }
    }
    @Test fun wrappedConnectionRefusalIsNetworkErrorRatherThanGenericFailure() {
        val error = java.io.IOException("transport", android.system.ErrnoException("connect", android.system.OsConstants.ECONNREFUSED))
        assertEquals(TransferError.NETWORK to true, com.wkq.nas.transfer.provider.classify(error))
        val denied = android.system.ErrnoException("open", android.system.OsConstants.EACCES)
        assertEquals(TransferError.PERMISSION to false, com.wkq.nas.transfer.provider.classify(denied))
    }
    @Test fun draftConnectionTestDoesNotPersistOrReplaceExistingConnection() = runBlocking {
        val transfer = TransferClient.get(context)
        val before = client.observeConnections().first()
        transfer.testConnectionDraft(ConnectionConfig(StorageProtocol.LOCAL, root.path), "")
        assertEquals(before, client.observeConnections().first())
        try {
            transfer.testConnectionDraft(ConnectionConfig(StorageProtocol.SMB, "smb://nas.local/Public"), "", "local")
            fail("Existing identity must match")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, client.observeConnections().first())
    }
    @Test fun listAndAnalyzeExcludePrivateIncompleteTransfers() = runBlocking {
        File(root, "zero.txt").writeBytes(byteArrayOf())
        File(root, "document.txt").writeText("test")
        File(root, ".nas-00000000-0000-0000-0000-000000000000.part").writeText("unfinished")
        assertEquals(2, client.list("local", "").size)
        val result = client.analyze("local", "")
        assertEquals(2, result.files); assertEquals(4L, result.bytes)
        assertEquals("Test files", client.observeConnections().first().single().name)
    }
    @Test fun previewRejectsCollisionWithoutModifyingEitherFile() = runBlocking {
        File(root, "a.txt").writeText("a"); File(root, "b.txt").writeText("b")
        val preview = client.previewRenameTo("local", "a.txt", "b.txt")
        assertTrue(preview.items.single().conflict)
        try { client.execute(preview); fail("Conflict") } catch (_: IllegalArgumentException) { }
        assertEquals("a", File(root, "a.txt").readText()); assertEquals("b", File(root, "b.txt").readText())
    }
    @Test fun changedSourceInvalidatesPreviewAndPreservesFiles() = runBlocking {
        File(root, "a.txt").writeText("original")
        val preview = client.previewRenameTo("local", "a.txt", "new.txt")
        File(root, "a.txt").writeText("changed contents")
        try { client.execute(preview); fail("Changed source") } catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertFalse(File(root, "new.txt").exists())
    }
    @Test fun renameAndOrganizeRecordResultsAndPreserveContents() = runBlocking {
        File(root, "a.txt").writeText("hello")
        client.execute(client.previewRenameTo("local", "a.txt", "b.txt"))
        assertFalse(File(root, "a.txt").exists()); assertEquals("hello", File(root, "b.txt").readText())
        client.execute(client.previewOrganize("local", listOf("b.txt"), OrganizeMode.TYPE))
        assertEquals("hello", File(root, "txt/b.txt").readText())
        assertEquals(2, client.observeOperations().first().count { it.state == "SUCCEEDED" })
    }
    @Test fun recursiveDeleteChecksFullPreviewAndDeletesEmptyParentsOnly() = runBlocking {
        File(root, "selected/sub").mkdirs(); File(root, "selected/sub/a.txt").writeText("a")
        val preview = client.previewDelete("local", listOf("selected"))
        File(root, "selected/new.txt").writeText("external")
        try { client.execute(preview); fail("New file") } catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertTrue(File(root, "selected/new.txt").exists())
        assertTrue(client.execute(client.previewDelete("local", listOf("selected"))).all { it.state == "SUCCEEDED" })
        assertFalse(File(root, "selected").exists())
    }
    @Test fun rootAndUnsafeDirectoryOperationsAreDenied() = runBlocking {
        try { client.previewDelete("local", listOf("")); fail("Root") } catch (_: IllegalArgumentException) { }
        assertFalse(client.capabilities(StorageProtocol.SAF).deleteDirectories)
        assertFalse(client.capabilities(StorageProtocol.WEBDAV).atomicRename)
        assertFalse(client.capabilities(StorageProtocol.SAF).renameDirectories)
    }
    @Test fun connectionRemovalWaitsForCanceledArtifactCleanup() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks(listOf(TaskEntity("pending", "batch", "COPY", "local", "a", "local", "b", "b", "FAIL", 1, 1, false, state = "PAUSED")))
        try { client.removeConnection("local"); fail("Live task") } catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        dao.cancel("pending")
        try { client.removeConnection("local"); fail("Pending cleanup must retain endpoint") }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        com.wkq.nas.transfer.work.TransferArtifactCleanup(dao, com.wkq.nas.transfer.provider.ProviderFactory(context)::open).run(false)
        client.removeConnection("local")
        assertTrue(client.observeConnections().first().isEmpty())
        assertNotNull(dao.task("pending"))
    }
    @Test fun interruptedMutationIsReportedWithoutRepeatingDestructiveOperations() = runBlocking {
        File(root, "keep.txt").writeText("keep")
        TransferDatabase.get(context).dao().insertOperations(listOf(OperationEntity("interrupted", "batch", "local", "DELETE", "keep.txt", "", "RUNNING", null, 1)))
        client.recoverInterruptedOperations()
        val record = client.observeOperations().first().single()
        assertEquals("FAILED", record.state); assertEquals(TransferError.VERIFICATION, record.error)
        assertEquals("keep", File(root, "keep.txt").readText())
    }
    @Test fun batchCancelKeepsCommittingAndCompletedFilesAndOneParent() = runBlocking {
        val dao = TransferDatabase.get(context).dao()
        dao.insertTasks(listOf("QUEUED", "COMMITTING", "SUCCEEDED", "PAUSED").mapIndexed { index, state ->
            TaskEntity("control-$index", "control", "COPY", "local", "a", "local", "b", "b", "FAIL", 1, 1, false, state = state)
        })
        assertEquals(2, TransferClient.get(context).controlBatch("control", 2))
        assertEquals(listOf("CANCELED", "COMMITTING", "SUCCEEDED", "CANCELED"), dao.tasksForBatch("control").map { it.state })
        assertEquals(1, dao.observeUserTaskGroups(200).first().size)
    }
}
