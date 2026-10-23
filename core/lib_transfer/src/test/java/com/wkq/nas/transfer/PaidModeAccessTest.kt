package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaidModeAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: TransferDatabase
    private lateinit var client: V2Client
    private var advancedAllowed = false
    private val profile get() = AdvancedSyncProfile("free", "Manual", "left", "", "right", "", SyncMode.INCREMENTAL)

    @Before fun setup() = runBlocking {
        advancedAllowed = false
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        db = TransferDatabase.get(context)
        db.dao().saveConnection(ConnectionEntity("left", "LOCAL", temporary.newFolder("left").path, "", "", ""))
        db.dao().saveConnection(ConnectionEntity("right", "LOCAL", temporary.newFolder("right").path, "", "", ""))
        client = V2Client(context) { advancedAllowed }
    }

    @After fun cleanup() {
        db.close()
        TransferDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TransferClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.deleteDatabase("nas-transfers.db")
    }

    private suspend fun denied(action: suspend () -> Unit) {
        try { action(); fail("未购买不能执行高级功能") }
        catch (error: TransferException) { assertEquals(TransferError.UNSUPPORTED, error.error) }
    }

    @Test fun freeUserCanSaveManualIncrementalSync() = runBlocking {
        client.saveProfile(profile)
        assertEquals(profile, client.observeProfiles().first().single())
    }

    @Test fun freeUserCannotSaveAdvancedModesOrAutomaticConditions() = runBlocking {
        for (value in listOf(profile.copy(mode = SyncMode.MIRROR), profile.copy(mode = SyncMode.TWO_WAY),
            profile.copy(intervalMinutes = 15), profile.copy(deviceTrigger = true))) {
            denied { client.saveProfile(value) }
        }
        assertTrue(client.observeProfiles().first().isEmpty())
    }

    @Test fun existingAutomaticProfileCannotExecuteWithoutEntitlement() = runBlocking {
        val saved = profile.copy(intervalMinutes = 15)
        db.dao().saveV2(V2Record("profile", saved.id, V2Codec.profile(saved).toString()))
        denied { client.periodic(saved.id) }
        assertTrue(db.dao().v2Records("job").isEmpty())
        assertNotNull(db.dao().v2Record("profile", saved.id))
    }

    @Test fun verifiedProCanSaveMirrorSync() = runBlocking {
        advancedAllowed = true
        client.saveProfile(profile.copy(mode = SyncMode.MIRROR))
        assertEquals(SyncMode.MIRROR, client.observeProfiles().first().single().mode)
    }
}
