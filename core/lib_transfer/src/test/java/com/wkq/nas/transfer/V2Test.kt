package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
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
class V2Test {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var left: File
    private lateinit var right: File
    private lateinit var client: V2Client
    private lateinit var dao: TransferDao
    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor())
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(appContext: android.content.Context, workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters): androidx.work.ListenableWorker =
                        object : androidx.work.Worker(appContext, workerParameters) {
                            override fun doWork() = Result.success()
                        }
                }).build())
        left = temporary.newFolder("left"); right = temporary.newFolder("right")
        dao = TransferDatabase.get(context).dao()
        dao.saveConnection(ConnectionEntity("left", "LOCAL", left.path, "", "", ""))
        dao.saveConnection(ConnectionEntity("right", "LOCAL", right.path, "", "", ""))
        client = V2Client(context)
    }
    @After fun close() {
        val field = TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? TransferDatabase)?.close(); field.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        RuntimeEnvironment.getApplication().deleteDatabase("nas-transfers.db")
    }
    @Test fun connectionExchangeHasSymmetricCountAndEmptyBoundaries() {
        fun locations(count: Int) = (0 until count).map { SavedConnection("id$it", "NAS $it", ConnectionConfig(StorageProtocol.WEBDAV, "https://host/root$it")) }
        for (count in listOf(1, 100, 101, 1000)) assertEquals(count, ConnectionExchange.decode(ConnectionExchange.encode(locations(count))).size)
        assertThrows(TransferException::class.java) { ConnectionExchange.encode(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionExchange.encode(locations(1001)) }
        val local = SavedConnection("local", "Phone", ConnectionConfig(StorageProtocol.LOCAL, "/private"))
        assertThrows(TransferException::class.java) { ConnectionExchange.encode(listOf(local)) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionExchange.encode(listOf(locations(1).single().copy(name = "x".repeat(201)))) }
        val json = ConnectionExchange.encode(locations(1))
        val boundary = json + " ".repeat(ConnectionExchange.MAX_BYTES - json.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, ConnectionExchange.decode(boundary).size)
        assertThrows(IllegalArgumentException::class.java) { ConnectionExchange.decode(boundary + " ") }
    }
    @Test fun parentCancelDuringRecycleCopyStopsBeforeDeletingSource() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val original = File(left, "cancel-recycle.txt").apply { writeText("preserve original") }
        val local = com.wkq.nas.transfer.provider.LocalProvider(left.path)
        val metadata = local.stat(original.name)!!
        val content = local.read(original.name).use { com.wkq.nas.transfer.provider.hash(it) }
        dao.saveV2(V2Record("job-status", "cancel-parent", "{\"state\":\"RUNNING\",\"name\":\"cancel test\"}"))
        val provider = object : com.wkq.nas.transfer.provider.StorageProvider by local {
            override fun write(path: String, input: java.io.InputStream, size: Long, progress: (Long) -> Unit) {
                local.write(path, input, size) { bytes ->
                    if (bytes > 0) runBlocking { client.cancelJob("cancel-parent") }
                    progress(bytes)
                }
            }
        }
        var clock = 0L
        val control = AdvancedJobControl({
            if (runBlocking { dao.v2Record("job-status", "cancel-parent") }!!.payload.contains("CANCELED"))
                throw TransferException(TransferError.VERIFICATION)
        }, { clock += 500; clock })
        try { withContext(control) { client.recycle("left", provider, ContentStamp(metadata, content)) }; fail("canceled source must survive") }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        assertEquals("preserve original", original.readText())
        assertEquals("CANCELED", client.observeJobs().first().single().state)
        assertTrue(client.recycles().none { it.state == "READY" })
    }
    @Test fun directorySnapshotsAccumulateAcrossSyncChildEnqueues() = runBlocking {
        File(left,"first").mkdirs(); File(left,"second").mkdirs()
        val transfers = TransferClient.get(RuntimeEnvironment.getApplication())
        transfers.enqueueBatch(listOf(TransferRequest("left","first","right","first")),"tree-snapshot")
        transfers.enqueueBatch(listOf(TransferRequest("left","second","right","second")),"tree-snapshot")
        assertEquals(mapOf("left:first" to true,"left:second" to true),transfers.batchDirectories("tree-snapshot"))
    }
    @Test fun periodicWaitsForOtherJobAndUnchangedRecoveryClearsWaiting() = runBlocking {
        val p = profile(SyncMode.INCREMENTAL)
        client.saveProfile(p)
        dao.saveV2(V2Record("job-status","other-job", """{"state":"QUEUED","name":"other","profile":"other","createdAt":1}"""))
        try { client.periodic(p.id); fail("busy queue must retry") }
        catch (e: TransferException) { assertEquals(TransferError.CONFLICT,e.error); assertTrue(e.retryable) }
        assertEquals(SyncWait.QUEUE,client.observeWaiting().first()[p.id])
        dao.deleteV2("job-status","other-job")
        client.rememberUnchanged(client.previewSync(p))
        assertFalse(client.observeWaiting().first().containsKey(p.id))
    }
    private fun stamp(digest: String, path: String = "a") = ContentStamp(FileMetadata(path, false, 4, 1000), digest)
    private fun profile(mode: SyncMode = SyncMode.TWO_WAY) = AdvancedSyncProfile("p", "test", "left", "", "right", "", mode)
    @Test fun transferWaitReceivesCompletionAndParentCancellationWithoutPolling() = runBlocking {
        File(left, "wait.txt").writeText("safe")
        val task = TransferClient.get(RuntimeEnvironment.getApplication()).enqueue(TransferRequest("left", "wait.txt", "right", "wait.txt"))
        dao.saveV2(V2Record("job-status", "parent", "{\"state\":\"RUNNING\"}"))
        val waiting = async(Dispatchers.IO) { client.awaitTransfer(task, "parent") }
        delay(30); dao.claim(task); dao.finish(task, "SUCCEEDED")
        assertEquals(TaskState.SUCCEEDED, withTimeout(5000) { waiting.await() }.state)
        val canceled = async(Dispatchers.IO) {
            try { client.awaitTransfer(task, "parent"); null } catch (error: TransferException) { error.error }
        }
        // 已完成的文件可以立即返回；新等待必须响应父级取消，即使文件没有更新。
        canceled.await()
        val pending = TransferClient.get(RuntimeEnvironment.getApplication()).enqueue(TransferRequest("left", "wait.txt", "right", "other.txt"))
        val parentWait = async(Dispatchers.IO) {
            try { client.awaitTransfer(pending, "parent"); null } catch (error: TransferException) { error.error }
        }
        delay(30); dao.saveV2(V2Record("job-status", "parent", "{\"state\":\"CANCELED\"}"))
        assertEquals(TransferError.VERIFICATION, withTimeout(5000) { parentWait.await() })
    }
    @Test fun smallFilesCompleteThroughRealQueueAndKeepContentChecks() = runBlocking {
        repeat(24) { File(left, "small-$it.txt").writeBytes(ByteArray(4096) { it.toByte() }) }
        val started = System.nanoTime()
        client.enqueueSync(client.previewSync(profile(SyncMode.INCREMENTAL))); drain()
        val elapsed = (System.nanoTime() - started) / 1000000
        assertEquals("SUCCEEDED", client.observeJobs().first().single().state)
        left.listFiles()!!.forEach { assertArrayEquals(it.readBytes(), File(right, it.name).readBytes()) }
        println("SYNC_END_TO_END files=24 bytes=98304 elapsedMs=$elapsed contentVerified=true")
    }
    private suspend fun drain() = withTimeout(30000) { coroutineScope {
        val runner = async(Dispatchers.IO) { client.runPending() }
        while (runner.isActive) {
            val worker = androidx.work.testing.TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(),
                com.wkq.nas.transfer.work.TransferQueueWorker::class.java)
                .setInputData(androidx.work.workDataOf(com.wkq.nas.transfer.work.TransferQueueWorker.NETWORK_KEY to false))
                .setForegroundUpdater { _, _, _ -> androidx.work.impl.utils.futures.SettableFuture.create<Void>().apply { set(null) } }.build()
            worker.doWork(); delay(50)
        }
        runner.await()
    } }
    private fun plan(l: String?, r: String?, baseline: SyncBaseline? = null, mode: SyncMode = SyncMode.TWO_WAY) =
        AdvancedSyncPlanner.plan(mode, l?.let { mapOf("a" to stamp(it)) }.orEmpty(), r?.let { mapOf("a" to stamp(it)) }.orEmpty(),
            baseline?.let { mapOf("a" to it) }.orEmpty()).single().action

    @Test fun syncRunSnapshotSurvivesCancellationWithoutDependingOnCurrentProfile() = runBlocking {
        File(left, "snapshot.txt").writeText("fixture")
        val p = profile(SyncMode.INCREMENTAL)
        val id = client.enqueueSync(client.previewSync(p))
        val summary = requireNotNull(client.syncRunSummary(id))
        assertEquals("INCREMENTAL", summary["mode"])
        assertEquals("1", summary["copyRight"])
        assertEquals("0", summary["recycle"])
        client.cancelJob(id)
        assertEquals(summary, client.syncRunSummary(id))
    }
    @Test fun firstSyncCopiesBothDirectionsWithoutGuessingConflicts() {
        assertEquals(AdvancedAction.COPY_RIGHT, plan("left", null))
        assertEquals(AdvancedAction.COPY_LEFT, plan(null, "right"))
        assertEquals(AdvancedAction.CONFLICT, plan("left", "right"))
    }
    @Test fun incrementalProtectsLocalEditsAndKeepsUserConflictDecision() {
        assertEquals(AdvancedAction.CONFLICT, plan("nas", "local", mode = SyncMode.INCREMENTAL))
        assertEquals(AdvancedAction.CONFLICT, plan("new", "local", SyncBaseline("old", "old"), SyncMode.INCREMENTAL))
        assertEquals(AdvancedAction.CONFLICT, plan("new", null, SyncBaseline("old", "old"), SyncMode.INCREMENTAL))
        assertEquals(AdvancedAction.COPY_RIGHT, plan("new", "old", SyncBaseline("old", "old"), SyncMode.INCREMENTAL))
        assertEquals(AdvancedAction.SAME, plan("nas", "local", SyncBaseline("nas", "local"), SyncMode.INCREMENTAL))
    }
    @Test fun incrementalKeepTargetNeverUploadsTargetBackToSource() = runBlocking {
        File(left, "a").writeText("nas version"); File(right, "a").writeText("phone version")
        val value = profile(SyncMode.INCREMENTAL)
        client.enqueueSync(client.previewSync(value), 2); drain()
        assertEquals("nas version", File(left, "a").readText())
        assertEquals("phone version", File(right, "a").readText())
        assertEquals(AdvancedAction.SAME, client.previewSync(value).items.single().action)
        assertTrue(client.recycles().isEmpty())
    }
    @Test fun localAvailabilityRequiresActualRoot() = runBlocking {
        val probe = StorageProbe(RuntimeEnvironment.getApplication())
        assertNull(probe.check("left").error)
        assertEquals(TransferError.NOT_FOUND, probe.check("missing").error)
        assertTrue(left.delete())
        assertNotNull(probe.check("left").error)
    }
    @Test fun selectionSurvivesCodecAndExcludesUnselectedFiles() = runBlocking {
        File(left, "selected.txt").writeText("selected"); File(left, "other.txt").writeText("other")
        val value = profile(SyncMode.INCREMENTAL).copy(selectedPaths = setOf("selected.txt"))
        client.saveProfile(value)
        assertEquals(value, client.observeProfiles().first().single())
        val preview = client.previewSync(value)
        assertEquals(listOf("selected.txt"), preview.items.map { it.relative })
        client.enqueueSync(preview); drain()
        assertTrue(File(right, "selected.txt").exists()); assertFalse(File(right, "other.txt").exists())
    }
    @Test fun selectedPathDisappearanceIsNotEmptySource() = runBlocking {
        File(right, "missing.txt").writeText("keep")
        try { client.previewSync(profile(SyncMode.INCREMENTAL).copy(selectedPaths = setOf("missing.txt"))); fail() }
        catch (e: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, e.error) }
        assertEquals("keep", File(right, "missing.txt").readText())
    }
    @Test fun legacyMigrationIsIdempotentAndKeepsOriginal() = runBlocking {
        val transfers = TransferClient.get(RuntimeEnvironment.getApplication())
        transfers.saveSyncProfile(SavedSyncProfile("legacy", "Legacy", "left", "", "right", "", ConflictPolicy.REPLACE))
        val value = client.migrateLegacy("legacy")
        assertEquals("legacy", value.id); assertEquals(SyncMode.INCREMENTAL, value.mode)
        assertEquals(value, client.migrateLegacy("legacy"))
        assertEquals("legacy", transfers.observeSyncProfiles().first().single().id)
    }
    @Test fun duplicateSyncReusesQueuedRunAndKeepsProfileAssociationAfterCompletion() = runBlocking {
        File(left, "a").writeText("contents")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        val first = client.enqueueSync(preview)
        assertEquals(first, client.enqueueSync(preview))
        drain()
        assertEquals("p", client.observeJobs().first().single().profileId)
        assertEquals("SUCCEEDED", client.observeProfileResults().first()["p"]?.first)
        assertFalse(AdvancedSyncPlanner.changed(client.previewSync(profile(SyncMode.INCREMENTAL))))
    }
    @Test fun oldProfileCodecHasSafeDefaults() {
        val old = V2Codec.profile(profile()).apply { remove("selected"); remove("deviceTrigger") }
        assertTrue(V2Codec.profile(old).selectedPaths.isEmpty())
        assertFalse(V2Codec.profile(old).deviceTrigger)
    }
    @Test fun baselineDistinguishesDeletionFromNewFiles() {
        assertEquals(AdvancedAction.RECYCLE_RIGHT, plan(null, "old", SyncBaseline("old", "old")))
        assertEquals(AdvancedAction.RECYCLE_LEFT, plan("old", null, SyncBaseline("old", "old")))
        assertEquals(AdvancedAction.TOMBSTONE, plan(null, null, SyncBaseline("old", "old")))
    }
    @Test fun deleteVersusEditRemainsConflict() {
        assertEquals(AdvancedAction.CONFLICT, plan(null, "new", SyncBaseline("old", "old")))
        assertEquals(AdvancedAction.CONFLICT, plan("new", null, SyncBaseline("old", "old")))
    }
    @Test fun singleSideEditPropagatesAndBothEditsConflict() {
        assertEquals(AdvancedAction.COPY_RIGHT, plan("new", "old", SyncBaseline("old", "old")))
        assertEquals(AdvancedAction.COPY_LEFT, plan("old", "new", SyncBaseline("old", "old")))
        assertEquals(AdvancedAction.CONFLICT, plan("new", "other", SyncBaseline("old", "old")))
    }
    @Test fun mirrorRecyclesExtrasWhileIncrementalKeepsThem() {
        assertEquals(AdvancedAction.RECYCLE_RIGHT, plan(null, "extra", mode = SyncMode.MIRROR))
        assertEquals(AdvancedAction.SAME, plan(null, "extra", mode = SyncMode.INCREMENTAL))
    }
    @Test fun emptyDirectoryDeletionUsesBaseline() {
        val directory = ContentStamp(FileMetadata("empty", true, 0, 10), "directory")
        assertEquals(AdvancedAction.RECYCLE_RIGHT, AdvancedSyncPlanner.plan(SyncMode.TWO_WAY, emptyMap(), mapOf("empty" to directory),
            mapOf("empty" to SyncBaseline("directory", "directory"))).single().action)
    }
    @Test fun cleanupRequiresOneSurvivingCopyPerGroup() {
        val group = DuplicateGroup("x", listOf(stamp("x", "a"), stamp("x", "b")))
        assertEquals(1, AdvancedSyncPlanner.cleanup(listOf(group), setOf("a")).size)
        assertThrows(IllegalArgumentException::class.java) { AdvancedSyncPlanner.cleanup(listOf(group), setOf("a", "b")) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedSyncPlanner.cleanup(listOf(group), setOf("unknown")) }
    }
    @Test fun bulkCleanupKeepsOneCopyInEveryGroupAndRejectsForeignPaths() {
        val groups = (0 until 2000).map { DuplicateGroup("digest-$it", listOf(stamp("digest-$it", "keep-$it"), stamp("digest-$it", "remove-$it"))) }
        val selected = (0 until 2000).map { "remove-$it" }.toSet()
        assertEquals(selected, AdvancedSyncPlanner.cleanup(groups, selected).map { it.metadata.path }.toSet())
        assertThrows(IllegalArgumentException::class.java) { AdvancedSyncPlanner.cleanup(groups, selected + "keep-1999") }
        assertThrows(IllegalArgumentException::class.java) { AdvancedSyncPlanner.cleanup(groups, selected + "foreign") }
    }
    @Test fun connectionExchangeExcludesLocalAndRejectsSecretsAndTraversal() {
        val json = ConnectionExchange.encode(listOf(SavedConnection("one", "NAS", ConnectionConfig(StorageProtocol.SMB, "smb://host/share/")),
            SavedConnection("two", "phone", ConnectionConfig(StorageProtocol.LOCAL, left.path))))
        assertEquals(1, ConnectionExchange.decode(json).size)
        assertFalse(json.contains(left.path)); assertFalse(json.contains("password"))
        val value = JSONObject(json); value.getJSONArray("connections").getJSONObject(0).put("password", "secret")
        assertThrows(IllegalArgumentException::class.java) { ConnectionExchange.decode(value.toString()) }
        val traversal = JSONObject(json); traversal.getJSONArray("connections").getJSONObject(0).put("root", "smb://host/share/%2e%2e/escape")
        assertThrows(IllegalArgumentException::class.java) { ConnectionExchange.decode(traversal.toString()) }
    }
    @Test fun importAddsNewIdsWithoutReplacingCredentials() = runBlocking {
        val json = ConnectionExchange.encode(listOf(SavedConnection("left", "server", ConnectionConfig(StorageProtocol.WEBDAV, "https://host/root"))))
        val ids = client.importConnections(json)
        assertNotEquals("left", ids.single()); assertEquals("", dao.connection(ids.single())!!.passwordEncrypted)
        assertEquals(left.path, dao.connection("left")!!.root)
    }
    @Test fun favoritesPersistAndInvalidPathsAreRejected() = runBlocking {
        val id = client.favorite("root", "left", "")
        assertEquals(id, client.observeFavorites().first().single().id)
        client.validateFavorite(client.observeFavorites().first().single())
        client.removeFavorite(id); assertTrue(client.observeFavorites().first().isEmpty())
        try { client.favorite("bad", "left", "../escape"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateAnalysisUsesContentAndExcludesRecoveryArea() = runBlocking {
        File(left, "a").writeText("same"); File(left, "b").writeText("same"); File(left, "c").writeText("diff")
        File(left, ".nas-recycle/hidden").apply { parentFile.mkdirs(); writeText("same") }
        val result = client.analyze("left", "")
        assertEquals(3, result.scannedFiles); assertEquals(1, result.duplicates.size)
        assertEquals(setOf("a", "b"), result.duplicates.single().files.map { it.metadata.path }.toSet())
    }
    @Test fun cleanupRetainsVerifiedRecoveryAndRestoreNeverOverwrites() = runBlocking {
        File(left, "a").writeText("same"); File(left, "b").writeText("same")
        val groups = client.analyze("left", "").duplicates
        client.cleanup("left", groups, setOf("b")); client.runPending()
        assertTrue(File(left, "a").exists()); assertFalse(File(left, "b").exists())
        val entry = client.recycles().single(); assertEquals("READY", entry.state)
        File(left, "b").writeText("new user file")
        val restored = client.restore(entry)
        assertNotEquals("b", restored); assertEquals("same", File(left, restored).readText())
        assertEquals("new user file", File(left, "b").readText())
        client.purge(entry); assertFalse(File(left, entry.storedPath).exists())
    }
    @Test fun cleanupChecksTheRetainedCopyBeforeMutating() = runBlocking {
        File(left, "a").writeText("same"); File(left, "b").writeText("same")
        client.cleanup("left", client.analyze("left", "").duplicates, setOf("b"))
        File(left, "a").writeText("changed")
        client.runPending()
        assertTrue(File(left, "b").exists()); assertEquals("FAILED", client.observeJobs().first().single().state)
    }
    @Test fun corruptRecoveryCannotBeRestoredOrPurged() = runBlocking {
        File(left, "a").writeText("same"); File(left, "b").writeText("same")
        client.cleanup("left", client.analyze("left", "").duplicates, setOf("b")); client.runPending()
        val entry = client.recycles().single(); File(left, entry.storedPath).writeText("evil")
        try { client.restore(entry); fail() } catch (e: TransferException) { assertEquals(TransferError.VERIFICATION, e.error) }
        try { client.purge(entry); fail() } catch (e: TransferException) { assertEquals(TransferError.VERIFICATION, e.error) }
    }
    @Test fun sourceMissingDoesNotBecomeMirrorDeletionPlan() = runBlocking {
        File(right, "only").writeText("preserve")
        try { client.previewSync(profile(SyncMode.MIRROR).copy(leftRoot = "missing")); fail() }
        catch (e: TransferException) { assertEquals(TransferError.NOT_FOUND, e.error) }
        assertEquals("preserve", File(right, "only").readText())
    }
    @Test fun changedSourceInvalidatesConfirmedSync() = runBlocking {
        File(left, "a").writeText("old")
        val preview = client.previewSync(profile())
        File(left, "a").writeText("new")
        try { client.enqueueSync(preview); fail() } catch (e: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, e.error) }
    }
    @Test fun safeSyncRejectsNewDestinationBetweenScanAndSubmission() = runBlocking {
        File(left, "new.txt").writeText("source")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        assertFalse(AdvancedSyncPlanner.risky(preview))
        File(right, "new.txt").writeText("created by another app")
        try { client.enqueueSync(preview); fail("A new target requires a fresh review") }
        catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertEquals("created by another app", File(right, "new.txt").readText())
        assertTrue(client.observeJobs().first().isEmpty())
    }
    @Test fun safeSyncSubmissionRetainsOneJobAndReportsLocalScanTiming() = runBlocking {
        repeat(256) { File(left, "item-$it.txt").writeBytes(ByteArray(4096) { 7 }) }
        val start = System.nanoTime()
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        val scanned = System.nanoTime()
        assertFalse(AdvancedSyncPlanner.risky(preview))
        val id = client.enqueueSync(preview)
        val submitted = System.nanoTime()
        assertEquals(id, client.enqueueSync(preview))
        assertEquals(1, client.observeJobs().first().size)
        assertEquals(0, right.listFiles()!!.size)
        println("QUICK_SYNC_LOCAL files=256 bytes=1048576 scanMs=${(scanned-start)/1000000} recheckAndSubmitMs=${(submitted-scanned)/1000000}")
    }
    @Test fun unchangedScanIsSavedWithoutReadingTheTreesAgain() = runBlocking {
        File(left, "a").writeText("same"); File(right, "a").writeText("same")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        assertFalse(AdvancedSyncPlanner.changed(preview))
        val digest = preview.items.single().left!!.digest
        // 快照描述已完成扫描的时点；记账不应再次读取已离线的源。
        left.deleteRecursively()
        client.rememberUnchanged(preview)
        val baseline = JSONObject(dao.v2Record("baseline", "p")!!.payload)
        assertEquals(digest, baseline.getJSONObject("a").getString("left"))
        try { client.previewSync(preview.profile); fail("Next scan must detect offline source") }
        catch (error: TransferException) { assertEquals(TransferError.NOT_FOUND, error.error) }
    }
    @Test fun queuedUnsavedSyncProtectsBothConnectionsUntilCanceled() = runBlocking {
        File(left, "a").writeText("content")
        val id = client.enqueueSync(client.previewSync(profile(SyncMode.INCREMENTAL)))
        val files = FileClient(RuntimeEnvironment.getApplication())
        for (endpoint in listOf("left", "right")) {
            try { files.removeConnection(endpoint); fail("Queued sync endpoint") }
            catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        }
        client.cancelJob(id)
        files.removeConnection("left")
        assertNull(dao.connection("left"))
        assertEquals("CANCELED", client.observeJobs().first().single().state)
    }
    @Test fun corruptQueuedPlanFailsSafelyAndDoesNotBlockANewSync() = runBlocking {
        File(left, "a").writeText("content")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        val id = client.enqueueSync(preview)
        val data = JSONObject(dao.v2Record("job", id)!!.payload).put("padding", "x".repeat(100000))
        dao.saveV2(V2Record("job", id, data.toString()))
        dao.deleteV2Raw(V2Payloads.chunkKind("job", id), "000000")
        client.runPending()
        assertEquals("FAILED", client.observeJobs().first().single().state)
        assertEquals("VERIFICATION", client.observeJobs().first().single().error)
        assertFalse(File(right, "a").exists())
        client.enqueueSync(preview); drain()
        assertEquals("content", File(right, "a").readText())
    }
    @Test fun cancelDoesNotNeedToLoadACorruptedLargePlan() = runBlocking {
        File(left, "a").writeText("content")
        val id = client.enqueueSync(client.previewSync(profile(SyncMode.INCREMENTAL)))
        val data = JSONObject(dao.v2Record("job", id)!!.payload).put("padding", "x".repeat(100000))
        dao.saveV2(V2Record("job", id, data.toString()))
        val chunks = V2Payloads.chunkKind("job", id)
        dao.deleteV2Raw(chunks, "000000")
        client.cancelJob(id)
        client.runPending()
        assertEquals("CANCELED", client.observeJobs().first().single().state)
        assertTrue(dao.v2Heads(chunks).isEmpty())
        assertFalse(File(right, "a").exists())
    }
    @Test fun malformedProfileDoesNotBlockOtherScheduleRecovery() = runBlocking {
        dao.saveV2(V2Record("profile", "bad", "{"))
        val valid = profile(SyncMode.INCREMENTAL).copy(intervalMinutes = 15)
        dao.saveV2(V2Record("profile", valid.id, V2Codec.profile(valid).toString()))
        client.recoverSchedules()
        val work = androidx.work.WorkManager.getInstance(RuntimeEnvironment.getApplication())
            .getWorkInfosForUniqueWork("nas-v2-profile-${valid.id}").get()
        assertEquals(1, work.size)
    }
    @Test fun scheduleWithDeletionsWaitsForManualConfirmation() = runBlocking {
        File(right, "extra").writeText("keep")
        val p = profile(SyncMode.MIRROR)
        dao.saveV2(V2Record("profile", p.id, V2Codec.profile(p).toString()))
        client.periodic(p.id)
        assertEquals(listOf(p.id), client.pendingProfiles()); assertTrue(client.observeJobs().first().isEmpty())
        assertEquals("keep", File(right, "extra").readText())
    }
    @Test fun interruptedDestructiveJobIsNotReplayed() = runBlocking {
        dao.saveV2(V2Record("job", "interrupted", JSONObject().put("state", "RUNNING").put("name", "test").toString()))
        client.runPending()
        assertEquals("FAILED", client.observeJobs().first().single().state)
    }
    @Test fun archiveRuleRespectsExtensionsAgeAndDestinationBoundary() = runBlocking {
        File(left, "a.pdf").writeText("pdf"); File(left, "b.txt").writeText("txt")
        File(left, "archive/old.pdf").apply { parentFile.mkdirs(); writeText("old") }
        val rule = ArchiveRule("rule", "pdf", "left", "", setOf("pdf"), 0, "archive")
        client.saveRule(rule)
        val preview = client.previewArchive(client.rules().single())
        assertEquals(listOf("a.pdf"), preview.items.map { it.source.path })
        assertEquals("archive/a.pdf", preview.items.single().targetPath)
    }
    @Test fun previewHasBoundedSizeAndRejectsPrivateArea() = runBlocking {
        File(left, "text.txt").writeText("hello")
        assertEquals("hello", client.previewContent("left", "text.txt").toString(Charsets.UTF_8))
        try { client.previewContent("left", "text.txt", 4); fail() } catch (_: IllegalArgumentException) { }
        try { client.previewContent("left", ".nas-recycle/x"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun twoWayExecutesBothDirectionsAndCommitsBaselineAfterVerification() = runBlocking {
        File(left, "left-only").writeText("left payload"); File(right, "right-only").writeText("right payload")
        client.enqueueSync(client.previewSync(profile())); drain()
        assertEquals("left payload", File(right, "left-only").readText())
        assertEquals("right payload", File(left, "right-only").readText())
        assertEquals("SUCCEEDED", client.observeJobs().first().single().state)
        assertNotNull(dao.v2Record("baseline", "p"))
        assertTrue(client.previewSync(profile()).items.all { it.action == AdvancedAction.SAME })
    }
    @Test fun confirmedMirrorRecyclesExtraFilesAndEmptyDirectories() = runBlocking {
        File(right, "extra").writeText("preserve old"); File(right, "empty").mkdir()
        client.enqueueSync(client.previewSync(profile(SyncMode.MIRROR))); drain()
        assertFalse(File(right, "extra").exists()); assertFalse(File(right, "empty").exists())
        assertEquals(2, client.recycles().size)
        val directory = client.recycles().first { it.directory }
        assertTrue(File(right, client.restore(directory)).isDirectory)
    }
    @Test fun selectedConflictWinnerPreservesLosingVersion() = runBlocking {
        File(left, "a").writeText("left version"); File(right, "a").writeText("right version")
        client.enqueueSync(client.previewSync(profile()), 1); drain()
        assertEquals("left version", File(right, "a").readText())
        assertEquals("right version", File(right, client.recycles().single().storedPath).readText())
        assertEquals("SUCCEEDED", client.observeJobs().first().single().state)
    }
    @Test fun baselineDeletionPropagatesOnlyAfterNewPreviewConfirmation() = runBlocking {
        File(left, "a").writeText("same"); File(right, "a").writeText("same")
        client.enqueueSync(client.previewSync(profile())); drain()
        File(left, "a").delete()
        val preview = client.previewSync(profile())
        assertEquals(AdvancedAction.RECYCLE_RIGHT, preview.items.single().action)
        assertTrue(File(right, "a").exists())
        client.enqueueSync(preview); drain()
        assertFalse(File(right, "a").exists())
        assertEquals(AdvancedAction.TOMBSTONE, client.previewSync(profile()).items.single().action)
    }
    @Test fun automaticArchiveVerifiesDestinationBeforeRecyclingOriginal() = runBlocking {
        File(left, "a.pdf").writeText("archive content")
        val rule = ArchiveRule("r", "scheduled", "left", "", setOf("pdf"), 0, "archive", 60)
        client.saveRule(rule); client.periodicRule(rule.id); drain()
        assertEquals("archive content", File(left, "archive/a.pdf").readText())
        assertFalse(File(left, "a.pdf").exists())
        assertEquals("archive content", File(left, client.recycles().single().storedPath).readText())
    }
    @Test fun analysisKeepsFilesBeyondFirstPageAndUsesStableSizeOrder() = runBlocking {
        repeat(205) { index -> File(left, "file-%03d".format(index)).writeBytes(ByteArray(index + 1)) }
        val analysis = client.analyze("left", "")
        assertEquals(205, analysis.scannedFiles)
        assertEquals(205, analysis.largeFiles.size)
        assertEquals("file-204", analysis.largeFiles.first().path)
        assertEquals("file-000", analysis.largeFiles.last().path)
        assertTrue(analysis.duplicates.isEmpty())
    }
    @Test fun sameSizeDifferentContentsAreNotDuplicates() = runBlocking {
        File(left, "a").writeText("aa"); File(left, "b").writeText("bb"); File(left, "c").writeText("aa")
        val analysis = client.analyze("left", "")
        assertEquals(listOf("a", "b", "c"), analysis.largeFiles.map { it.path })
        assertEquals(listOf("a", "c"), analysis.duplicates.single().files.map { it.metadata.path })
    }
    @Test fun canceledQueuedJobCannotMutateFiles() = runBlocking {
        File(left, "a").writeText("same"); File(left, "b").writeText("same")
        val id = client.cleanup("left", client.analyze("left", "").duplicates, setOf("b"))
        client.cancelJob(id); client.runPending()
        assertTrue(File(left, "b").exists()); assertEquals("CANCELED", client.observeJobs().first().single().state)
    }
    @Test fun scanProgressIdentifiesBothSidesAndContentVerificationWithoutPaths() = runBlocking {
        File(left, "a").writeText("same"); File(right, "a").writeText("same")
        val events = mutableListOf<SyncScanProgress>()
        val preview = client.previewSync(profile()) { events += it }
        assertFalse(AdvancedSyncPlanner.changed(preview))
        listOf(0, 1).forEach { side ->
            assertTrue(events.any { it.side == side && it.stage == SyncScanStage.LISTING })
            assertTrue(events.any { it.side == side && it.stage == SyncScanStage.VERIFYING && it.completed == 1 && it.total == 1 })
        }
    }
    @Test fun submissionReportsRealRecheckProgressAndStillDeduplicatesWithoutCopying() = runBlocking {
        File(left, "a").writeText("source")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        val events = java.util.Collections.synchronizedList(mutableListOf<SyncScanProgress>())
        val id = client.enqueueSync(preview) { events += it }
        assertTrue(events.any { it.side == 0 && it.stage == SyncScanStage.VERIFYING && it.completed == 1 })
        assertTrue(events.any { it.side == 1 && it.stage == SyncScanStage.LISTING })
        events.clear()
        assertEquals(id, client.enqueueSync(preview) { events += it })
        assertTrue(events.isEmpty())
        assertEquals(1, client.observeJobs().first().size)
        assertTrue(right.listFiles()!!.isEmpty())
    }
    @Test fun progressSubmissionStillRejectsChangedTargetAndCancellationBeforeQueueing() = runBlocking {
        File(left, "a").writeText("source")
        val preview = client.previewSync(profile(SyncMode.INCREMENTAL))
        try {
            client.enqueueSync(preview) { throw CancellationException("cancel recheck") }
            fail("Canceled recheck cannot create a job")
        } catch (_: CancellationException) { }
        assertTrue(client.observeJobs().first().isEmpty())
        File(right, "a").writeText("external")
        try { client.enqueueSync(preview) { }; fail("Target changed") }
        catch (error: TransferException) { assertEquals(TransferError.SOURCE_CHANGED, error.error) }
        assertEquals("external", File(right, "a").readText())
        assertTrue(client.observeJobs().first().isEmpty())
    }
}
