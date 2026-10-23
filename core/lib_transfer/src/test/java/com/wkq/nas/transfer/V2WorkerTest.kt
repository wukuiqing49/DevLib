package com.wkq.nas.transfer

import androidx.work.ListenableWorker
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.work.V2Worker
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V2WorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: TransferDatabase
    private val context get() = RuntimeEnvironment.getApplication()
    private val profile = AdvancedSyncProfile("plan", "Test", "left", "gone", "right", "", SyncMode.INCREMENTAL, intervalMinutes = 15)
    @Before fun setup() = runBlocking {
        db = TransferDatabase.get(context)
        db.dao().saveConnection(ConnectionEntity("left", "LOCAL", temporary.newFolder("left").path, "", "", ""))
        db.dao().saveConnection(ConnectionEntity("right", "LOCAL", temporary.newFolder("right").path, "", "", ""))
        db.dao().saveV2(V2Record("profile", profile.id, V2Codec.profile(profile).toString()))
    }
    @After fun cleanup() {
        db.close()
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.deleteDatabase("nas-transfers.db")
    }
    private fun worker() = TestListenableWorkerBuilder.from(context, V2Worker::class.java)
        .setInputData(workDataOf("profile" to profile.id))
        .setForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }.build()

    @Test fun deletedSourceFolderStopsAutomaticRetryAndPersistsRepairReason() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        val error = JSONObject(db.dao().v2Record("schedule-error", profile.id)!!.payload)
        assertEquals("NOT_FOUND", error.getString("error"))
        assertTrue(db.dao().v2Records("job").isEmpty())
    }
    @Test fun offlineNasRemainsRetryableAndRecordsNetworkWaiting() = runBlocking {
        assertFalse(TransferConnectivity.current(context).connected)
        db.dao().saveConnection(ConnectionEntity("left", "WEBDAV", "https://nas.invalid/root/", "", "", ""))
        db.dao().saveV2(V2Record("profile", profile.id, V2Codec.profile(profile.copy(leftRoot = "")).toString()))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("NETWORK", JSONObject(db.dao().v2Record("schedule-error", profile.id)!!.payload).getString("error"))
        assertEquals("NETWORK", JSONObject(db.dao().v2Record("schedule-wait", profile.id)!!.payload).getString("state"))
    }
}
