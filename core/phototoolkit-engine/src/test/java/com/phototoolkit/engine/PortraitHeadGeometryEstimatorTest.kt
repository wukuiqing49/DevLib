package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitHeadGeometryEstimatorTest {

    @Test
    fun fallsBackToFaceBoxWhenMaskFindsCrownFarAboveFace() {
        val face = face(top = 300, height = 200, chinY = 500f)
        val mask = mask(width = 600, height = 800) { x, y ->
            // 模拟上方背景误分割为前景；旧阈值会把 y=0 认作头顶。
            x in 200..400 && y in 0..500
        }

        val result = PortraitHeadGeometryEstimator.estimate(
            face = face,
            mask = mask,
            imageWidth = 600,
            imageHeight = 800,
            measurement = IdPhotoHeadMeasurement.CROWN_TO_CHIN,
        )

        assertEquals(face.top.toDouble(), result.top, 0.001)
        assertEquals(face.height.toDouble(), result.height, 0.001)
        assertTrue(result.isReliable)
    }

    @Test
    fun keepsNearbyCrownFromPortraitMask() {
        val face = face(top = 300, height = 200, chinY = 500f)
        val mask = mask(width = 600, height = 800) { x, y ->
            x in 200..400 && y in 250..500
        }

        val result = PortraitHeadGeometryEstimator.estimate(
            face = face,
            mask = mask,
            imageWidth = 600,
            imageHeight = 800,
            measurement = IdPhotoHeadMeasurement.CROWN_TO_CHIN,
        )

        assertEquals(250.0, result.top, 0.001)
        assertEquals(250.0, result.height, 0.001)
    }

    private fun face(top: Int, height: Int, chinY: Float) = PortraitFace(
        left = 200,
        top = top,
        width = 200,
        height = height,
        leftEyeX = 250f,
        leftEyeY = 360f,
        rightEyeX = 350f,
        rightEyeY = 360f,
        leftEyeOpenProbability = 1f,
        rightEyeOpenProbability = 1f,
        pitchDegrees = 0f,
        yawDegrees = 0f,
        rollDegrees = 0f,
        chinY = chinY,
    )

    private fun mask(width: Int, height: Int, isForeground: (Int, Int) -> Boolean): PortraitMatting.AlphaMask =
        PortraitMatting.AlphaMask(
            width = width,
            height = height,
            bytes = ByteArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (isForeground(x, y)) 0xFF.toByte() else 0
            },
        )
}
