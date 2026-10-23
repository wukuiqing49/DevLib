package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "jurisdiction",
    primaryKeys = ["release_id", "code"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["release_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("release_id"), Index(value = ["release_id", "kind"])],
)
data class JurisdictionEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    val code: String,
    val kind: String,
    @ColumnInfo(name = "alpha3_code")
    val alpha3Code: String? = null,
    @ColumnInfo(name = "numeric_code")
    val numericCode: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
)

@Entity(
    tableName = "jurisdiction_name",
    primaryKeys = ["release_id", "jurisdiction_code", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "jurisdiction_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "jurisdiction_code"]),
        Index(value = ["release_id", "locale_tag"]),
    ],
)
data class JurisdictionNameEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "jurisdiction_code")
    val jurisdictionCode: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val name: String,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "jurisdiction_membership",
    primaryKeys = ["release_id", "group_code", "member_code"],
    foreignKeys = [
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "group_code"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "member_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "group_code"]),
        Index(value = ["release_id", "member_code"]),
    ],
)
data class JurisdictionMembershipEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "group_code")
    val groupCode: String,
    @ColumnInfo(name = "member_code")
    val memberCode: String,
)

@Entity(
    tableName = "coverage_status",
    primaryKeys = ["release_id", "jurisdiction_code", "document_type"],
    foreignKeys = [
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "jurisdiction_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "jurisdiction_code"])],
)
data class CoverageStatusEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "jurisdiction_code")
    val jurisdictionCode: String,
    @ColumnInfo(name = "document_type")
    val documentType: String,
    val status: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String? = null,
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: String? = null,
)
