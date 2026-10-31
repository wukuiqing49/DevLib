package com.wkq.base.activity

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewbinding.ViewBinding
import com.wkq.base.insets.SystemBarInsets

/** 全屏内容基类：隐藏系统栏，保留刘海及桌面标题栏安全区，兼容窗口重新获得焦点。 */
abstract class BaseFullScreenActivity<VB : ViewBinding> : BaseActivity<VB>() {

    /** 全屏根布局独占安全区处理；需要背景铺满的页面可覆盖并自行适配操作控件。 */
    override fun applyDefaultSystemBarsInsets() {
        SystemBarInsets.applyImmersiveInsets(binding.root, includeIme = shouldApplyImeInset())
    }

    /** 沿用 Base 的外观初始化与 Insets 入口，再进入全屏沉浸式。 */
    override fun initImmersionBar() {
        super.initImmersionBar()
        binding.root.fitsSystemWindows = false
        restoreImmersiveMode()
    }

    /** 从后台恢复时重新应用当前页面的全屏策略，不影响其他 Activity 的窗口。 */
    override fun onResume() {
        super.onResume()
        restoreImmersiveMode()
    }

    /** 弹窗或权限窗口关闭后恢复全屏；失去焦点时不修改前台窗口状态。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) restoreImmersiveMode()
    }

    /** 允许边缘滑动短暂显示系统栏，不反复监听可见性强行隐藏。 */
    private fun restoreImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
