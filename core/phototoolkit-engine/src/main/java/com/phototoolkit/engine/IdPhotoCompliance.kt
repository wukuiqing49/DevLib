package com.phototoolkit.engine

import kotlin.math.abs

enum class IdPhotoOutputFormat { JPEG, PNG }

val IdPhotoOutputFormat.fileExtension: String
    get() = when (this) {
        IdPhotoOutputFormat.JPEG -> "jpg"
        IdPhotoOutputFormat.PNG -> "png"
    }

fun IdPhotoOutputFormat.acceptsExtension(extension: String): Boolean = when (this) {
    IdPhotoOutputFormat.JPEG -> extension.equals("jpg", true) || extension.equals("jpeg", true)
    IdPhotoOutputFormat.PNG -> extension.equals("png", true)
}

enum class IdPhotoConstraintLevel { HARD, SOFT }

enum class IdPhotoComplianceCode {
    FACE_HEIGHT_OUT_OF_RANGE,
    TOP_MARGIN_OUT_OF_RANGE,
    EYE_LINE_OUT_OF_RANGE,
    FACE_CENTER_OUT_OF_RANGE,
    SHOULDER_LINE_OUT_OF_RANGE,
    BACKGROUND_COLOR_NOT_ALLOWED,
    BACKGROUND_CHECK_UNAVAILABLE,
    EYES_NOT_OPEN,
    EYE_OPEN_CHECK_UNAVAILABLE,
    OUTPUT_FORMAT_NOT_ALLOWED,
    OUTPUT_FILE_TOO_SMALL,
    OUTPUT_FILE_TOO_LARGE,
    OUTPUT_DIMENSIONS_INVALID,
    VISUAL_QUALITY_REVIEW_REQUIRED,
}

enum class IdPhotoComplianceSeverity { WARNING, ERROR }

enum class IdPhotoComplianceDecision { PASS, MANUAL_REVIEW, BLOCKED }

data class IdPhotoComplianceIssue(
    val code: IdPhotoComplianceCode,
    val severity: IdPhotoComplianceSeverity = IdPhotoComplianceSeverity.WARNING,
)

data class IdPhotoCompositionMetrics(
    val faceHeightRatio: Double,
    val faceTopRatio: Double,
    val eyeLineRatio: Double?,
    val faceCenterXRatio: Double,
    val shoulderLineRatio: Double?,
)

data class IdPhotoCompositionPlan(
    val faceHeightRatio: Double,
    val faceTopRatio: Double,
    val predictedEyeLineRatio: Double?,
    val faceCenterXRatio: Double,
    val predictedShoulderLineRatio: Double?,
)

data class IdPhotoComplianceReport(
    val metrics: IdPhotoCompositionMetrics,
    val issues: List<IdPhotoComplianceIssue>,
) {
    val decision: IdPhotoComplianceDecision
        get() = when {
            issues.any { it.severity == IdPhotoComplianceSeverity.ERROR } -> IdPhotoComplianceDecision.BLOCKED
            issues.isNotEmpty() -> IdPhotoComplianceDecision.MANUAL_REVIEW
            else -> IdPhotoComplianceDecision.PASS
        }
    val canExport: Boolean get() = decision != IdPhotoComplianceDecision.BLOCKED
    val isCompliant: Boolean get() = decision == IdPhotoComplianceDecision.PASS
}

object IdPhotoComplianceEvaluator {

    fun plan(spec: IdPhotoSpec, face: PortraitFace): IdPhotoCompositionPlan =
        plan(spec, PortraitHeadGeometry.fromFace(face), null)

    fun plan(spec: IdPhotoSpec, head: PortraitHeadGeometry): IdPhotoCompositionPlan =
        plan(spec, head, null)

