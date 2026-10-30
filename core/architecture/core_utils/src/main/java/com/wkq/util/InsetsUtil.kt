package com.wkq.util

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * 启用 edge-to-edge 布局，并可选设置状态栏和导航栏图标的明暗模式。
 *
 * @param lightStatusBars true 使用深色状态栏图标，false 使用浅色图标，null 保持当前设置。
 * @param lightNavigationBars true 使用深色导航栏图标，false 使用浅色图标，null 保持当前设置。
 */
fun Activity.enableEdgeToEdge(
    lightStatusBars: Boolean? = null,
    lightNavigationBars: Boolean? = null
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        lightStatusBars?.let { isAppearanceLightStatusBars = it }
        lightNavigationBars?.let { isAppearanceLightNavigationBars = it }
    }
}

/**
 * 将系统栏 Insets 叠加到当前 View padding。
 *
 * 此方法会设置 View 的 WindowInsets listener；同一 View 如有自定义 listener，应由调用方自行合并逻辑。
 */
fun View.applySystemBarsPadding(
    applyLeft: Boolean = true,
    applyTop: Boolean = true,
    applyRight: Boolean = true,
    applyBottom: Boolean = true
) {
    val initial = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            left = initial.left + if (applyLeft) bars.left else 0,
            top = initial.top + if (applyTop) bars.top else 0,
            right = initial.right + if (applyRight) bars.right else 0,
            bottom = initial.bottom + if (applyBottom) bars.bottom else 0
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * 将 IME 或系统底部栏的最大高度叠加到当前 bottom padding，适合输入区和底部操作栏。
 * 此方法会设置 View 的 WindowInsets listener。
 */
fun View.applyImePadding() {
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bottom = maxOf(
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        )
        view.updatePadding(bottom = initialBottom + bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
