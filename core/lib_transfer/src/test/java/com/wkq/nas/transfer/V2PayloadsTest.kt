package com.wkq.nas.transfer

import androidx.room.Room
import androidx.room.withTransaction
import com.wkq.nas.transfer.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V2PayloadsTest {
    private lateinit var db: TransferDatabase
    private val dao get() = db.dao()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TransferDatabase::class.java).build()
    }
    @After fun close() = db.close()
    private fun largePlan(): String = V2Codec.preview(AdvancedSyncPreview(
        AdvancedSyncProfile("large", "Large", "left", "", "right", "", SyncMode.INCREMENTAL),
        (0 until 10000).map { index ->
            val path = "目录/😀/" + "a".repeat(128) + "-$index.txt"
            AdvancedSyncItem(path, ContentStamp(FileMetadata(path, false, 1024, 1000), "a".repeat(64)), null, AdvancedAction.COPY_RIGHT)
        }
    )).toString()

    @Test fun tenThousandItemPlanRoundTripsWithoutOversizedRows() = runBlocking {
        val text = largePlan()
        assertTrue(text.toByteArray().size > 2 * 1024 * 1024)
        dao.saveV2(V2Record("job", "large", text))
        assertEquals(text, dao.v2Record("job", "large")!!.payload)
        assertEquals(10000, V2Codec.preview(JSONObject(dao.v2Record("job", "large")!!.payload)).items.size)
        assertEquals(text, dao.v2Records("job").single().payload)
        assertEquals(text, dao.observeV2("job").first().single().payload)
        val chunks = dao.v2Heads(V2Payloads.chunkKind("job", "large"))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.payload.toByteArray().size <= 4 * 32768 })
    }
    @Test fun legacyLargeUnicodeRecordReadsInBoundedSlices() = runBlocking {
        val text = largePlan()
        dao.saveV2Raw(V2Record("baseline", "old", text))
        assertTrue(dao.v2Head("baseline", "old")!!.payload.length < text.length)
        assertEquals(text, dao.v2Record("baseline", "old")!!.payload)
        dao.saveV2(dao.v2Record("baseline", "old")!!)
        assertTrue(dao.v2Heads(V2Payloads.chunkKind("baseline", "old")).isNotEmpty())
    }
    @Test fun overwriteDeleteAndRollbackKeepMainRecordAndChunksAtomic() = runBlocking {
        val original = largePlan()
        dao.saveV2(V2Record("pending", "p", original))
        try {
            db.withTransaction { dao.saveV2(V2Record("pending", "p", "replacement")); error("Rollback") }
        } catch (_: IllegalStateException) { }
        assertEquals(original, dao.v2Record("pending", "p")!!.payload)
        dao.saveV2(V2Record("pending", "p", "small"))
        assertEquals("small", dao.v2Record("pending", "p")!!.payload)
        assertTrue(dao.v2Heads(V2Payloads.chunkKind("pending", "p")).isEmpty())
        dao.saveV2(V2Record("pending", "p", original))
        dao.deleteV2("pending", "p")
        assertNull(dao.v2Record("pending", "p"))
        assertTrue(dao.v2Heads(V2Payloads.chunkKind("pending", "p")).isEmpty())
    }
    @Test fun failureDuringChunkWriteRollsBackToThePreviousCompletePlan() = runBlocking {
        val original = largePlan()
        dao.saveV2(V2Record("job", "p", original))
        val failing = object : TransferDao by dao {
            var writes = 0
            override suspend fun saveV2Raw(record: V2Record) {
                if (++writes == 2) throw java.io.IOException("Isolated write failure")
                dao.saveV2Raw(record)
            }
        }
        try {
            db.withTransaction { V2Payloads.save(failing, V2Record("job", "p", original + " ")) }
            fail("Write failure")
        } catch (_: java.io.IOException) { }
        assertEquals(original, dao.v2Record("job", "p")!!.payload)
    }
    @Test fun missingOrModifiedChunkIsNeverReturnedAsValidPlan() = runBlocking {
        dao.saveV2(V2Record("job", "p", largePlan()))
        val kind = V2Payloads.chunkKind("job", "p")
        dao.saveV2Raw(V2Record(kind, "000000", "tampered"))
        try { dao.v2Record("job", "p"); fail("Corrupt plan") }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
        dao.deleteV2Raw(kind, "000000")
        try { dao.v2Record("job", "p"); fail("Missing chunk") }
        catch (error: TransferException) { assertEquals(TransferError.VERIFICATION, error.error) }
    }
    @Test fun surrogateBoundaryAndMarkerTextArePreserved() = runBlocking {
        val text = "x".repeat(32767) + "😀" + "y".repeat(40000)
        dao.saveV2(V2Record("baseline", "emoji", text))
        assertEquals(text, dao.v2Record("baseline", "emoji")!!.payload)
        val marker = "\u0001nas-payload-v1:not-a-manifest"
        dao.saveV2(V2Record("batch-label", "marker", marker))
        assertEquals(marker, dao.v2Record("batch-label", "marker")!!.payload)
    }
    @Test fun largeMutationPlansAndLegacyPayloadsRemainReadableAndCleanable() = runBlocking {
        val text = largePlan()
        dao.insertMutationJob(MutationJobEntity("new", text, connectionId = "local"))
        assertEquals(V2Payloads.MUTATION_MARKER, dao.mutationHead("new")!!.payload)
        assertEquals(text, dao.nextMutationJob()!!.payload)
        dao.finishMutationJob("new", "FAILED", "VERIFICATION")
        dao.insertMutationJobRaw(MutationJobEntity("old", text, connectionId = "local"))
        assertEquals(text, dao.mutationJob("old")!!.payload)
        dao.clearFinishedMutationJobs()
        assertNull(dao.v2Record("mutation-plan", "new"))
        assertEquals(text, dao.mutationJob("old")!!.payload)
    }
}
