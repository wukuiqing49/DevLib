package com.wkq.photo.mediastore

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

enum class ArtifactKind { IMAGE, PDF, ZIP, JSON, CSV, OTHER }

@Entity(
    tableName = "artifact_records",
    indices = [Index(value = ["createdAtMillis"]), Index(value = ["operationType"])]
)
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val operationType: String,
    val kind: String,
    val mimeType: String,
    val displayName: String,
    val internalPath: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val mediaStoreUri: String?,
    val isPublished: Boolean
)

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: ArtifactEntity)

    @Query("SELECT * FROM artifact_records ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifact_records ORDER BY createdAtMillis DESC")
    fun loadAll(): List<ArtifactEntity>

    @Query("SELECT * FROM artifact_records WHERE id = :id LIMIT 1")
    fun findById(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifact_records WHERE mediaStoreUri = :uri LIMIT 1")
    fun findByMediaStoreUri(uri: String): ArtifactEntity?

    @Query("SELECT * FROM artifact_records WHERE taskId = :taskId AND displayName = :displayName AND isPublished = 1 ORDER BY createdAtMillis DESC LIMIT 1")
    fun findPublished(taskId: String, displayName: String): ArtifactEntity?

    @Query("UPDATE artifact_records SET mediaStoreUri = :uri, isPublished = 1 WHERE id = :id")
    fun markPublished(id: String, uri: String)

    @Query("UPDATE artifact_records SET mediaStoreUri = NULL, isPublished = 0 WHERE id = :id")
    fun markUnpublished(id: String)

    @Query("DELETE FROM artifact_records WHERE id = :id")
    fun deleteById(id: String)
}

@Database(entities = [ArtifactEntity::class], version = 1, exportSchema = false)
abstract class ArtifactDatabase : RoomDatabase() {
    abstract fun artifactDao(): ArtifactDao

    companion object {
        @Volatile private var instance: ArtifactDatabase? = null

        fun get(context: Context): ArtifactDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ArtifactDatabase::class.java,
                "feature_artifacts.db"
            ).build().also { instance = it }
        }
    }
}

object InternalArtifactStore {
    private const val ROOT_DIR = "artifacts"

    fun archive(context: Context, request: MediaExportRequest): ArtifactEntity {
        require(request.source.isFile && request.source.length() > 0L) { "Archive source is missing or empty" }
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "$ROOT_DIR/$id").apply { mkdirs() }
        val target = File(directory, safeName(request.displayName))
        val temporary = File(directory, ".${target.name}.part")
        try {
            request.source.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    copyExactly(input, output, request.source.length())
                }
            }
            check(temporary.renameTo(target)) { "Unable to commit internal artifact" }
            check(target.length() == request.source.length()) { "Internal artifact size does not match source" }
            val record = ArtifactEntity(
                id = id,
                taskId = request.taskId,
                operationType = resolveOperationType(request),
                kind = kindFor(request.mimeType, target.extension).name,
                mimeType = request.mimeType,
                displayName = target.name,
                internalPath = target.absolutePath,
                sizeBytes = target.length(),
                createdAtMillis = System.currentTimeMillis(),
                mediaStoreUri = null,
                isPublished = false
            )
            ArtifactDatabase.get(context).artifactDao().insert(record)
            return record
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun markPublished(context: Context, id: String, uri: String) {
        ArtifactDatabase.get(context).artifactDao().markPublished(id, uri)
        enforceRetention(context)
    }

    fun markUnpublished(context: Context, id: String) {
        ArtifactDatabase.get(context).artifactDao().markUnpublished(id)
        enforceRetention(context)
    }

    fun delete(context: Context, id: String) {
        val dao = ArtifactDatabase.get(context).artifactDao()
        dao.findById(id)?.internalPath?.let { path -> File(path).parentFile?.deleteRecursively() }
        dao.deleteById(id)
    }

    fun observeAll(context: Context): Flow<List<ArtifactEntity>> =
        ArtifactDatabase.get(context).artifactDao().observeAll()

    fun retainedSizeBytes(context: Context): Long =
        ArtifactDatabase.get(context).artifactDao().loadAll().sumOf { it.sizeBytes.coerceAtLeast(0L) }

    fun findById(context: Context, id: String): ArtifactEntity? =
        ArtifactDatabase.get(context).artifactDao().findById(id)

    fun findPublished(context: Context, taskId: String, displayName: String): ArtifactEntity? =
        ArtifactDatabase.get(context).artifactDao().findPublished(taskId, displayName)

    fun deleteByPublishedUri(context: Context, uri: String) {
        ArtifactDatabase.get(context).artifactDao().findByMediaStoreUri(uri)?.let { record ->
            delete(context, record.id)
        }
    }

    fun resolveFile(context: Context, id: String): File? =
        findById(context, id)?.internalPath?.let(::File)?.takeIf { it.isFile }

    private fun enforceRetention(context: Context) {
        val records = ArtifactDatabase.get(context).artifactDao().loadAll()
        val retainedIds = retainedArtifactIds(records, MAX_RETAINED_ARTIFACTS, MAX_RETAINED_BYTES)
        records.filterNot { it.id in retainedIds }.forEach { delete(context, it.id) }
    }

    private fun kindFor(mimeType: String, extension: String): ArtifactKind = when {
        mimeType.startsWith("image/") -> ArtifactKind.IMAGE
        mimeType == "application/pdf" || extension.equals("pdf", ignoreCase = true) -> ArtifactKind.PDF
        mimeType == "application/zip" || extension.equals("zip", ignoreCase = true) -> ArtifactKind.ZIP
        mimeType == "application/json" || extension.equals("json", ignoreCase = true) -> ArtifactKind.JSON
        mimeType == "text/csv" || extension.equals("csv", ignoreCase = true) -> ArtifactKind.CSV
        else -> ArtifactKind.OTHER
    }

    private fun resolveOperationType(request: MediaExportRequest): String {
        if (request.operationType.isNotBlank() && request.operationType != "unknown") {
            return request.operationType
        }
        val path = request.relativePath.orEmpty().lowercase()
        return when {
            "compress" in path -> "compression"
            "resize" in path -> "resize"
            "convert" in path -> "format_convert"
            "rename" in path -> "batch_rename"
            "privacy" in path -> "privacy"
            "location" in path -> "location"
            "tool" in path -> "tools"
            else -> "unknown"
        }
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .ifBlank { "artifact.bin" }

    private const val MAX_RETAINED_ARTIFACTS = 50
    private const val MAX_RETAINED_BYTES = 256L * 1024L * 1024L
}

internal fun retainedArtifactIds(
    records: List<ArtifactEntity>,
    maxCount: Int = 50,
    maxBytes: Long = 256L * 1024L * 1024L,
): Set<String> {
    if (records.isEmpty() || maxCount <= 0 || maxBytes < 0L) return emptySet()
    var retainedBytes = 0L
    return buildSet {
        records.sortedByDescending(ArtifactEntity::createdAtMillis).forEachIndexed { index, record ->
            val recordBytes = record.sizeBytes.coerceAtLeast(0L)
            val keep = index == 0 ||
                (index < maxCount && retainedBytes <= maxBytes - recordBytes)
            if (keep) {
                add(record.id)
                retainedBytes = (retainedBytes + recordBytes).coerceAtLeast(retainedBytes)
            }
        }
    }
}
