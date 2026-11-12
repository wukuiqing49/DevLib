package com.lxj.xpopup.core

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/** 验证独立弹窗在最低版本、刘海版本及 Android 15 沙箱中的窗口初始化。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35])
class FullScreenDialogWindowTest {
    private lateinit var host: ActivityController<Activity>

    /** 释放测试宿主，避免窗口与 Activity 状态跨用例保留。 */
    @After
    fun releaseHost() {
        if (this::host.isInitialized) host.pause().stop().destroy()
    }

    /** 不依赖主题颜色项，创建窗口后系统栏透明且使用对应平台的刘海策略。 */
    @Suppress("DEPRECATION")
    @Test
    fun createsTransparentWindowAcrossSupportedVersions() {
        val dialog = createDialog()
        try {
            val window = requireNotNull(dialog.window)
            assertEquals(Color.TRANSPARENT, window.statusBarColor)
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            if (Build.VERSION.SDK_INT >= 28) {
                val expected = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                assertEquals(expected, window.attributes.layoutInDisplayCutoutMode)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                assertFalse(window.isStatusBarContrastEnforced)
                assertFalse(window.isNavigationBarContrastEnforced)
            }
        } finally {
            dialog.dismiss()
        }
    }

    /** Android 15 上旧 target 宿主也应由兼容入口获得透明栏，无需 Legacy 主题。 */
    @Suppress("DEPRECATION")
    @Test
    @Config(sdk = [35])
    fun legacyTargetHostAlsoGetsTransparentBars() {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        val originalTarget = info.targetSdkVersion
        try {
            info.targetSdkVersion = 34
            val dialog = createDialog()
            try {
                assertEquals(Color.TRANSPARENT, requireNotNull(dialog.window).statusBarColor)
                assertEquals(Color.TRANSPARENT, requireNotNull(dialog.window).navigationBarColor)
            } finally {
                dialog.dismiss()
            }
        } finally {
            info.targetSdkVersion = originalTarget
        }
    }

    /** 使用真实 XPopup 内容与配置创建窗口，执行生产 onCreate 初始化链路。 */
    private fun createDialog(): FullScreenDialog {
        host = Robolectric.buildActivity(Activity::class.java).setup()
        val context = host.get().apply { setTheme(androidx.appcompat.R.style.Theme_AppCompat) }
        val popup = CenterPopupView(context).apply { popupInfo = PopupInfo() }
        return FullScreenDialog(context).setContent(popup).also { it.create() }
    }
}
