package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "requirement",
    primaryKeys = ["release_id", "requirement_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutputVariantEntity::class,
            parentColumns = ["release_id", "variant_id"],
            childColumns = ["release_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "variant_id"])],
)
data class RequirementEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "requirement_id")
    val requirementId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val category: String,
    val severity: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)

@Entity(
    tableName = "requirement_text",
    primaryKeys = ["release_id", "requirement_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = RequirementEntity::class,
            parentColumns = ["release_id", "requirement_id"],
            childColumns = ["release_id", "requirement_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "requirement_id"]),
        Index(value = ["release_id", "locale_tag"]),
    ],
)
data class RequirementTextEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "requirement_id")
    val requirementId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val text: String,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "source_evidence",
    primaryKeys = ["release_id", "evidence_id"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["release_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("release_id"), Index(value = ["release_id", "authority_code"])],
)
data class SourceEvidenceEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "authority_code")
    val authorityCode: String,
    @ColumnInfo(name = "source_language")
    val sourceLanguage: String,
    @ColumnInfo(name = "original_title")
    val originalTitle: String,
    @ColumnInfo(name = "original_quote")
    val originalQuote: String? = null,
    val url: String,
    @ColumnInfo(name = "document_reference")
    val documentReference: String? = null,
    @ColumnInfo(name = "published_at")
    val publishedAt: String? = null,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: String? = null,
    @ColumnInfo(name = "effective_to")
    val effectiveTo: String? = null,
    @ColumnInfo(name = "retrieved_at")
    val retrievedAt: String,
    @ColumnInfo(name = "verified_at")
    val verifiedAt: String? = null,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String? = null,
)

@Entity(
    tableName = "source_evidence_translation",
    primaryKeys = ["release_id", "evidence_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEvidenceEntity::class,
            parentColumns = ["release_id", "evidence_id"],
            childColumns = ["release_id", "evidence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "evidence_id"])],
)
data class SourceEvidenceTranslationEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    @ColumnInfo(name = "translated_title")
    val translatedTitle: String? = null,
    @ColumnInfo(name = "translated_summary")
    val translatedSummary: String,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "variant_source_link",
    primaryKeys = ["release_id", "variant_id", "evidence_id", "field_path"],
    foreignKeys = [
        ForeignKey(
            entity = OutputVariantEntity::class,
            parentColumns = ["release_id", "variant_id"],
            childColumns = ["release_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEvidenceEntity::class,
            parentColumns = ["release_id", "evidence_id"],
            childColumns = ["release_id", "evidence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "variant_id"]),
        Index(value = ["release_id", "evidence_id"]),
    ],
)
data class VariantSourceLinkEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "field_path")
    val fieldPath: String,
    @ColumnInfo(name = "source_section")
    val sourceSection: String? = null,
    @ColumnInfo(name = "source_quote")
    val sourceQuote: String? = null,
)