    fun plan(
        spec: IdPhotoSpec,
        head: PortraitHeadGeometry,
        shoulders: PortraitShoulderGeometry?,
    ): IdPhotoCompositionPlan {
        val eyePositionWithinHead = head.eyeCenterY?.let { eyeY ->
            ((eyeY - head.top) / head.height).coerceIn(0.0, 1.0)
        }
        val shoulderPositionWithinHead = shoulders?.let {
            ((it.centerY - head.top) / head.height).takeIf { ratio -> ratio in 0.7..2.5 }
        }
        var bestHeight = spec.faceHeightRatio.coerceIn(spec.faceHeightMinRatio, spec.faceHeightMaxRatio)
        var bestTop = spec.faceTopRatio.coerceIn(spec.faceTopMinRatio, spec.faceTopMaxRatio)
        var bestScore = Double.POSITIVE_INFINITY
        val heightSpan = (spec.faceHeightMaxRatio - spec.faceHeightMinRatio).coerceAtLeast(0.01)
        val topSpan = (spec.faceTopMaxRatio - spec.faceTopMinRatio).coerceAtLeast(0.01)
        val heightCandidates = if (shoulderPositionWithinHead == null) {
            listOf(bestHeight)
        } else {
            (0..100).map { heightStep ->
                spec.faceHeightMinRatio +
                    (spec.faceHeightMaxRatio - spec.faceHeightMinRatio) * heightStep / 100.0
            }
        }
        for (height in heightCandidates) {
            val topCandidates = if (
                spec.eyeLineConstraint == IdPhotoConstraintLevel.HARD &&
                spec.eyeLineRatio != null && eyePositionWithinHead != null
            ) {
                listOf(
                    (spec.eyeLineRatio - eyePositionWithinHead * height)
                        .coerceIn(spec.faceTopMinRatio, spec.faceTopMaxRatio),
                )
            } else {
                (0..100).map { topStep ->
                    spec.faceTopMinRatio +
                        (spec.faceTopMaxRatio - spec.faceTopMinRatio) * topStep / 100.0
                }
            }
            for (top in topCandidates) {
                var score = square((height - spec.faceHeightRatio) / heightSpan)
                if (spec.topPolicy == IdPhotoTopPolicy.FIXED_REFERENCE) {
                    score += square((top - spec.faceTopRatio) / topSpan)
                }
                if (spec.eyeLineConstraint == IdPhotoConstraintLevel.HARD &&
                    spec.eyeLineRatio != null && eyePositionWithinHead != null
                ) {
                    score += 30.0 * square(
                        (top + eyePositionWithinHead * height - spec.eyeLineRatio) /
                            spec.eyeLineTolerance.coerceAtLeast(0.005),
                    )
                }
                if (shoulderPositionWithinHead != null && spec.shoulderLineRatio != null) {
                    score += 2.5 * square(
                        (top + shoulderPositionWithinHead * height - spec.shoulderLineRatio) /
                            spec.shoulderLineTolerance.coerceAtLeast(0.01),
                    )
                }
                if (score < bestScore) {
                    bestScore = score
                    bestHeight = height
                    bestTop = top
                }
            }
        }
        return IdPhotoCompositionPlan(
            faceHeightRatio = bestHeight,
            faceTopRatio = bestTop,
            predictedEyeLineRatio = eyePositionWithinHead?.let { bestTop + it * bestHeight },
            faceCenterXRatio = spec.faceCenterXRatio,
            predictedShoulderLineRatio = shoulderPositionWithinHead?.let { bestTop + it * bestHeight },
        )
    }

    fun actualPlan(
        face: PortraitFace,
        faceHeightRatio: Double,
        faceTopRatio: Double,
        faceCenterXRatio: Double = 0.5,
        shoulderLineRatio: Double? = null,
    ): IdPhotoCompositionPlan = actualPlan(
        head = PortraitHeadGeometry.fromFace(face),
        faceHeightRatio = faceHeightRatio,
        faceTopRatio = faceTopRatio,
        faceCenterXRatio = faceCenterXRatio,
        shoulderLineRatio = shoulderLineRatio,
    )

