package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdPhotoComplianceEvaluatorTest {

    @Test
    fun planKeepsRequestedHeadAndTopWhenSpecHasNoEyeRule() {
        val spec = IdPhotoSpec(
            widthPx = 295,
            heightPx = 413,
            faceHeightRatio = 0.68,
            faceTopRatio = 0.08,
            topPolicy = IdPhotoTopPolicy.FIXED_REFERENCE,
            faceHeightMinRatio = 0.64,
            faceHeightMaxRatio = 0.72,
            faceTopMinRatio = 0.04,
            faceTopMaxRatio = 0.12,
            headMeasurement = IdPhotoHeadMeasurement.CROWN_TO_CHIN,
        )

        val plan = IdPhotoComplianceEvaluator.plan(spec, head(eyePositionWithinHead = 0.55))

        assertEquals(0.68, plan.faceHeightRatio, 0.000001)
        assertEquals(0.08, plan.faceTopRatio, 0.000001)
    }

    @Test
    fun planUsesEyeLineWithoutLeavingAllowedTopRange() {
        val spec = IdPhotoSpec(
            widthPx = 600,
            heightPx = 600,
            faceHeightRatio = 0.60,
            faceTopRatio = 0.10,
            topPolicy = IdPhotoTopPolicy.FIXED_REFERENCE,
            faceHeightMinRatio = 0.55,
            faceHeightMaxRatio = 0.65,
            faceTopMinRatio = 0.06,
            faceTopMaxRatio = 0.14,
            eyeLineRatio = 0.43,
            eyeLineConstraint = IdPhotoConstraintLevel.HARD,
            headMeasurement = IdPhotoHeadMeasurement.CROWN_TO_CHIN,
        )

        val aligned = IdPhotoComplianceEvaluator.plan(spec, head(eyePositionWithinHead = 0.55))
        val clamped = IdPhotoComplianceEvaluator.plan(spec, head(eyePositionWithinHead = 0.80))

        assertEquals(0.10, aligned.faceTopRatio, 0.000001)
        assertEquals(0.43, aligned.predictedEyeLineRatio!!, 0.000001)
        assertEquals(0.06, clamped.faceTopRatio, 0.000001)
        assertTrue(clamped.faceTopRatio in spec.faceTopMinRatio..spec.faceTopMaxRatio)
    }

    @Test
    fun softEyeLineDoesNotPushPortraitTowardTop() {
        val spec = IdPhotoSpec(
            widthPx = 600,
            heightPx = 600,
            faceHeightRatio = 0.60,
            faceTopRatio = 0.10,
            topPolicy = IdPhotoTopPolicy.FIXED_REFERENCE,
            faceHeightMinRatio = 0.55,
            faceHeightMaxRatio = 0.65,
            faceTopMinRatio = 0.06,
            faceTopMaxRatio = 0.14,
            eyeLineRatio = 0.43,
            eyeLineConstraint = IdPhotoConstraintLevel.SOFT,
            shoulderLineRatio = null,
        )

        val plan = IdPhotoComplianceEvaluator.plan(spec, head(eyePositionWithinHead = 0.80))

        assertEquals(0.10, plan.faceTopRatio, 0.000001)
        assertEquals(0.60, plan.faceHeightRatio, 0.000001)
    }

    @Test
    fun shoulderLineUsesAllowedRangeToReduceExcessBody() {
        val spec = IdPhotoSpec(
            widthPx = 413,
            heightPx = 531,
            faceHeightRatio = 0.68,
            faceTopRatio = 0.08,
            topPolicy = IdPhotoTopPolicy.FIXED_REFERENCE,
            faceHeightMinRatio = 0.62,
            faceHeightMaxRatio = 0.72,
            faceTopMinRatio = 0.04,
            faceTopMaxRatio = 0.12,
            shoulderLineRatio = 0.88,
            shoulderLineTolerance = 0.04,
        )
        val head = head(eyePositionWithinHead = 0.55)
        val shoulders = PortraitShoulderGeometry(
            leftX = 250.0,
            leftY = 600.0,
            rightX = 650.0,
            rightY = 600.0,
            confidence = 0.95f,
        )

        val plan = IdPhotoComplianceEvaluator.plan(spec, head, shoulders)

        assertTrue(plan.predictedShoulderLineRatio!! < 0.93)
        assertTrue(plan.faceHeightRatio in spec.faceHeightMinRatio..spec.faceHeightMaxRatio)
        assertTrue(plan.faceTopRatio in spec.faceTopMinRatio..spec.faceTopMaxRatio)
    }

    @Test
    fun onlyHardCompositionViolationBlocksExport() {
        val base = IdPhotoSpec(
            widthPx = 295,
            heightPx = 413,
            faceHeightRatio = 0.68,
            faceTopRatio = 0.08,
            topPolicy = IdPhotoTopPolicy.FIXED_REFERENCE,
            faceHeightMinRatio = 0.64,
            faceHeightMaxRatio = 0.72,
            faceTopMinRatio = 0.04,
            faceTopMaxRatio = 0.12,
            shoulderLineRatio = null,
        )
        val plan = IdPhotoCompositionPlan(0.60, 0.08, null, 0.5, null)
        val face = PortraitFace(10, 10, 100, 120, 35f, 50f, 85f, 50f, 1f, 1f, 0f, 0f, 0f, 120f)

        val soft = IdPhotoComplianceEvaluator.evaluate(
            base,
            face,
            plan,
            IdPhotoColor.WHITE,
            IdPhotoOutputFormat.JPEG,
            100_000,
        )
        val hard = IdPhotoComplianceEvaluator.evaluate(
            base.copy(faceHeightConstraint = IdPhotoConstraintLevel.HARD),
            face,
            plan,
            IdPhotoColor.WHITE,
            IdPhotoOutputFormat.JPEG,
            100_000,
        )

        assertTrue(soft.canExport)
        assertTrue(!hard.canExport)
    }

    private fun head(eyePositionWithinHead: Double): PortraitHeadGeometry {
        val top = 100.0
        val height = 400.0
        return PortraitHeadGeometry(
            left = 300.0,
            top = top,
            width = 300.0,
            height = height,
            eyeCenterY = top + height * eyePositionWithinHead,
        )
    }
}
