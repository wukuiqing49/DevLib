package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "rule_scope",
    primaryKeys = ["release_id", "scope_id"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoRuleEntity::class,
            parentColumns = ["release_id", "rule_id"],
            childColumns = ["release_id", "rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "applicant_jurisdiction_code"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "submission_jurisdiction_code"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = IssuingAuthorityEntity::class,
            parentColumns = ["release_id", "authority_id"],
            childColumns = ["release_id", "mission_authority_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["release_id", "rule_id"]),
        Index(value = ["release_id", "applicant_jurisdiction_code"]),
        Index(value = ["release_id", "submission_jurisdiction_code"]),
        Index(value = ["release_id", "mission_authority_id"]),
    ],
)
data class RuleScopeEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "applicant_type")
    val applicantType: String,
    @ColumnInfo(name = "application_action")
    val applicationAction: String,
    @ColumnInfo(name = "residency_status")
    val residencyStatus: String? = null,
    @ColumnInfo(name = "min_age_months")
    val minAgeMonths: Int? = null,
    @ColumnInfo(name = "max_age_months")
    val maxAgeMonths: Int? = null,
    @ColumnInfo(name = "applicant_jurisdiction_code")
    val applicantJurisdictionCode: String? = null,
    @ColumnInfo(name = "submission_jurisdiction_code")
    val submissionJurisdictionCode: String? = null,
    @ColumnInfo(name = "mission_authority_id")
    val missionAuthorityId: String? = null,
)

@Entity(
    tableName = "submission_artifact",
    primaryKeys = ["release_id", "artifact_id"],
    foreignKeys = [
        ForeignKey(
            entity = ApplicationChannelEntity::class,
            parentColumns = ["release_id", "channel_id"],
            childColumns = ["release_id", "channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "channel_id"]),
        Index(value = ["release_id", "artifact_type"]),
    ],
)
data class SubmissionArtifactEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "artifact_id")
    val artifactId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "artifact_type")
    val artifactType: String,
    @ColumnInfo(name = "transport_method")
    val transportMethod: String,
    @ColumnInfo(name = "is_required")
    val isRequired: Boolean,
    @ColumnInfo(name = "accepts_user_generated_file")
    val acceptsUserGeneratedFile: Boolean? = null,
    val status: String,
)

@Entity(
    tableName = "variant_capability",
    primaryKeys = ["release_id", "variant_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutputVariantEntity::class,
            parentColumns = ["release_id", "variant_id"],
            childColumns = ["release_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "availability_mode"])],
)
data class VariantCapabilityEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "availability_mode")
    val availabilityMode: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String? = null,
)
