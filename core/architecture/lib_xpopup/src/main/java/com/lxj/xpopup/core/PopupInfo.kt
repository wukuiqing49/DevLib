package com.lxj.xpopup.core

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import androidx.lifecycle.Lifecycle
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupInsetMode
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.interfaces.XPopupCallback

/**
 * Description: Popup的属性封装
 * Create by dance, at 2018/12/8
 */
class PopupInfo {
    var isDismissOnBackPressed: Boolean = true //按返回键是否消失
    var isDismissOnTouchOutside: Boolean = true //点击外部消失
    var autoDismiss: Boolean = true //操作完毕后是否自动关闭
    var hasShadowBg: Boolean = true // 是否有半透明的背景
    var hasBlurBg: Boolean = false // 是否有高斯模糊背景
    var atView: View? = null // 依附于那个View

    // 动画执行器，如果不指定，则会根据窗体类型popupType字段生成默认合适的动画执行器
    var popupAnimation: PopupAnimation? = null
    var customAnimator: PopupAnimator? = null
    var touchPoint: PointF? = null // 触摸的点
    var maxWidth: Int = 0 // 最大宽度
    var maxHeight: Int = 0 // 最大高度
    var popupWidth: Int = 0
    var popupHeight: Int = 0 // 指定弹窗的宽高，受max的宽高限制
    var borderRadius: Float = 15f // 圆角
    var autoOpenSoftInput: Boolean = false //是否自动打开输入法
    var xPopupCallback: XPopupCallback? = null

    var isMoveUpToKeyboard: Boolean = true //是否移动到软键盘上面，默认弹窗会移到软键盘上面
    var popupPosition: PopupPosition? = null //弹窗出现在目标的什么位置
    var hasStatusBarShadow: Boolean = false //是否显示状态栏阴影
    var hasStatusBar: Boolean = true //是否显示状态栏
    var hasNavigationBar: Boolean = true //是否显示导航栏
    var navigationBarColor: Int = 0 //是否显示导航栏
    var isLightNavigationBar: Int = 0 //是否是亮色导航栏，>0为true，<0为false
    var isLightStatusBar: Int = 0 //是否是亮色状态栏，>0为true，<0为false
    var offsetX: Int = 0
    var offsetY: Int = 0 //x，y方向的偏移量
    var enableDrag: Boolean = true //是否启用拖拽
    var isCenterHorizontal: Boolean = false //是否水平居中
    var isRequestFocus: Boolean = true //弹窗是否强制抢占焦点
    var autoFocusEditText: Boolean = true //是否让输入框自动获取焦点
    var isClickThrough: Boolean = false //是否点击透传，默认弹背景点击是拦截的
    var isTouchThrough: Boolean = false //是否触摸透传，默认是不支持的
    var isDarkTheme: Boolean = false //是否是暗色调主题
    var enableShowWhenAppBackground: Boolean = false //是否允许应用在后台的时候也能弹出弹窗
    var isThreeDrag: Boolean = false //是否开启三阶拖拽
    var isDestroyOnDismiss: Boolean = false //是否关闭后进行资源释放
    var positionByWindowCenter: Boolean = false //是否已屏幕中心进行定位，默认根据Material范式进行定位
    var isViewMode: Boolean = false //是否是View实现，默认是Dialog实现
    var popupInsetMode: PopupInsetMode = PopupInsetMode.Auto
    var keepScreenOn: Boolean = false //是否保持屏幕常亮
    var shadowBgColor: Int = 0 //阴影背景的颜色
    var animationDuration: Int = -1 //动画的时长
    var statusBarBgColor: Int = 0 //状态栏阴影颜色，对Drawer弹窗和全屏弹窗有效
    var notDismissWhenTouchInArea: ArrayList<Rect>? = null //当触摸在这个区域时，不消失
    var hostLifecycle: Lifecycle? = null //自定义的宿主生命周期
    var isCoverSoftInput: Boolean = false //弹窗是否覆盖/遮挡在输入法之上

    val atViewRect: Rect
        get() {
            val locations = IntArray(2)
            atView!!.getLocationInWindow(locations)
            return Rect(
                locations[0], locations[1], locations[0] + atView!!.getMeasuredWidth(),
                locations[1] + atView!!.getMeasuredHeight()
            )
        }
}
