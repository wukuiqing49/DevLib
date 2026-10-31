package com.wkq.base.insets

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 验证桌面标题栏、全屏安全区与键盘切换，使用受支持的 API 35 沙箱。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarInsetsTest {
    /** 普通页面应采用标题栏与状态栏的最大值，重复分发不得累加。 */
    @Test
    fun regularPageAvoidsCaptionWithoutAccumulation() {
        val view = createView()
        SystemBarInsets.applySystemBarsInset(view, includeHorizontal = true, includeIme = true)
        val insets = windowInsets(caption = 42, ime = 260)
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        assertPadding(view, 11, 44, 15, 264)
        ViewCompat.dispatchApplyWindowInsets(view, windowInsets(caption = 0, ime = 0))
        assertPadding(view, 11, 26, 15, 28)
        SystemBarInsets.clearInsets(view)
        assertPadding(view, 1, 2, 3, 4)
    }

    /** 全屏仅保留刘海与桌面标题栏，临时系统栏不能挤压内容，键盘采用最大值。 */
    @Test
    fun immersivePageKeepsCutoutAndCaptionWithoutTransientBars() {
        val view = createView()
        SystemBarInsets.applyImmersiveInsets(view, includeIme = true)
        ViewCompat.dispatchApplyWindowInsets(view, windowInsets(caption = 42, ime = 260))
        assertPadding(view, 11, 44, 15, 264)
        ViewCompat.dispatchApplyWindowInsets(view, windowInsets(caption = 0, ime = 0))
        assertPadding(view, 11, 20, 15, 10)
        SystemBarInsets.clearInsets(view)
        assertPadding(view, 1, 2, 3, 4)
    }

    /** 默认媒体全屏不因键盘高度变化缩小画面，由输入页面显式选择键盘避让。 */
    @Test
    fun immersiveImeIsOptIn() {
        val view = createView()
        SystemBarInsets.applyImmersiveInsets(view)
        ViewCompat.dispatchApplyWindowInsets(view, windowInsets(caption = 0, ime = 260))
        assertPadding(view, 11, 20, 15, 10)
    }

    /** 顶部工具栏也必须避让桌面标题栏，且不能重复增加固定高度。 */
    @Test
    fun topContainerResizesOnceForCaption() {
        val view = createView()
        view.layoutParams = android.view.ViewGroup.LayoutParams(100, 56)
        SystemBarInsets.applyTopInset(view)
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(view, windowInsets(caption = 42)) }
        assertEquals(98, view.layoutParams.height)
        assertEquals(44, view.paddingTop)
        SystemBarInsets.clearInsets(view)
        assertEquals(56, view.layoutParams.height)
    }

    /** 创建带业务基础间距的独立 View。 */
    private fun createView(): View = View(RuntimeEnvironment.getApplication()).apply {
        setPadding(1, 2, 3, 4)
    }

    /** 构造横屏刘海、底栏、标题栏和键盘同时存在的窗口数据。 */
    private fun windowInsets(caption: Int, ime: Int = 0): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, caption, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(10, 18, 12, 6))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .build()

    /** 核对物理四边间距，避免不同来源安全区相加或遗漏。 */
    private fun assertPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(left, view.paddingLeft)
        assertEquals(top, view.paddingTop)
        assertEquals(right, view.paddingRight)
        assertEquals(bottom, view.paddingBottom)
    }
}