    fun actualPlan(
        head: PortraitHeadGeometry,
        faceHeightRatio: Double,
        faceTopRatio: Double,
        faceCenterXRatio: Double = 0.5,
        shoulderLineRatio: Double? = null,
    ): IdPhotoCompositionPlan {
        val eyePositionWithinHead = head.eyeCenterY?.let { eyeY ->
            ((eyeY - head.top) / head.height).coerceIn(0.0, 1.0)
        }
        return IdPhotoCompositionPlan(
            faceHeightRatio = faceHeightRatio,
            faceTopRatio = faceTopRatio,
            predictedEyeLineRatio = eyePositionWithinHead?.let { faceTopRatio + it * faceHeightRatio },
            faceCenterXRatio = faceCenterXRatio,
            predictedShoulderLineRatio = shoulderLineRatio,
        )
    }

    fun actualPlan(head: PortraitHeadGeometry, imageWidth: Int, imageHeight: Int): IdPhotoCompositionPlan {
        require(imageWidth > 0 && imageHeight > 0)
        return IdPhotoCompositionPlan(
            faceHeightRatio = head.height / imageHeight,
            faceTopRatio = head.top / imageHeight,
            predictedEyeLineRatio = head.eyeCenterY?.div(imageHeight.toDouble()),
            faceCenterXRatio = (head.left + head.width * 0.5) / imageWidth,
            predictedShoulderLineRatio = null,
        )
    }

