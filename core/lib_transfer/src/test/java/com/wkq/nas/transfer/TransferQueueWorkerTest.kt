package com.wkq.nas.transfer

import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.work.TransferQueueWorker
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
class TransferQueueWorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: TransferDatabase
    private lateinit var source: File
    private lateinit var target: File
    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = TransferDatabase.get(context)
        source = temporary.newFolder("source")
        target = temporary.newFolder("target")
        database.dao().saveConnection(ConnectionEntity("source", "LOCAL", source.path, "", "", ""))
        database.dao().saveConnection(ConnectionEntity("target", "LOCAL", target.path, "", "", ""))
    }
    @After fun cleanup() {
        database.close()
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        RuntimeEnvironment.getApplication().deleteDatabase("nas-transfers.db")
    }
    private fun task(id: String, path: String, network: Boolean = false): TaskEntity {
        val file = File(source, path)
        return TaskEntity(id, "batch", "COPY", "source", path, "target", path, path, "FAIL", file.length(), file.lastModified(), network)
    }
    private fun worker() = TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(), TransferQueueWorker::class.java)
        .setInputData(workDataOf(TransferQueueWorker.NETWORK_KEY to false))
        .setForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }
        .build()

    @Test fun cleanupRemovesOnlyCanceledPartAndPreservesDestinationBackupAndPausedPart() = runBlocking {
        File(target, "canceled").writeText("original")
        val part = File(target, ".nas-canceled.part").apply { writeText("partial") }
        val backup = File(target, ".nas-canceled.backup").apply { writeText("backup") }
        val paused = File(target, ".nas-paused.part").apply { writeText("paused") }
        database.dao().insertTasks(listOf(task("canceled", "canceled").copy(state = "CANCELED", conflictPolicy = "REPLACE"), task("paused", "paused").copy(state = "PAUSED")))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertFalse(part.exists()); assertEquals("original", File(target, "canceled").readText())
        assertEquals("backup", backup.readText()); assertEquals("paused", paused.readText())
        assertEquals("DONE", database.dao().v2Record("transfer-cleanup", "canceled")!!.payload)
    }
    @Test fun cleanupRefusesDirectoriesAndRotatesFailuresWithoutStarvingLaterTasks() = runBlocking {
        val dao = database.dao()
        dao.insertTasks((0..16).map { i -> task("cleanup$i", "cleanup$i").copy(state = "CANCELED", createdAt = i.toLong()) })
        (0..15).forEach { File(target, ".nas-cleanup$it.part").mkdirs() }
        val good = File(target, ".nas-cleanup16.part").apply { writeText("partial") }
        val cleanup = com.wkq.nas.transfer.work.TransferArtifactCleanup(dao, com.wkq.nas.transfer.provider.ProviderFactory(RuntimeEnvironment.getApplication())::open)
        cleanup.run(false)
        assertTrue(good.exists())
        cleanup.run(false)
        assertFalse(good.exists())
        assertTrue(File(target, ".nas-cleanup0.part").isDirectory)
        assertEquals("VERIFICATION", dao.v2Record("transfer-cleanup", "cleanup0")!!.payload)
        assertEquals(16, dao.pendingCleanupCount(false))
    }
    @Test fun cleanupRetainsRecordWhenStorageUnavailableAndHonorsQueueNetworkPartition() = runBlocking {
        val dao = database.dao()
        dao.insertTasks(listOf(task("offline", "offline").copy(state = "CANCELED", targetId = "missing"),
            task("remote-cleanup", "remote").copy(state = "CANCELED", networkRequired = true)))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("NOT_FOUND", dao.v2Record("transfer-cleanup", "offline")!!.payload)
        assertNull(dao.v2Record("transfer-cleanup", "remote-cleanup"))
        assertNotNull(dao.task("offline"))
    }
    @Test fun interruptedCleanupRemainsPendingAndRetryIsIdempotent() = runBlocking {
        val dao = database.dao()
        val part = File(target, ".nas-interrupted.part").apply { writeText("partial") }
        val destination = File(target, "interrupted").apply { writeText("original") }
        dao.insertTasks(listOf(task("interrupted", "interrupted").copy(state = "CANCELED")))
        val cleanup = com.wkq.nas.transfer.work.TransferArtifactCleanup(dao) { connection ->
            val real = com.wkq.nas.transfer.provider.LocalProvider(connection.root)
            object : com.wkq.nas.transfer.provider.StorageProvider by real {
                override fun delete(path: String) { real.delete(path); throw kotlinx.coroutines.CancellationException("system stopped worker") }
            }
        }
        try { cleanup.run(false); fail("cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(part.exists())
        assertNull(dao.v2Record("transfer-cleanup", "interrupted"))
        assertEquals(1, dao.pendingCleanupCount(false))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("DONE", dao.v2Record("transfer-cleanup", "interrupted")!!.payload)
        assertEquals("original", destination.readText())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("original", destination.readText())
    }
    @Test fun silentDeleteFailureCannotBeMarkedDone() = runBlocking {
        val dao = database.dao()
        val part = File(target, ".nas-delete-failure.part").apply { writeText("partial") }
        dao.insertTasks(listOf(task("delete-failure", "original").copy(state = "CANCELED")))
        val cleanup = com.wkq.nas.transfer.work.TransferArtifactCleanup(dao) { connection ->
            val real = com.wkq.nas.transfer.provider.LocalProvider(connection.root)
            object : com.wkq.nas.transfer.provider.StorageProvider by real { override fun delete(path: String) = Unit }
        }
        cleanup.run(false)
        assertEquals("VERIFICATION", dao.v2Record("transfer-cleanup", "delete-failure")!!.payload)
        assertTrue(part.exists()); assertEquals(1, dao.pendingCleanupCount(false))
    }
    @Test fun failedFileDoesNotBlockNextFile() = runBlocking {
        File(source, "good").writeText("payload")
        database.dao().insertTasks(listOf(task("missing", "missing"), task("good", "good")))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("FAILED", database.dao().task("missing")!!.state)
        assertEquals("SUCCEEDED", database.dao().task("good")!!.state)
        assertEquals("payload", File(target, "good").readText())
    }
    @Test fun recoveringWorkerSkipsPausedAndAlreadyCompletedFiles() = runBlocking {
        File(source, "active").writeText("active")
        File(source, "paused").writeText("paused")
        database.dao().insertTasks(listOf(task("active", "active"), task("paused", "paused")))
        database.dao().claim("active")
        database.dao().pause("paused")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("SUCCEEDED", database.dao().task("active")!!.state)
        assertEquals("PAUSED", database.dao().task("paused")!!.state)
        assertFalse(File(target, "paused").exists())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, database.dao().task("active")!!.attempts)
    }
    @Test fun committedFileRecoveryVerifiesDigestWithoutReplacingExistingTarget() = runBlocking {
        val content = "committed-payload"
        File(source, "committed").writeText(content)
        val committed = File(target, "committed").apply { writeText(content) }
        val digest = committed.inputStream().use { com.wkq.nas.transfer.provider.hash(it) }
        database.dao().insertTasks(listOf(task("committed", "committed")))
        database.dao().claim("committed")
        database.dao().beginCommit("committed", digest)
        val modified = committed.lastModified()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("SUCCEEDED", database.dao().task("committed")!!.state)
        assertEquals(digest, database.dao().task("committed")!!.digest)
        assertEquals(modified, committed.lastModified())
        assertEquals(content, committed.readText())
    }
    @Test fun canceledCopyIsNotRecoveredAndPreservesExistingTarget() = runBlocking {
        File(source, "cancel").writeText("new")
        File(target, "cancel").writeText("original")
        database.dao().insertTasks(listOf(task("cancel", "cancel").copy(conflictPolicy = "REPLACE")))
        database.dao().claim("cancel")
        database.dao().cancel("cancel")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("CANCELED", database.dao().task("cancel")!!.state)
        assertEquals("original", File(target, "cancel").readText())
    }
    @Test fun localWorkerDoesNotConsumeNetworkQueue() = runBlocking {
        File(source, "local").writeText("local")
        File(source, "network").writeText("network")
        database.dao().insertTasks(listOf(task("local", "local"), task("network", "network", true)))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("SUCCEEDED", database.dao().task("local")!!.state)
        assertEquals("QUEUED", database.dao().task("network")!!.state)
        assertFalse(File(target, "network").exists())
    }
    @Test fun replacementPersistsOldAndNewDigestsBeforeCompleting() = runBlocking {
        File(source, "replace").writeText("new-content")
        val old = File(target, "replace").apply { writeText("old-content") }
        val oldHash = old.inputStream().use { com.wkq.nas.transfer.provider.hash(it) }
        database.dao().insertTasks(listOf(task("replace", "replace").copy(conflictPolicy = "REPLACE",
            targetExpectedSize = old.length(), targetExpectedModified = old.lastModified())))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val stored = database.dao().task("replace")!!
        assertEquals("SUCCEEDED", stored.state)
        assertEquals(oldHash, stored.backupDigest)
        assertEquals(File(source, "replace").inputStream().use { com.wkq.nas.transfer.provider.hash(it) }, stored.digest)
        assertEquals("new-content", old.readText())
    }
    @Test fun revokedExternalStorageWaitsWithoutConsumingAttemptsOrBlockingOtherFiles() = runBlocking {
        File(source, "good").writeText("payload")
        database.dao().saveConnection(ConnectionEntity("usb", "SAF", "content://documents/tree/usb", "", "", ""))
        database.dao().insertTasks(listOf(task("external", "missing").copy(sourceId = "usb"), task("good", "good")))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        val waiting = requireNotNull(database.dao().task("external"))
        assertEquals("RETRY_WAIT", waiting.state); assertEquals("PERMISSION", waiting.error); assertEquals(0, waiting.attempts)
        assertEquals("SUCCEEDED", database.dao().task("good")!!.state)
        database.dao().wakeStorageTasks()
        assertEquals(0L, database.dao().task("external")!!.retryAt)
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(0, database.dao().task("external")!!.attempts)
        database.dao().pause("external"); database.dao().wakeStorageTasks()
        assertEquals("PAUSED", database.dao().task("external")!!.state)
    }
    @Test fun interruptedStorageReturnsItsAttemptAndCannotOverwriteCanceledTask() = runBlocking {
        database.dao().insertTasks(listOf(task("external", "missing")))
        database.dao().claim("external")
        database.dao().interruptedStorage("external", "STORAGE_UNAVAILABLE", 500)
        assertEquals(0, database.dao().task("external")!!.attempts)
        database.dao().claim("external"); database.dao().cancel("external")
        assertEquals(0, database.dao().interruptedStorage("external", "STORAGE_UNAVAILABLE", 500))
        assertEquals("CANCELED", database.dao().task("external")!!.state)
    }
    @Test fun repeatedSystemInterruptionsDoNotExhaustFailureBudget() = runBlocking {
        File(source, "good").writeText("payload")
        database.dao().insertTasks(listOf(task("good", "good")))
        repeat(8) {
            database.dao().claim("good")
            database.dao().recoverInterrupted(false)
            assertEquals(0, database.dao().task("good")!!.attempts)
        }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("SUCCEEDED", database.dao().task("good")!!.state)
        assertEquals(1, database.dao().task("good")!!.attempts)
    }
}
