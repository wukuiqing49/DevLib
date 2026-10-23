package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_release")
data class CatalogReleaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "data_version")
    val dataVersion: Long,
    @ColumnInfo(name = "published_at")
    val publishedAt: String,
    @ColumnInfo(name = "imported_at")
    val importedAt: String,
    val status: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "signature_key_id")
    val signatureKeyId: String,
)

@Entity(
    tableName = "catalog_state",
    foreignKeys = [
        ForeignKey(
            entity = CatalogReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["active_release_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("active_release_id")],
)
data class CatalogStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "active_release_id")
    val activeReleaseId: String,
) {
    init {
        require(singletonId == SINGLETON_ID)
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "locale_pack",
    primaryKeys = ["release_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["release_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("release_id")],
)
data class LocalePackEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    @ColumnInfo(name = "pack_version")
    val packVersion: Long,
    val status: String,
    @ColumnInfo(name = "published_at")
    val publishedAt: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "signature_key_id")
    val signatureKeyId: String,
)
