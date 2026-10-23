package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证生成结果与规格契约一致，而不是比较不可稳定复现的 JPEG 字节。 */
class GeneratedIdPhotoContractTest {

    @Test
    fun standardGeneratedResultMatchesExpectedContract() {
        val spec = IdPhotoSpec.STANDARD_413_X_591
        val face = face()
        val plan = IdPhotoCompositionPlan(
            faceHeightRatio = spec.faceHeightRatio,
            faceTopRatio = spec.faceTopRatio,
            predictedEyeLineRatio = null,
            faceCenterXRatio = spec.faceCenterXRatio,
            predictedShoulderLineRatio = null,
        )

        val report = IdPhotoComplianceEvaluator.evaluate(
            spec = spec,
            face = face,
            plan = plan,
            backgroundColor = IdPhotoColor.BLUE,
            outputFormat = IdPhotoOutputFormat.JPEG,
            outputBytes = 100_000,
            outputWidth = 295,
            outputHeight = 413,
        )

        assertEquals(295, spec.widthPx)
        assertEquals(413, spec.heightPx)
        assertEquals(0.68, report.metrics.faceHeightRatio, 0.000001)
        assertEquals(0.0, report.metrics.faceTopRatio, 0.000001)
        assertEquals(0.50, report.metrics.faceCenterXRatio, 0.000001)
        assertTrue(report.canExport)
        // 清晰度、阴影、反光等仍需人工复核，不能误报为全自动 PASS。
        assertEquals(IdPhotoComplianceDecision.MANUAL_REVIEW, report.decision)
        assertEquals(
            listOf(IdPhotoComplianceCode.VISUAL_QUALITY_REVIEW_REQUIRED),
            report.issues.map { it.code },
        )
    }

    @Test
    fun generatedResultWithWrongDimensionsOrFormatIsBlocked() {
        val spec = IdPhotoSpec.STANDARD_413_X_591
        val report = IdPhotoComplianceEvaluator.evaluate(
            spec = spec,
            face = face(),
            plan = IdPhotoCompositionPlan(0.68, 0.08, null, 0.5, null),
            backgroundColor = IdPhotoColor.BLUE,
            outputFormat = IdPhotoOutputFormat.PNG,
            outputBytes = 100_000,
            outputWidth = 600,
            outputHeight = 600,
        )

        assertEquals(IdPhotoComplianceDecision.BLOCKED, report.decision)
        assertTrue(report.issues.any { it.code == IdPhotoComplianceCode.OUTPUT_FORMAT_NOT_ALLOWED })
        assertTrue(report.issues.any { it.code == IdPhotoComplianceCode.OUTPUT_DIMENSIONS_INVALID })
    }

    private fun face() = PortraitFace(
        left = 100,
        top = 100,
        width = 200,
        height = 200,
        leftEyeX = 160f,
        leftEyeY = 180f,
        rightEyeX = 240f,
        rightEyeY = 180f,
        leftEyeOpenProbability = 0.95f,
        rightEyeOpenProbability = 0.95f,
        pitchDegrees = 0f,
        yawDegrees = 0f,
        rollDegrees = 0f,
    )
}
