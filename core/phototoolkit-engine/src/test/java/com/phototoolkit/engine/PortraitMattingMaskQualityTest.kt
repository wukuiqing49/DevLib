package com.phototoolkit.engine

import org.junit.Assert.assertThrows
import org.junit.Test

class PortraitMattingMaskQualityTest {
    @Test
    fun `rejects transparent model result`() {
        assertThrows(PortraitMatting.UnusableMaskException::class.java) {
            PortraitMatting.requireUsableMask(PortraitMatting.AlphaMask(ByteArray(64), 8, 8))
        }
    }

    @Test
    fun `rejects opaque model result`() {
        assertThrows(PortraitMatting.UnusableMaskException::class.java) {
            PortraitMatting.requireUsableMask(PortraitMatting.AlphaMask(ByteArray(64) { 0xFF.toByte() }, 8, 8))
        }
    }

    @Test
    fun `accepts a mask with foreground and background`() {
        val mask = ByteArray(64).apply {
            for (index in 20..43) this[index] = 0xFF.toByte()
        }

        PortraitMatting.requireUsableMask(PortraitMatting.AlphaMask(mask, 8, 8))
    }
}
