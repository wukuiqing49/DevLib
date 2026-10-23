package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "transform_policy",
    primaryKeys = ["release_id", "rule_id", "operation"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoRuleEntity::class,
            parentColumns = ["release_id", "rule_id"],
            childColumns = ["release_id", "rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "rule_id"])],
)
data class TransformPolicyEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    val operation: String,
    val policy: String,
    @ColumnInfo(name = "condition_code")
    val conditionCode: String? = null,
)

@Entity(
    tableName = "composition_constraint",
    primaryKeys = ["release_id", "constraint_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutputVariantEntity::class,
            parentColumns = ["release_id", "variant_id"],
            childColumns = ["release_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "variant_id"]),
        Index(value = ["release_id", "variant_id", "measurement"]),
    ],
)
data class CompositionConstraintEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "constraint_id")
    val constraintId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val measurement: String,
    @ColumnInfo(name = "from_landmark")
    val fromLandmark: String? = null,
    @ColumnInfo(name = "to_landmark")
    val toLandmark: String? = null,
    @ColumnInfo(name = "reference_frame")
    val referenceFrame: String,
    @ColumnInfo(name = "subject_scope")
    val subjectScope: String,
    @ColumnInfo(name = "target_value")
    val targetValue: String? = null,
    @ColumnInfo(name = "min_value")
    val minValue: String? = null,
    @ColumnInfo(name = "max_value")
    val maxValue: String? = null,
    val unit: String,
    @ColumnInfo(name = "constraint_level")
    val constraintLevel: String,
)

@Entity(
    tableName = "background_constraint",
    primaryKeys = ["release_id", "constraint_id"],
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
data class BackgroundConstraintEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "constraint_id")
    val constraintId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val policy: String,
    @ColumnInfo(name = "constraint_mode")
    val constraintMode: String,
    @ColumnInfo(name = "semantic_code")
    val semanticCode: String? = null,
    @ColumnInfo(name = "exact_value")
    val exactValue: String? = null,
    @ColumnInfo(name = "color_space")
    val colorSpace: String? = null,
    @ColumnInfo(name = "component_1_min")
    val component1Min: String? = null,
    @ColumnInfo(name = "component_1_max")
    val component1Max: String? = null,
    @ColumnInfo(name = "component_2_min")
    val component2Min: String? = null,
    @ColumnInfo(name = "component_2_max")
    val component2Max: String? = null,
    @ColumnInfo(name = "component_3_min")
    val component3Min: String? = null,
    @ColumnInfo(name = "component_3_max")
    val component3Max: String? = null,
    @ColumnInfo(name = "is_preferred")
    val isPreferred: Boolean = false,
)

@Entity(
    tableName = "artifact_constraint",
    primaryKeys = ["release_id", "constraint_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutputVariantEntity::class,
            parentColumns = ["release_id", "variant_id"],
            childColumns = ["release_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "variant_id"]),
        Index(value = ["release_id", "variant_id", "attribute"]),
    ],
)
data class ArtifactConstraintEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "constraint_id")
    val constraintId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val attribute: String,
    val operator: String,
    val value: String,
    @ColumnInfo(name = "secondary_value")
    val secondaryValue: String? = null,
    val unit: String? = null,
    @ColumnInfo(name = "subject_scope")
    val subjectScope: String = "all",
    @ColumnInfo(name = "constraint_level")
    val constraintLevel: String,
)

@Entity(
    tableName = "output_encoding",
    primaryKeys = ["release_id", "variant_id", "format"],
    foreignKeys = [
        ForeignKey(
            entity = OutputFormatEntity::class,
            parentColumns = ["release_id", "variant_id", "format"],
            childColumns = ["release_id", "variant_id", "format"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "variant_id"])],
)
data class OutputEncodingEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val format: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,
    @ColumnInfo(name = "color_mode")
    val colorMode: String? = null,
    @ColumnInfo(name = "bit_depth")
    val bitDepth: Int? = null,
    @ColumnInfo(name = "min_dpi")
    val minDpi: Int? = null,
    @ColumnInfo(name = "max_dpi")
    val maxDpi: Int? = null,
    @ColumnInfo(name = "compression_policy")
    val compressionPolicy: String? = null,
)
