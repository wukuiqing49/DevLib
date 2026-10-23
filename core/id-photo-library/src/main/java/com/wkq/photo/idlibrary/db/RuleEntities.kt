package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "photo_rule",
    primaryKeys = ["release_id", "rule_id"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentProgramEntity::class,
            parentColumns = ["release_id", "program_id"],
            childColumns = ["release_id", "program_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ApplicationChannelEntity::class,
            parentColumns = ["release_id", "channel_id"],
            childColumns = ["release_id", "channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "program_id"]),
        Index(value = ["release_id", "channel_id"]),
        Index(value = ["release_id", "status"]),
    ],
)
data class PhotoRuleEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "program_id")
    val programId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String? = null,
    val status: String,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: String,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: String? = null,
    @ColumnInfo(name = "effective_to")
    val effectiveTo: String? = null,
    @ColumnInfo(name = "capture_policy")
    val capturePolicy: String,
    @ColumnInfo(name = "edit_policy")
    val editPolicy: String,
)

@Entity(
    tableName = "output_variant",
    primaryKeys = ["release_id", "variant_id"],
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
data class OutputVariantEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    val delivery: String,
    val width: String,
    val height: String,
    val unit: String,
    @ColumnInfo(name = "dimension_mode")
    val dimensionMode: String,
    @ColumnInfo(name = "min_width")
    val minWidth: String? = null,
    @ColumnInfo(name = "min_height")
    val minHeight: String? = null,
    @ColumnInfo(name = "max_width")
    val maxWidth: String? = null,
    @ColumnInfo(name = "max_height")
    val maxHeight: String? = null,
    val dpi: Int,
    @ColumnInfo(name = "min_bytes")
    val minBytes: Long? = null,
    @ColumnInfo(name = "max_bytes")
    val maxBytes: Long? = null,
    @ColumnInfo(name = "color_space")
    val colorSpace: String,
)

@Entity(
    tableName = "output_variant_text",
    primaryKeys = ["release_id", "variant_id", "locale_tag"],
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
data class OutputVariantTextEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "output_format",
    primaryKeys = ["release_id", "variant_id", "format"],
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
data class OutputFormatEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    val format: String,
)

@Entity(
    tableName = "allowed_background",
    primaryKeys = ["release_id", "variant_id", "color_hex"],
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
data class AllowedBackgroundEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "color_hex")
    val colorHex: String,
    @ColumnInfo(name = "is_preferred")
    val isPreferred: Boolean,
)

@Entity(
    tableName = "composition_rule",
    primaryKeys = ["release_id", "variant_id"],
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
data class CompositionRuleEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "variant_id")
    val variantId: String,
    @ColumnInfo(name = "head_measurement")
    val headMeasurement: String,
    @ColumnInfo(name = "face_height_ratio")
    val faceHeightRatio: Double,
    @ColumnInfo(name = "face_height_min_ratio")
    val faceHeightMinRatio: Double,
    @ColumnInfo(name = "face_height_max_ratio")
    val faceHeightMaxRatio: Double,
    @ColumnInfo(name = "face_height_constraint")
    val faceHeightConstraint: String,
    @ColumnInfo(name = "face_top_ratio")
    val faceTopRatio: Double,
    @ColumnInfo(name = "face_top_min_ratio")
    val faceTopMinRatio: Double,
    @ColumnInfo(name = "face_top_max_ratio")
    val faceTopMaxRatio: Double,
    @ColumnInfo(name = "face_top_constraint")
    val faceTopConstraint: String,
    /** 只有明确给出顶部构图目标时才使用 fixed_reference；旧目录数据为空即自动构图。 */
    @ColumnInfo(name = "top_policy")
    val topPolicy: String? = null,
    @ColumnInfo(name = "eye_line_ratio")
    val eyeLineRatio: Double? = null,
    @ColumnInfo(name = "eye_line_tolerance")
    val eyeLineTolerance: Double? = null,
    @ColumnInfo(name = "eye_line_constraint")
    val eyeLineConstraint: String? = null,
    @ColumnInfo(name = "face_center_x_ratio")
    val faceCenterXRatio: Double,
    @ColumnInfo(name = "face_center_x_tolerance")
    val faceCenterXTolerance: Double,
    @ColumnInfo(name = "face_center_constraint")
    val faceCenterConstraint: String,
    @ColumnInfo(name = "shoulder_line_ratio")
    val shoulderLineRatio: Double? = null,
    @ColumnInfo(name = "shoulder_line_tolerance")
    val shoulderLineTolerance: Double? = null,
    @ColumnInfo(name = "shoulder_line_constraint")
    val shoulderLineConstraint: String? = null,
    @ColumnInfo(name = "min_face_width_ratio")
    val minFaceWidthRatio: Double? = null,
    @ColumnInfo(name = "max_head_pose_degrees")
    val maxHeadPoseDegrees: Double? = null,
    @ColumnInfo(name = "min_eye_open_probability")
    val minEyeOpenProbability: Double? = null,
    @ColumnInfo(name = "portrait_bottom_ratio")
    val portraitBottomRatio: Double,
    @ColumnInfo(name = "max_portrait_width_ratio")
    val maxPortraitWidthRatio: Double,
    @ColumnInfo(name = "bottom_policy")
    val bottomPolicy: String,
    @ColumnInfo(name = "max_upscale_ratio")
    val maxUpscaleRatio: Double,
    @ColumnInfo(name = "strict_composition")
    val strictComposition: Boolean,
)

@Entity(
    tableName = "source_image_policy",
    primaryKeys = ["release_id", "rule_id", "policy"],
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
data class SourceImagePolicyEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    val policy: String,
)
