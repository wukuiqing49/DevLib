package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "source_evidence_assessment",
    primaryKeys = ["release_id", "evidence_id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEvidenceEntity::class,
            parentColumns = ["release_id", "evidence_id"],
            childColumns = ["release_id", "evidence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "verification_status"])],
)
data class SourceEvidenceAssessmentEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "source_tier")
    val sourceTier: String,
    @ColumnInfo(name = "verification_status")
    val verificationStatus: String,
    @ColumnInfo(name = "retrieval_method")
    val retrievalMethod: String,
    @ColumnInfo(name = "http_status")
    val httpStatus: Int? = null,
    @ColumnInfo(name = "archive_reference")
    val archiveReference: String? = null,
    @ColumnInfo(name = "checked_at")
    val checkedAt: String,
)

@Entity(
    tableName = "program_source_link",
    primaryKeys = ["release_id", "program_id", "evidence_id", "field_path"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentProgramEntity::class,
            parentColumns = ["release_id", "program_id"],
            childColumns = ["release_id", "program_id"],
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
        Index(value = ["release_id", "program_id"]),
        Index(value = ["release_id", "evidence_id"]),
    ],
)
data class ProgramSourceLinkEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "program_id")
    val programId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "field_path")
    val fieldPath: String,
    @ColumnInfo(name = "source_section")
    val sourceSection: String? = null,
    @ColumnInfo(name = "source_quote")
    val sourceQuote: String? = null,
)

@Entity(
    tableName = "rule_source_link",
    primaryKeys = ["release_id", "rule_id", "evidence_id", "field_path"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoRuleEntity::class,
            parentColumns = ["release_id", "rule_id"],
            childColumns = ["release_id", "rule_id"],
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
        Index(value = ["release_id", "rule_id"]),
        Index(value = ["release_id", "evidence_id"]),
    ],
)
data class RuleSourceLinkEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "field_path")
    val fieldPath: String,
    @ColumnInfo(name = "source_section")
    val sourceSection: String? = null,
    @ColumnInfo(name = "source_quote")
    val sourceQuote: String? = null,
)

@Entity(
    tableName = "coverage_source_link",
    primaryKeys = ["release_id", "jurisdiction_code", "document_type", "evidence_id", "field_path"],
    foreignKeys = [
        ForeignKey(
            entity = CoverageStatusEntity::class,
            parentColumns = ["release_id", "jurisdiction_code", "document_type"],
            childColumns = ["release_id", "jurisdiction_code", "document_type"],
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
        Index(value = ["release_id", "jurisdiction_code", "document_type"]),
        Index(value = ["release_id", "evidence_id"]),
    ],
)
data class CoverageSourceLinkEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "jurisdiction_code")
    val jurisdictionCode: String,
    @ColumnInfo(name = "document_type")
    val documentType: String,
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "field_path")
    val fieldPath: String,
    @ColumnInfo(name = "source_section")
    val sourceSection: String? = null,
    @ColumnInfo(name = "source_quote")
    val sourceQuote: String? = null,
)
