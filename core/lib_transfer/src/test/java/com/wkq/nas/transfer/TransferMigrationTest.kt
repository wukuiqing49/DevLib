package com.wkq.nas.transfer

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import com.wkq.nas.transfer.data.TransferDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferMigrationTest {
    @Test fun versionOneCredentialsAndTaskCheckpointsSurviveMigration() = runBlocking { migrate(1) }
    @Test fun versionTwoCredentialsAndTaskCheckpointsSurviveMigration() = runBlocking { migrate(2) }
    @Test fun versionThreeCredentialsAndTaskCheckpointsSurviveMigration() = runBlocking { migrate(3) }
    private suspend fun migrate(version: Int) {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File(System.getProperty("nas.schemaDir"), "com.wkq.nas.transfer.data.TransferDatabase/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (item in 0 until indices.length()) old.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO connections (id,protocol,root,username,passwordEncrypted,hostKeySha256) VALUES ('saved','SMB','smb://host/share/','user','encrypted-placeholder','')")
            old.execSQL("INSERT INTO transfer_tasks (id,batchId,kind,sourceId,sourcePath,targetId,targetPath,requestedTargetPath,conflictPolicy,expectedSize,expectedModifiedAt,networkRequired,state,bytesTransferred,attempts,retryAt,digest,error,createdAt) VALUES ('task','batch','COPY','saved','a','saved','b','b','FAIL',4,1000,0,'PAUSED',2,1,0,'abc',NULL,1)")
            old.version = version
        }
        val migrated = Room.databaseBuilder(context, TransferDatabase::class.java, name).addMigrations(TransferDatabase.MIGRATION_1_2, TransferDatabase.MIGRATION_2_3, TransferDatabase.MIGRATION_3_4).build()
        try {
            val connection = migrated.dao().connection("saved")!!
            assertEquals("encrypted-placeholder", connection.passwordEncrypted)
            assertEquals("", connection.displayName)
            val task = migrated.dao().task("task")!!
            assertEquals("PAUSED", task.state); assertEquals(2L, task.bytesTransferred); assertEquals("abc", task.digest)
            assertFalse(task.verifyContent); assertEquals(-2L, task.targetExpectedSize)
            assertTrue(migrated.dao().operationPage(200, 0).isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
