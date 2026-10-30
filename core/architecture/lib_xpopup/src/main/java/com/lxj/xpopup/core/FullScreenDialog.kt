package com.lxj.xpopup.core

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.insets.ColorProtection
import androidx.core.view.insets.Protection
import androidx.core.view.insets.ProtectionLayout
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.util.KeyboardUtils
import com.lxj.xpopup.util.XPermission
import com.lxj.xpopup.util.XPopupUtils.context2Activity

/**
 * 弹窗的宿主
 */
class FullScreenDialog(context: Context) : Dialog(context, R.style._XPopup_TransparentDialog) {
    private val hostActivity = context2Activity(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getWindow() == null || contentView == null || contentView!!.popupInfo == null) return
        if (contentView!!.popupInfo.enableShowWhenAppBackground && XPermission.create(getContext())
                ?.isGrantedDrawOverlays == true
        ) {
            if (Build.VERSION.SDK_INT >= 26) {
                getWindow()!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                getWindow()!!.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
        }

        if (contentView!!.popupInfo!!.keepScreenOn) {
            getWindow()!!.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        getWindow()!!.getDecorView().setPadding(0, 0, 0, 0)
        getWindow()!!.getAttributes().format = PixelFormat.TRANSPARENT
        getWindow()!!.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        getWindow()!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        getWindow()!!.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        WindowCompat.setDecorFitsSystemWindows(getWindow()!!, false)
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow()!!.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            getWindow()!!.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        getWindow()!!.setBackgroundDrawable(null)

        if (Build.VERSION.SDK_INT >= 29) {
            getWindow()!!.isNavigationBarContrastEnforced = false
        }

        if (!contentView!!.popupInfo!!.isRequestFocus) { //不获取焦点
            var flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (contentView!!.popupInfo!!.isCoverSoftInput) {
                flag = flag or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            setWindowFlag(flag, true)
        } else if (contentView!!.popupInfo!!.isCoverSoftInput) {
            setWindowFlag(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, true)
        }

        setStatusBarLightMode()
        setNavBarLightMode()
        setPopupContentView()
    }

    private var protectionLayout: ProtectionLayout? = null

    private fun setPopupContentView() {
        val popupView = contentView ?: return
        val protections = mutableListOf<Protection>()
        val statusBarColor = resolvedStatusBarColor
        if (statusBarColor != Color.TRANSPARENT) {
            protections.add(ColorProtection(WindowInsetsCompat.Side.TOP, statusBarColor))
        }
        val navigationBarColor = resolvedNavigationBarColor
        if (navigationBarColor != Color.TRANSPARENT) {
            protections.add(ColorProtection(WindowInsetsCompat.Side.LEFT, navigationBarColor))
            protections.add(ColorProtection(WindowInsetsCompat.Side.RIGHT, navigationBarColor))
            protections.add(ColorProtection(WindowInsetsCompat.Side.BOTTOM, navigationBarColor))
        }
        if (protections.isNotEmpty()) {
            protectionLayout = ProtectionLayout(
                getContext(),
                protections
            )
            protectionLayout!!.addView(
                popupView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setContentView(protectionLayout!!)
        } else {
            setContentView(popupView)
        }
    }

    private val resolvedNavigationBarColor: Int
        get() = if (contentView!!.popupInfo!!.navigationBarColor == 0)
            XPopup.navigationBarColor
        else
            contentView!!.popupInfo!!.navigationBarColor

    private val resolvedStatusBarColor: Int
        get() {
            val light = if (contentView!!.popupInfo!!.isLightStatusBar == 0)
                XPopup.isLightStatusBar
            else
                contentView!!.popupInfo!!.isLightStatusBar
            return if (light == 0) Color.TRANSPARENT else contentView!!.statusBarBgColor
        }

    fun setWindowFlag(bits: Int, on: Boolean) {
        val winParams = getWindow()!!.getAttributes()
        if (on) {
            winParams.flags = winParams.flags or bits
        } else {
            winParams.flags = winParams.flags and bits.inv()
        }
        getWindow()!!.setAttributes(winParams)
    }

    private fun setStatusBarLightMode() {
        val controller =
            WindowCompat.getInsetsController(getWindow()!!, getWindow()!!.getDecorView())
        updateSystemBarBehavior(controller)
        if (!contentView!!.popupInfo!!.hasStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            return
        }
        controller.show(WindowInsetsCompat.Type.statusBars())
        val light =
            if (contentView!!.popupInfo!!.isLightStatusBar == 0) XPopup.isLightStatusBar else contentView!!.popupInfo!!.isLightStatusBar
        if (light != 0) {
            controller.setAppearanceLightStatusBars(light > 0)
        } else {
            val hostWindow = hostActivity?.window
            if (hostWindow != null) {
                controller.isAppearanceLightStatusBars = WindowCompat.getInsetsController(
                    hostWindow,
                    hostWindow.decorView
                ).isAppearanceLightStatusBars
            }
        }
    }

    /**
     * copy from AndroidUtilCode/BarUtils
     */
    fun hideNavigationBar() {
        val controller =
            WindowCompat.getInsetsController(getWindow()!!, getWindow()!!.getDecorView())
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    fun setNavBarLightMode() {
        val controller =
            WindowCompat.getInsetsController(getWindow()!!, getWindow()!!.getDecorView())
        updateSystemBarBehavior(controller)
        if (!contentView!!.popupInfo!!.hasNavigationBar) {
            hideNavigationBar()
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        val light =
            if (contentView!!.popupInfo!!.isLightNavigationBar == 0) XPopup.isLightNavigationBar else contentView!!.popupInfo!!.isLightNavigationBar
        if (light != 0) {
            controller.setAppearanceLightNavigationBars(light > 0)
        } else {
            val hostWindow = hostActivity?.window
            if (hostWindow != null) {
                controller.isAppearanceLightNavigationBars = WindowCompat.getInsetsController(
                    hostWindow,
                    hostWindow.decorView
                ).isAppearanceLightNavigationBars
            }
        }
    }

    private fun updateSystemBarBehavior(controller: WindowInsetsControllerCompat) {
        if (!contentView!!.popupInfo!!.hasStatusBar || !contentView!!.popupInfo!!.hasNavigationBar) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private var backInvokedCallback: OnBackInvokedCallback? = null

    override fun onStart() {
        super.onStart()
        getWindow()?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        protectionLayout?.let(ViewCompat::requestApplyInsets)
        contentView?.let(ViewCompat::requestApplyInsets)
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback == null) {
            backInvokedCallback = OnBackInvokedCallback {
                if (contentView != null) contentView!!.dispatchBackPressed()
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback!!
            )
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback!!)
            backInvokedCallback = null
        }
        super.onStop()
    }

    var contentView: BasePopupView? = null
    fun setContent(view: BasePopupView): FullScreenDialog {
        if (view.getParent() != null) {
            (view.getParent() as ViewGroup).removeView(view)
        }
        this.contentView = view
        return this
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setStatusBarLightMode()
        setNavBarLightMode()
        if (hasFocus && contentView != null && contentView!!.hasMoveUp && contentView!!.popupStatus == PopupStatus.Show) {
            contentView!!.focusAndProcessBackPress()
            KeyboardUtils.showSoftInput(contentView)
        }
    }
}
