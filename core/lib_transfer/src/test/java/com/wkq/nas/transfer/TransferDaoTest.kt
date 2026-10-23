package com.wkq.nas.transfer

import androidx.room.Room
import com.wkq.nas.transfer.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferDaoTest {
    private lateinit var db: TransferDatabase
    private lateinit var dao: TransferDao
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TransferDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
    }
    @After fun cleanup() = db.close()
    private fun task(id: String = "a", network: Boolean = true) = TaskEntity(id, "batch", "UPLOAD", "s", "a", "t", "a", "a", "FAIL", 3, 0, network)
    @Test fun failedBatchFilterRunsBeforePaginationAndSummaryKeepsEndpoints() = runBlocking {
        dao.insertTasks((0..200).map { index -> task("item-$index").copy(sourcePath = "folder/$index", targetPath = "backup/$index",
            state = if (index == 200) "FAILED" else "SUCCEEDED", createdAt = index.toLong()) })
        assertEquals(200, dao.observeTransferBatch("batch", 200).first().size)
        val failed = dao.observeTransferBatchFiltered("batch", 200, true).first()
        assertEquals(listOf("item-200"), failed.map { it.id })
        val group = dao.observeUserTaskGroups(200).first().single()
        assertEquals(201, group.total); assertEquals(1, group.failed)
        assertEquals("s", group.sourceId); assertEquals("t", group.targetId)
        assertEquals(200L, group.createdAt)
        assertTrue(group.sourcePath.startsWith("folder/"))
    }
    @Test fun failedOperationFilterIncludesFailuresBeyondFirstPage() = runBlocking {
        dao.insertOperations((0..200).map { index -> OperationEntity("operation-$index", "operation-batch", "storage", "RENAME",
            "old/$index", "new/$index", if (index == 200) "FAILED" else "SUCCEEDED", null, index.toLong()) })
        assertEquals(listOf("operation-200"), dao.observeOperationBatchFiltered("operation-batch", 200, true).first().map { it.id })
        val group = dao.observeUserTaskGroups(200).first().single()
        assertEquals("RENAME", group.kind); assertEquals("storage", group.sourceId)
        assertEquals("storage", group.targetId)
    }
    @Test fun queuedMutationWithoutFileResultsStillHasUsableAggregate() = runBlocking {
        dao.insertMutationJob(MutationJobEntity("waiting", "{}", connectionId = "storage"))
        val group = dao.observeUserTaskGroups(200).first().single()
        assertEquals(0, group.total); assertEquals(1, group.active)
        assertEquals("storage", group.sourceId); assertEquals("", group.sourcePath)
    }
    @Test fun onlyOneWorkerCanClaimTask() = runBlocking {
        dao.insertTasks(listOf(task()))
        assertEquals(1, dao.claim("a"))
        assertEquals(0, dao.claim("a"))
        assertEquals(1, dao.task("a")!!.attempts)
    }
    @Test fun pauseAndCancelCannotBeOverwrittenByFailure() = runBlocking {
        dao.insertTasks(listOf(task()))
        dao.claim("a")
        assertEquals(1, dao.pause("a"))
        assertEquals(0, dao.finish("a", "FAILED"))
        assertEquals(1, dao.cancel("a"))
        assertEquals(0, dao.beginCommit("a", "hash"))
        assertEquals("CANCELED", dao.task("a")!!.state)
    }
    @Test fun commitBoundaryCannotBePausedOrCanceled() = runBlocking {
        dao.insertTasks(listOf(task()))
        dao.claim("a")
        assertEquals(1, dao.beginCommit("a", "hash"))
        assertEquals(0, dao.pause("a"))
        assertEquals(0, dao.cancel("a"))
        assertEquals(1, dao.finish("a", "SUCCEEDED"))
    }
    @Test fun recoveryRetainsCheckpointsAndDoesNotResumePausedTasks() = runBlocking {
        dao.insertTasks(listOf(task("a"), task("b"), task("c", false)))
        dao.claim("a"); dao.progress("a", 2); dao.beginCommit("a", "hash")
        dao.claim("b"); dao.pause("b")
        dao.claim("c")
        dao.recoverInterrupted(true)
        assertEquals("QUEUED", dao.task("a")!!.state)
        assertEquals("hash", dao.task("a")!!.digest)
        assertEquals(2L, dao.task("a")!!.bytesTransferred)
        assertEquals("PAUSED", dao.task("b")!!.state)
        assertEquals("RUNNING", dao.task("c")!!.state)
    }
    @Test fun retryWaitDoesNotBlockReadyTasks() = runBlocking {
        dao.insertTasks(listOf(task("a"), task("b")))
        dao.claim("a"); dao.finish("a", "RETRY_WAIT", "NETWORK", 10000)
        assertEquals("b", dao.next(0, true)!!.id)
        dao.claim("b"); dao.finish("b", "SUCCEEDED")
        assertNull(dao.next(0, true))
        assertEquals("a", dao.next(10000, true)!!.id)
    }
    @Test fun systemDeferralPreservesCommitDigestsAndPriorFailures() = runBlocking {
        dao.insertTasks(listOf(task()))
        dao.claim("a"); dao.finish("a", "RETRY_WAIT", "NETWORK", 10)
        dao.claim("a"); dao.progress("a", 2); dao.beginCommit("a", "verified")
        dao.backupDigest("a", "original")
        assertEquals(1, dao.deferInterrupted("a"))
        val recovered = dao.task("a")!!
        assertEquals("QUEUED", recovered.state); assertEquals(1, recovered.attempts)
        assertEquals("verified", recovered.digest); assertEquals("original", recovered.backupDigest)
        assertEquals(2L, recovered.bytesTransferred)
        assertEquals(0, dao.deferInterrupted("a"))
    }
    @Test fun systemDeferralCannotResumePausedCanceledOrCompletedTasks() = runBlocking {
        dao.insertTasks(listOf(task("paused"), task("canceled"), task("done")))
        dao.claim("paused"); dao.pause("paused")
        dao.claim("canceled"); dao.cancel("canceled")
        dao.claim("done"); dao.finish("done", "SUCCEEDED")
        listOf("paused", "canceled", "done").forEach { assertEquals(0, dao.deferInterrupted(it)) }
        dao.recoverInterrupted(true)
        assertEquals("PAUSED", dao.task("paused")!!.state)
        assertEquals("CANCELED", dao.task("canceled")!!.state)
        assertEquals("SUCCEEDED", dao.task("done")!!.state)
    }
    @Test fun localAndNetworkTasksUseSeparateConstraints() = runBlocking {
        dao.insertTasks(listOf(task("remote"), task("local", false)))
        assertEquals("local", dao.next(0, false)!!.id)
        assertEquals("remote", dao.next(0, true)!!.id)
    }
    @Test fun operationTotalsAreNotLimitedToTheVisibleWindow() = runBlocking {
        dao.insertOperations((0..1001).map { i -> OperationEntity("op-$i", "batch", "storage", "DELETE", "file-$i", "",
            if (i < 5) "FAILED" else "SUCCEEDED", null, i.toLong()) })
        val totals = dao.observeOperationTotals().first()
        assertEquals(1002, totals.total)
        assertEquals(997, totals.completed)
        assertEquals(5, totals.failed)
    }
    @Test fun userGroupsCountAllFilesBeforeApplyingWindow() = runBlocking {
        dao.insertTasks((0..1001).map { i -> task("group-$i").copy(
            state = if (i < 5) "FAILED" else "SUCCEEDED", bytesTransferred = 3) })
        val group = dao.observeUserTaskGroups(1).first().single()
        assertEquals(1002, group.total)
        assertEquals(997, group.completed)
        assertEquals(5, group.failed)
        assertEquals(3006L, group.bytes)
    }
    @Test fun parentJobHidesItsTransferChildren() = runBlocking {
        dao.insertTasks(listOf(task()))
        dao.saveV2(V2Record("job-status", "batch", "{}"))
        assertTrue(dao.observeUserTaskGroups(20).first().isEmpty())
        assertEquals(1, dao.observeTransferBatch("batch", 20).first().size)
    }
    @Test fun mutationAndItsFileRecordsAppearOnce() = runBlocking {
        dao.insertMutationJob(MutationJobEntity("mutation", "{}", "SUCCEEDED"))
        dao.insertOperations(listOf(OperationEntity("file", "mutation", "s", "DELETE", "a", "", "SUCCEEDED", null, 1)))
        val group = dao.observeUserTaskGroups(20).first().single()
        assertEquals("mutation", group.id)
        assertEquals(1, group.total)
        assertEquals(1, group.completed)
    }
    @Test fun queuedMutationAppearsBeforeItCreatesFileRecords() = runBlocking {
        dao.insertMutationJob(MutationJobEntity("queued", "{}"))
        val group = dao.observeUserTaskGroups(20).first().single()
        assertEquals(0, group.total)
        assertEquals(1, group.active)
    }
    @Test fun syncBatchSummaryRemainsFullWhileChildrenAreHiddenFromTopLevel() = runBlocking {
        dao.saveV2(V2Record("job-status", "batch", "{}"))
        dao.insertTasks((0..500).map { task("file-$it").copy(state = if (it == 500) "FAILED" else "SUCCEEDED") })
        assertTrue(dao.observeUserTaskGroups(200).first().isEmpty())
        val summary = dao.observeTransferGroups().first().single()
        assertEquals(501, summary.total)
        assertEquals(500, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(200, dao.observeTransferBatch("batch", 200).first().size)
    }
}
