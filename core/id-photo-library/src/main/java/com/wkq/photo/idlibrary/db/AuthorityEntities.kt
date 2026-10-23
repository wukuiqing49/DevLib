package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "issuing_authority",
    primaryKeys = ["release_id", "authority_id"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["release_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "jurisdiction_code"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("release_id"),
        Index(value = ["release_id", "authority_code"], unique = true),
        Index(value = ["release_id", "jurisdiction_code"]),
    ],
)
data class IssuingAuthorityEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "authority_id")
    val authorityId: String,
    @ColumnInfo(name = "authority_code")
    val authorityCode: String,
    @ColumnInfo(name = "jurisdiction_code")
    val jurisdictionCode: String? = null,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "official_website")
    val officialWebsite: String? = null,
)

@Entity(
    tableName = "issuing_authority_text",
    primaryKeys = ["release_id", "authority_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = IssuingAuthorityEntity::class,
            parentColumns = ["release_id", "authority_id"],
            childColumns = ["release_id", "authority_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "authority_id"]),
        Index(value = ["release_id", "locale_tag"]),
    ],
)
data class IssuingAuthorityTextEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "authority_id")
    val authorityId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "jurisdiction_relation",
    primaryKeys = ["release_id", "parent_code", "child_code", "relation_type"],
    foreignKeys = [
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "parent_code"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "child_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "parent_code"]),
        Index(value = ["release_id", "child_code"]),
    ],
)
data class JurisdictionRelationEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "parent_code")
    val parentCode: String,
    @ColumnInfo(name = "child_code")
    val childCode: String,
    @ColumnInfo(name = "relation_type")
    val relationType: String,
)
