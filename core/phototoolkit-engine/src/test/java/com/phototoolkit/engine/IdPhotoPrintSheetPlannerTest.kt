package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdPhotoPrintSheetPlannerTest {

    @Test
    fun keepsHongKongPortraitOrientationInsteadOfPlanningRotatedRows() {
        val layout = requireNotNull(IdPhotoPrintSheetPlanner.planFourBySix(472, 591))

        assertEquals(3, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(3, layout.copies)
        assertFits(layout, 472, 591)
    }

    @Test
    fun keepsLargePortraitWithinFourBySixBounds() {
        val layout = IdPhotoPrintSheetPlanner.planFourBySix(591, 827)

        assertNotNull(layout)
        assertFits(requireNotNull(layout), 591, 827)
    }

    @Test
    fun rejectsPhotoThatCannotFitAtOriginalPixelSize() {
        assertNull(IdPhotoPrintSheetPlanner.planFourBySix(1801, 1201))
    }

    private fun assertFits(layout: IdPhotoSheetLayout, photoWidth: Int, photoHeight: Int) {
        val occupiedWidth = layout.marginPx * 2 +
            layout.columns * photoWidth +
            (layout.columns - 1) * layout.gapPx
        val occupiedHeight = layout.marginPx * 2 +
            layout.rows * photoHeight +
            (layout.rows - 1) * layout.gapPx
        assertTrue(occupiedWidth <= layout.paperWidthPx)
        assertTrue(occupiedHeight <= layout.paperHeightPx)
    }
}
