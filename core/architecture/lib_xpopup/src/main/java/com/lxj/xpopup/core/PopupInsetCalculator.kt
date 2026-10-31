package com.lxj.xpopup.core

import com.lxj.xpopup.enums.PopupInsetMode
import kotlin.math.max

/** Android-free inset policy so it can be tested without a device or Robolectric. */
internal data class PopupInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/** 弹窗前景的安全区计算，不修改铺满窗口的背景及触摸层。 */
internal object PopupInsetCalculator {
    /** 按模式计算前景间距，全屏 Auto 模式仍需避让无法隐藏的桌面标题栏。 */
    fun foregroundInsets(
        mode: PopupInsetMode,
        keepsImmersiveContent: Boolean,
        hostIsEdgeToEdge: Boolean,
        systemBars: PopupInsets,
        displayCutout: PopupInsets,
        imeVisible: Boolean,
        captionBar: PopupInsets = PopupInsets()
    ): PopupInsets {
        val applySafeArea = when (mode) {
            PopupInsetMode.SafeArea -> true
            PopupInsetMode.EdgeToEdge -> false
            PopupInsetMode.Auto -> !keepsImmersiveContent && hostIsEdgeToEdge
        }
        if (!applySafeArea) {
            return if (mode == PopupInsetMode.Auto && hostIsEdgeToEdge) captionBar else PopupInsets()
        }

        return PopupInsets(
            left = max(systemBars.left, displayCutout.left),
            top = max(systemBars.top, displayCutout.top),
            right = max(systemBars.right, displayCutout.right),
            // Keyboard positioning is handled by BasePopupView; retaining navigation padding
            // while the IME is visible would move bottom popups twice.
            bottom = if (imeVisible) 0 else max(systemBars.bottom, displayCutout.bottom)
        )
    }

    fun applyToInitialPadding(initial: PopupInsets, insets: PopupInsets): PopupInsets {
        return PopupInsets(
            left = initial.left + insets.left,
            top = initial.top + insets.top,
            right = initial.right + insets.right,
            bottom = initial.bottom + insets.bottom
        )
    }
}
