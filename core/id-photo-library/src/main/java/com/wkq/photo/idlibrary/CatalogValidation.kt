package com.wkq.photo.idlibrary

import com.wkq.photo.idlibrary.db.BackgroundConstraintEntity
import com.wkq.photo.idlibrary.db.CompositionConstraintEntity
import java.math.BigDecimal

internal val AVAILABILITY_MODES = setOf(
    "generatable",
    "capture_only",
    "guidance_only",
    "provider_required",
    "on_site_only",
)

internal val TRANSFORM_POLICIES = setOf("allowed", "prohibited", "conditional", "unspecified")
internal val VERIFIED_STATUSES = setOf("verified", "channel_verified")
internal val OFFICIAL_SOURCE_TIERS = setOf(
    "official_primary",
    "official_channel",
    "official_regulation",
)

internal fun isValidCompositionConstraint(value: CompositionConstraintEntity): Boolean {
    val target = value.targetValue.toDecimalOrNull()
    val minimum = value.minValue.toDecimalOrNull()
    val maximum = value.maxValue.toDecimalOrNull()
    if (target == null && minimum == null && maximum == null) return false
    if (value.targetValue != null && target == null) return false
    if (value.minValue != null && minimum == null) return false
    if (value.maxValue != null && maximum == null) return false
    if (minimum != null && maximum != null && minimum > maximum) return false
    return target == null || (minimum == null || target >= minimum) && (maximum == null || target <= maximum)
}

internal fun isValidBackgroundConstraint(value: BackgroundConstraintEntity): Boolean =
    when (value.constraintMode) {
        "semantic" -> !value.semanticCode.isNullOrBlank()
        "exact" -> !value.exactValue.isNullOrBlank() && !value.colorSpace.isNullOrBlank()
        "range" -> !value.colorSpace.isNullOrBlank() &&
            listOf(
                value.component1Min,
                value.component1Max,
                value.component2Min,
                value.component2Max,
                value.component3Min,
                value.component3Max,
            ).all { it.toDecimalOrNull() != null }
        else -> false
    }

private fun String?.toDecimalOrNull(): BigDecimal? = this?.let {
    runCatching { BigDecimal(it) }.getOrNull()
}
