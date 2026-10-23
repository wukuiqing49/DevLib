package com.lxj.xpopup.core

import com.lxj.xpopup.enums.PopupInsetMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupInsetCalculatorTest {
    @Test
    fun safeAreaUsesLargestSystemBarAndCutoutOnEverySide() {
        val result = PopupInsetCalculator.foregroundInsets(
            PopupInsetMode.SafeArea,
            keepsImmersiveContent = true,
            hostIsEdgeToEdge = true,
            systemBars = PopupInsets(left = 12, top = 24, right = 16, bottom = 48),
            displayCutout = PopupInsets(left = 20, top = 32, right = 8, bottom = 0),
            imeVisible = false
        )

        assertEquals(PopupInsets(left = 20, top = 32, right = 16, bottom = 48), result)
    }

    @Test
    fun imeTemporarilyRemovesBottomSafePaddingAndRestoresItWhenHidden() {
        val bars = PopupInsets(bottom = 48)
        assertEquals(
            PopupInsets(bottom = 48),
            PopupInsetCalculator.foregroundInsets(
                PopupInsetMode.SafeArea, false, true, bars, PopupInsets(), false
            )
        )
        assertEquals(
            PopupInsets(),
            PopupInsetCalculator.foregroundInsets(
                PopupInsetMode.SafeArea, false, true, bars, PopupInsets(), true
            )
        )
    }

    @Test
    fun autoKeepsLegacyImmersiveTypesAndProtectsForcedEdgeToEdgeHosts() {
        val bars = PopupInsets(top = 24, bottom = 48)
        assertEquals(
            PopupInsets(),
            PopupInsetCalculator.foregroundInsets(PopupInsetMode.Auto, true, true, bars, PopupInsets(), false)
        )
        assertEquals(
            PopupInsets(),
            PopupInsetCalculator.foregroundInsets(PopupInsetMode.Auto, false, false, bars, PopupInsets(), false)
        )
        assertEquals(
            bars,
            PopupInsetCalculator.foregroundInsets(PopupInsetMode.Auto, false, true, bars, PopupInsets(), false)
        )
    }

    @Test
    fun edgeToEdgeNeverAddsAutomaticPadding() {
        assertEquals(
            PopupInsets(),
            PopupInsetCalculator.foregroundInsets(
                PopupInsetMode.EdgeToEdge,
                false,
                true,
                PopupInsets(left = 10, top = 20, right = 30, bottom = 40),
                PopupInsets(left = 12, top = 24, right = 36, bottom = 48),
                false
            )
        )
    }

    @Test
    fun repeatedDispatchUsesInitialPaddingWithoutAccumulationAndKeepsPhysicalRtlSides() {
        val initial = PopupInsets(left = 3, top = 5, right = 7, bottom = 11)
        val insets = PopupInsets(left = 40, top = 24, right = 12, bottom = 48)
        val first = PopupInsetCalculator.applyToInitialPadding(initial, insets)
        val second = PopupInsetCalculator.applyToInitialPadding(initial, insets)

        assertEquals(PopupInsets(left = 43, top = 29, right = 19, bottom = 59), first)
        assertEquals(first, second)
    }
}
