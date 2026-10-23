package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitRoiPlannerTest {
    @Test
    fun roiAlwaysStaysInsideImageAtEdges() {
        val face = PortraitFace(
            left = 0,
            top = 0,
            width = 120,
            height = 140,
            leftEyeX = 35f,
            leftEyeY = 50f,
            rightEyeX = 85f,
            rightEyeY = 50f,
            leftEyeOpenProbability = 1f,
            rightEyeOpenProbability = 1f,
            pitchDegrees = 0f,
            yawDegrees = 0f,
            rollDegrees = 0f,
        )

        val roi = PortraitRoiPlanner.plan(face, 1080, 1920)

        assertEquals(0, roi.left)
        assertEquals(0, roi.top)
        assertTrue(roi.right <= 1080)
        assertTrue(roi.bottom <= 1920)
        assertTrue(roi.width > face.width)
        assertTrue(roi.height > face.height)
    }

    @Test
    fun decodedRoiCoordinatesMapBackToOriginalPixels() {
        val transform = PortraitCoordinateTransform(
            roi = PortraitRoi(left = 101, top = 203, right = 1102, bottom = 1704),
            decodedWidth = 500,
            decodedHeight = 750,
        )

        assertEquals(601.5f, transform.mapX(250f), 0.001f)
        assertEquals(953.5f, transform.mapY(375f), 0.001f)
    }
}
