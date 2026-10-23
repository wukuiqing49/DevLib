package com.phototoolkit.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdPhotoSourceQualityGateTest {
    @Test
    fun blocksHeadThatRequiresExcessiveUpscaling() {
        val head = PortraitHeadGeometry(0.0, 0.0, 180.0, 180.0, 90.0)

        val result = IdPhotoSourceQualityGate.evaluate(head, 600, 0.60, 1.35)

        assertFalse(result.accepted)
    }

    @Test
    fun acceptsHeadWithEnoughSourcePixels() {
        val head = PortraitHeadGeometry(0.0, 0.0, 360.0, 360.0, 180.0)

        val result = IdPhotoSourceQualityGate.evaluate(head, 600, 0.60, 1.35)

        assertTrue(result.accepted)
    }
}
