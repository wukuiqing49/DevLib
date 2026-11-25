package com.wkq.guide

import org.junit.Assert.*
import org.junit.Test

/** 独立几何测试覆盖边界、空间不足及不同窗口原点。 */
class GuidePlacementTest {
    private val safe = GuideBounds(16f, 40f, 344f, 760f)

    /** 目标下方有空间时优先向下放置。 */
    @Test fun belowTarget() {
        val result = GuidePlacement.place(safe, GuideBounds(100f, 100f, 160f, 140f), 200f, 150f, 12f)
        assertEquals(152f, result.top)
    }

    /** 底部目标优先把提示放到上方。 */
    @Test fun aboveBottomTarget() {
        val result = GuidePlacement.place(safe, GuideBounds(200f, 650f, 300f, 710f), 200f, 150f, 12f)
        assertEquals(488f, result.top)
        assertTrue(result.right <= safe.right)
    }

    /** 大卡片不得超出小窗口，原点偏移不影响安全边界。 */
    @Test fun oversizedCardStaysInSafeWindow() {
        val result = GuidePlacement.place(safe, GuideBounds(0f, 200f, 30f, 240f), 900f, 1600f, 12f)
        assertEquals(safe, result)
    }

    /** 为各种目标位置和字号产生的尺寸组合检查不越界。 */
    @Test fun allCornersAndSizesStayInside() {
        for (x in listOf(0f, 16f, 180f, 340f)) for (y in listOf(0f, 40f, 400f, 730f)) {
            for (width in listOf(80f, 320f, 600f)) for (height in listOf(60f, 300f, 900f)) {
                val result = GuidePlacement.place(safe, GuideBounds(x, y, x + 24, y + 24), width, height, 16f)
                assertTrue(result.left >= safe.left && result.top >= safe.top)
                assertTrue(result.right <= safe.right && result.bottom <= safe.bottom)
            }
        }
    }
}