    fun evaluate(
        spec: IdPhotoSpec,
        face: PortraitFace,
        plan: IdPhotoCompositionPlan,
        backgroundColor: IdPhotoColor,
        outputFormat: IdPhotoOutputFormat,
        outputBytes: Long,
        outputWidth: Int = spec.widthPx,
        outputHeight: Int = spec.heightPx,
    ): IdPhotoComplianceReport {
        fun severity(level: IdPhotoConstraintLevel): IdPhotoComplianceSeverity =
            if (spec.strictComposition || level == IdPhotoConstraintLevel.HARD) {
                IdPhotoComplianceSeverity.ERROR
            } else {
                IdPhotoComplianceSeverity.WARNING
            }
        val issues = buildList {
            if (plan.faceHeightRatio !in spec.faceHeightMinRatio..spec.faceHeightMaxRatio) {
                add(IdPhotoComplianceIssue(
                    IdPhotoComplianceCode.FACE_HEIGHT_OUT_OF_RANGE,
                    severity(spec.faceHeightConstraint),
                ))
            }
            if (spec.topPolicy == IdPhotoTopPolicy.FIXED_REFERENCE &&
                plan.faceTopRatio !in spec.faceTopMinRatio..spec.faceTopMaxRatio
            ) {
                add(IdPhotoComplianceIssue(
                    IdPhotoComplianceCode.TOP_MARGIN_OUT_OF_RANGE,
                    severity(spec.faceTopConstraint),
                ))
            }
            val expectedEyeLine = spec.eyeLineRatio
            val actualEyeLine = plan.predictedEyeLineRatio
            if (expectedEyeLine != null &&
                (actualEyeLine == null || abs(actualEyeLine - expectedEyeLine) > spec.eyeLineTolerance)
            ) {
                add(IdPhotoComplianceIssue(
                    IdPhotoComplianceCode.EYE_LINE_OUT_OF_RANGE,
                    severity(spec.eyeLineConstraint),
                ))
            }
            if (abs(plan.faceCenterXRatio - spec.faceCenterXRatio) > spec.faceCenterXTolerance) {
                add(IdPhotoComplianceIssue(
                    IdPhotoComplianceCode.FACE_CENTER_OUT_OF_RANGE,
                    severity(spec.faceCenterConstraint),
                ))
            }
            val expectedShoulderLine = spec.shoulderLineRatio
            val actualShoulderLine = plan.predictedShoulderLineRatio
            val shoulderLineUnavailableAndRequired = actualShoulderLine == null &&
                spec.shoulderLineConstraint == IdPhotoConstraintLevel.HARD
            if (expectedShoulderLine != null && (
                shoulderLineUnavailableAndRequired ||
                    actualShoulderLine != null &&
                    abs(actualShoulderLine - expectedShoulderLine) > spec.shoulderLineTolerance
                )
            ) {
                add(IdPhotoComplianceIssue(
                    IdPhotoComplianceCode.SHOULDER_LINE_OUT_OF_RANGE,
                    severity(spec.shoulderLineConstraint),
                ))
            }
            if (spec.allowedBackgroundColors.isNotEmpty()) {
                if (spec.editPolicy == IdPhotoEditPolicy.RESTRICTED) {
                    add(IdPhotoComplianceIssue(IdPhotoComplianceCode.BACKGROUND_CHECK_UNAVAILABLE))
                } else if (backgroundColor !in spec.allowedBackgroundColors) {
                    add(
                        IdPhotoComplianceIssue(
                            IdPhotoComplianceCode.BACKGROUND_COLOR_NOT_ALLOWED,
                            IdPhotoComplianceSeverity.ERROR,
                        ),
                    )
                }
            }
            spec.minEyeOpenProbability?.let { minimum ->
                val left = face.leftEyeOpenProbability
                val right = face.rightEyeOpenProbability
                when {
                    left == null || right == null -> {
                        add(
                            IdPhotoComplianceIssue(
                                IdPhotoComplianceCode.EYE_OPEN_CHECK_UNAVAILABLE,
                                // 检测不到概率只能人工复核，不能把“无法确认”当成“闭眼”阻断导出。
                                IdPhotoComplianceSeverity.WARNING,
                            ),
                        )
                    }
                    left < minimum || right < minimum -> {
                        add(
                            IdPhotoComplianceIssue(
                                IdPhotoComplianceCode.EYES_NOT_OPEN,
                                IdPhotoComplianceSeverity.ERROR,
                            ),
                        )
                    }
                }
            }
            if (outputFormat !in spec.allowedOutputFormats) {
                add(
                    IdPhotoComplianceIssue(
                        IdPhotoComplianceCode.OUTPUT_FORMAT_NOT_ALLOWED,
                        IdPhotoComplianceSeverity.ERROR,
                    ),
                )
            }
            spec.minOutputBytes?.let { minimum ->
                if (outputBytes < minimum) {
                    add(
                        IdPhotoComplianceIssue(
                            IdPhotoComplianceCode.OUTPUT_FILE_TOO_SMALL,
                            IdPhotoComplianceSeverity.ERROR,
                        ),
                    )
                }
            }
            spec.maxOutputBytes?.let { maximum ->
                if (outputBytes > maximum) {
                    add(
                        IdPhotoComplianceIssue(
                            IdPhotoComplianceCode.OUTPUT_FILE_TOO_LARGE,
                            IdPhotoComplianceSeverity.ERROR,
                        ),
                    )
                }
            }
            if (outputWidth != spec.widthPx || outputHeight != spec.heightPx) {
                add(
                    IdPhotoComplianceIssue(
                        IdPhotoComplianceCode.OUTPUT_DIMENSIONS_INVALID,
                        IdPhotoComplianceSeverity.ERROR,
                    ),
                )
            }
            // 清晰度、阴影、反光、表情、红眼、眼镜遮挡和色彩配置尚不能可靠自动判定。
            add(IdPhotoComplianceIssue(IdPhotoComplianceCode.VISUAL_QUALITY_REVIEW_REQUIRED))
        }
        return IdPhotoComplianceReport(
            metrics = IdPhotoCompositionMetrics(
                faceHeightRatio = plan.faceHeightRatio,
                faceTopRatio = plan.faceTopRatio,
                eyeLineRatio = plan.predictedEyeLineRatio,
                faceCenterXRatio = plan.faceCenterXRatio,
                shoulderLineRatio = plan.predictedShoulderLineRatio,
            ),
            issues = issues,
        )
    }

    private fun square(value: Double): Double = value * value
}
