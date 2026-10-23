package com.lxj.xpopup.core

import android.R
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.animator.BlurAnimator
import com.lxj.xpopup.animator.EmptyAnimator
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScaleAlphaAnimator
import com.lxj.xpopup.animator.ScrollScaleAnimator
import com.lxj.xpopup.animator.ShadowBgAnimator
import com.lxj.xpopup.animator.TranslateAlphaAnimator
import com.lxj.xpopup.animator.TranslateAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupInsetMode
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.impl.FullScreenPopupView
import com.lxj.xpopup.impl.PartShadowPopupView
import com.lxj.xpopup.util.KeyboardUtils
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.util.XPopupUtils.context2Activity
import com.lxj.xpopup.util.XPopupUtils.getDecorViewInvisibleHeight
import com.lxj.xpopup.util.XPopupUtils.getNavBarHeight
import com.lxj.xpopup.util.XPopupUtils.getStatusBarHeight
import com.lxj.xpopup.util.XPopupUtils.hasSetKeyListener
import com.lxj.xpopup.util.XPopupUtils.isInRect
import com.lxj.xpopup.util.XPopupUtils.moveDown
import com.lxj.xpopup.util.XPopupUtils.moveUpToKeyboard
import com.lxj.xpopup.util.XPopupUtils.view2Bitmap
import kotlin.math.sqrt

/**
 * Description: 弹窗基类
 * Create by lxj, at 2018/12/7
 */
abstract class BasePopupView(context: Context) : FrameLayout(context), DefaultLifecycleObserver,
    LifecycleOwner, ViewCompat.OnUnhandledKeyEventListenerCompat {
    lateinit var popupInfo: PopupInfo
    protected var popupContentAnimator: PopupAnimator? = null
    protected var shadowBgAnimator: ShadowBgAnimator? = null
    protected var blurAnimator: BlurAnimator? = null
    private val touchSlop: Int
    var popupStatus: PopupStatus = PopupStatus.Dismiss
    protected var isCreated: Boolean = false
    private var hasModifySoftMode = false
    private var preSoftMode = -1
    var hasMoveUp: Boolean = false
    protected var popupHandler: Handler = Handler(Looper.getMainLooper())
    protected lateinit var lifecycleRegistry: LifecycleRegistry
    private var backPressedCallback: OnBackPressedCallback? = null
    private var appliedSystemBarInsets = PopupInsets()
    private var appliedInsetTarget: View? = null
    private var insetTargetInitialPadding = PopupInsets()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        init()
    }

    fun show(): BasePopupView {
        val activity = this.activity
        if (activity == null || activity.isFinishing()) {
            return this
        }
        requireNotNull(popupInfo) { "popupInfo is null, if your popup object is reused, do not set isDestroyOnDismiss(true) !" }
        if (popupStatus == PopupStatus.Showing || popupStatus == PopupStatus.Dismissing) return this
        popupStatus = PopupStatus.Showing
        //        if (popupInfo.isRequestFocus) KeyboardUtils.hideSoftInput(activity.getWindow());
        if (!popupInfo!!.isViewMode && dialog != null && dialog!!.isShowing()) return this@BasePopupView

        // 1. add PopupView to its host.
        val cv = activity.getWindow().getDecorView().findViewById<View>(R.id.content)
        cv.post(object : Runnable {
            override fun run() {
                attachToHost()
            }
        })
        return this
    }

    var dialog: FullScreenDialog? = null

    private fun attachToHost() {
        requireNotNull(popupInfo) { "如果弹窗对象是复用的，则不要设置isDestroyOnDismiss(true)" }
        if (popupInfo!!.hostLifecycle != null) {
            popupInfo.hostLifecycle?.addObserver(this)
        } else {
            if (getContext() is FragmentActivity) {
                (getContext() as FragmentActivity).lifecycle.addObserver(this)
            }
        }
        if (popupInfo!!.isViewMode && getContext() is FragmentActivity) {
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dispatchBackPressed()
                }
            }
            (getContext() as FragmentActivity).onBackPressedDispatcher
                .addCallback(backPressedCallback!!)
        }
        doMeasure()

        if (popupInfo!!.isViewMode) {
            //view实现
            val decorView = this.activity!!.getWindow().getDecorView() as ViewGroup
            if (getParent() != null) (getParent() as ViewGroup).removeView(this)
            decorView.addView(this, getLayoutParams())
        } else {
            //dialog实现
            if (dialog == null) {
                dialog = FullScreenDialog(getContext()).setContent(this)
            }
            val activity = this.activity
            if (activity != null && !activity.isFinishing() && !dialog!!.isShowing()) dialog!!.show()
        }

        //2. 注册对话框监听器
        KeyboardUtils.registerSoftInputChangedListener(
            this@BasePopupView.hostWindow!!,
            this@BasePopupView,
            object : KeyboardUtils.OnSoftInputChangedListener {
                override fun onSoftInputChanged(height: Int) {
                    onKeyboardHeightChange(height)
                    if (popupInfo != null && popupInfo!!.xPopupCallback != null) {
                        popupInfo.xPopupCallback?.onKeyBoardStateChanged(
                            this@BasePopupView,
                            height
                        )
                    }
                    if (height == 0) { // 说明输入法隐藏
                        post(object : Runnable {
                            override fun run() {
                                moveDown(this@BasePopupView)
                            }
                        })
                        hasMoveUp = false
                    } else {
                        //when show keyboard, move up
                        if (this@BasePopupView is PartShadowPopupView && popupStatus == PopupStatus.Showing) {
                            return
                        }
                        moveUpToKeyboard(height, this@BasePopupView)
                        hasMoveUp = true
                    }
                }
            })
        // 2. do init，game start.
//        init();
    }

    protected val activity: Activity?
        get() = context2Activity(getContext())
    protected val windowDecorView: View?
        get() {
            val window = this@BasePopupView.hostWindow ?: return null
            return window.decorView as ViewGroup
        }

    val activityContentView: View
        /**
         * 注意此处的Activity content并不是android.R.id.content，而是decorView的第一个子View，
         * 是包含了ActionBar/ToolBar在内的
         * @return
         */
        get() {
            val decorView = this.activity!!.getWindow().getDecorView() as ViewGroup
            return decorView.getChildAt(0)
        }

    protected val activityContentLeft: Int
        get() = 0
    //        if(!XPopupUtils.isLandscape(getContext())) return 0;
//        //以Activity的content的left为准
//        View decorView = getActivity().getWindow().getDecorView().findViewById(android.R.id.content);
//        int[] loc = new int[2];
//        decorView.getLocationInWindow(loc);
//        return loc[0];

    protected open fun doMeasure() {
        //设置自己的大小，和Activity的contentView保持一致
        val act = this.activity
        if (act == null) return
        //        WindowManager wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
//        Point point = new Point();
//        wm.getDefaultDisplay().getSize(point);
        var params = getLayoutParams() as MarginLayoutParams?
        if (!popupInfo.isViewMode) {
            if (params == null) {
                params = MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            } else {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            params.setMargins(0, 0, 0, 0)
            setLayoutParams(params)
            return
        }
        val activityContent = this.activityContentView
        if (params == null) {
            params = MarginLayoutParams(activityContent.getWidth(), activityContent.getHeight())
        } else {
            params.width = activityContent.getWidth()
            params.height = activityContent.getHeight()
        }
        params.leftMargin =
            if (popupInfo != null && popupInfo!!.isViewMode) activityContent.getLeft() else 0
        params.topMargin = activityContent.getTop()
        setLayoutParams(params)
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets? {
        post { doMeasure() }
        return super.onApplyWindowInsets(insets)
    }

    internal fun applySystemWindowInsets(insets: WindowInsetsCompat) {
        val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val displayCutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
        val systemInsets = PopupInsetCalculator.foregroundInsets(
            mode = popupInfo.popupInsetMode,
            keepsImmersiveContent = this is FullScreenPopupView || this is DrawerPopupView,
            hostIsEdgeToEdge = !popupInfo.isViewMode || hostTargetsEdgeToEdge,
            systemBars = PopupInsets(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            ),
            displayCutout = PopupInsets(
                displayCutout.left,
                displayCutout.top,
                displayCutout.right,
                displayCutout.bottom
            ),
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        )
        val target = popupInsetTarget ?: return
        if (appliedInsetTarget !== target) {
            appliedInsetTarget = target
            insetTargetInitialPadding = PopupInsets(
                target.paddingLeft,
                target.paddingTop,
                target.paddingRight,
                target.paddingBottom
            )
            appliedSystemBarInsets = PopupInsets()
        }
        if (appliedSystemBarInsets == systemInsets) return
        appliedSystemBarInsets = systemInsets
        val padding = PopupInsetCalculator.applyToInitialPadding(insetTargetInitialPadding, systemInsets)
        target.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        target.requestLayout()
        post { doMeasure() }
    }

    private val hostTargetsEdgeToEdge: Boolean
        get() = Build.VERSION.SDK_INT >= 35 && (activity?.applicationInfo?.targetSdkVersion ?: 0) >= 35

    /**
     * Foreground view that receives [PopupInsetMode.SafeArea] padding. The popup root remains
     * edge-to-edge so backgrounds, shadows, touch handling, and animations keep full-window bounds.
     */
    protected open val popupInsetTarget: View?
        get() = this

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        ViewCompat.requestApplyInsets(this)
        this.activityContentView.post(object : Runnable {
            override fun run() {
                doMeasure()
            }
        })
    }

    /**
     * 执行初始化
     */
    protected fun init() {
        if (shadowBgAnimator == null) shadowBgAnimator = ShadowBgAnimator(
            this,
            this@BasePopupView.animationDuration,
            this.shadowBgColor
        )
        if (popupInfo!!.hasBlurBg) {
            blurAnimator = BlurAnimator(this, this.shadowBgColor)
            blurAnimator!!.hasShadowBg = popupInfo!!.hasShadowBg
            blurAnimator!!.decorBitmap = view2Bitmap(
                (this.activity)!!.getWindow().getDecorView(),
                this.activityContentView.getHeight(), 5
            )
        }

        //1. 初始化Popup
        if (this is AttachPopupView || this is BubbleAttachPopupView
            || this is PartShadowPopupView || this is PositionPopupView
        ) {
            initPopupContent()
        } else if (!isCreated) {
            initPopupContent()
        }
        if (!isCreated) {
            isCreated = true
            onCreate()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            if (popupInfo!!.xPopupCallback != null) popupInfo.xPopupCallback?.onCreated(this)
        }
        popupHandler.post(initTask)
    }

    private val initTask: Runnable = object : Runnable {
        override fun run() {
            if (this@BasePopupView.hostWindow == null) return
            if (popupInfo != null && popupInfo!!.xPopupCallback != null) popupInfo.xPopupCallback?.beforeShow(
                this@BasePopupView
            )
            beforeShow()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            if (this@BasePopupView !is FullScreenPopupView) focusAndProcessBackPress()

            //由于部分弹窗有个位置设置过程，需要在位置设置完毕自己开启动画
            if ((this@BasePopupView !is AttachPopupView) && (this@BasePopupView !is BubbleAttachPopupView) && (this@BasePopupView !is PositionPopupView) && (this@BasePopupView !is PartShadowPopupView)) {
                initAnimator()

                doShowAnimation()

                doAfterShow()
            }
        }
    }

    protected fun initAnimator() {
        this.popupContentView.setAlpha(1f)
        // 优先使用自定义的动画器
        if (popupInfo != null && popupInfo!!.customAnimator != null) {
            popupContentAnimator = popupInfo!!.customAnimator
            if (popupContentAnimator!!.targetView == null) popupContentAnimator!!.targetView =
                this.popupContentView
        } else {
            // 根据PopupInfo的popupAnimation字段来生成对应的动画执行器，如果popupAnimation字段为null，则返回null
            popupContentAnimator = genAnimatorByPopupType()
            if (popupContentAnimator == null) {
                popupContentAnimator = this.popupAnimator
            }
        }

        //3. 初始化动画执行器
        if (popupInfo != null && popupInfo!!.hasShadowBg) {
            shadowBgAnimator!!.initAnimator()
        }
        if (popupInfo != null && popupInfo!!.hasBlurBg && blurAnimator != null) {
            blurAnimator!!.initAnimator()
        }
        if (popupContentAnimator != null) {
            popupContentAnimator!!.initAnimator()
        }
    }

    private fun detachFromHost() {
        if (popupInfo != null && popupInfo!!.isViewMode) {
            val decorView = getParent() as ViewGroup?
            if (decorView != null) decorView.removeView(this)
        } else {
            if (dialog != null) dialog!!.dismiss()
        }
    }

    val hostWindow: Window?
        get() {
            if (popupInfo != null && popupInfo!!.isViewMode) {
                val activity = this.activity
                return if (activity == null) null else activity.getWindow()
            }
            return if (dialog == null) null else dialog!!.getWindow()
        }

    protected fun doAfterShow() {
        popupHandler.removeCallbacks(doAfterShowTask)
        popupHandler.postDelayed(doAfterShowTask, this@BasePopupView.animationDuration.toLong())
    }

    protected var doAfterShowTask: Runnable = object : Runnable {
        override fun run() {
            popupStatus = PopupStatus.Show
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            onShow()
            if (this@BasePopupView is FullScreenPopupView) focusAndProcessBackPress()
            if (popupInfo != null && popupInfo!!.xPopupCallback != null) popupInfo.xPopupCallback?.onShow(
                this@BasePopupView
            )
            //再次检测移动距离
            if (this@BasePopupView.hostWindow != null && getDecorViewInvisibleHeight(this@BasePopupView.hostWindow) > 0 && !hasMoveUp) {
                moveUpToKeyboard(getDecorViewInvisibleHeight(this@BasePopupView.hostWindow), this@BasePopupView)
            }
        }
    }

    private var showSoftInputTask: ShowSoftInputTask? = null
    fun focusAndProcessBackPress() {
        if (popupInfo != null && popupInfo!!.isRequestFocus) {
            setFocusableInTouchMode(true)
            setFocusable(true)
            // 此处焦点可能被内部的EditText抢走，也需要给EditText也设置返回按下监听
            if (Build.VERSION.SDK_INT >= 28) {
                addOnUnhandledKeyListener(this)
            } else {
                setOnKeyListener(BackPressListener())
            }

            //let all EditText can process back pressed.
            val list = ArrayList<EditText>()
            XPopupUtils.findAllEditText(list, (this.popupContentView as ViewGroup?)!!)
            if (list.size > 0) {
                preSoftMode = this@BasePopupView.hostWindow!!.attributes.softInputMode
                if (popupInfo!!.isViewMode) {
                    this@BasePopupView.hostWindow!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    hasModifySoftMode = true
                }
                for (i in list.indices) {
                    val et = list.get(i)
                    //                    addOnUnhandledKeyListener(et);
                    if (Build.VERSION.SDK_INT >= 28) {
                        addOnUnhandledKeyListener(et)
                    } else {
                        val hasSetKeyListener = hasSetKeyListener(et)
                        if (!hasSetKeyListener) et.setOnKeyListener(BackPressListener())
                    }
                    if (i == 0) {
                        if (popupInfo!!.autoFocusEditText) {
                            et.setFocusable(true)
                            et.setFocusableInTouchMode(true)
                            et.requestFocus()
                            if (popupInfo!!.autoOpenSoftInput) showSoftInput(et)
                        } else {
                            if (popupInfo!!.autoOpenSoftInput) showSoftInput(this)
                        }
                    }
                }
            } else {
                if (popupInfo!!.autoOpenSoftInput) showSoftInput(this)
            }
        }
    }

    override fun onUnhandledKeyEvent(v: View, event: KeyEvent): Boolean {
        return processKeyEvent(event.getKeyCode(), event)
    }

    protected fun addOnUnhandledKeyListener(view: View) {
        ViewCompat.removeOnUnhandledKeyEventListener(view, this)
        ViewCompat.addOnUnhandledKeyEventListener(view, this)
    }

    protected fun showSoftInput(focusView: View?) {
        if (popupInfo != null) {
            if (showSoftInputTask == null) {
                showSoftInputTask = ShowSoftInputTask(focusView)
            } else {
                popupHandler.removeCallbacks(showSoftInputTask!!)
            }
            popupHandler.postDelayed(showSoftInputTask!!, 10)
        }
    }

    fun dismissOrHideSoftInput() {
        if (getDecorViewInvisibleHeight(this@BasePopupView.hostWindow) == 0) {
            dismiss()
        } else KeyboardUtils.hideSoftInput(this@BasePopupView)
    }

    internal class ShowSoftInputTask(var focusView: View?) : Runnable {
        override fun run() {
            if (focusView != null) {
                KeyboardUtils.showSoftInput(focusView)
            }
        }
    }

    protected fun processKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && popupInfo != null) {
            return dispatchBackPressed()
        }
        return false
    }

    fun dispatchBackPressed(): Boolean {
        if (popupInfo == null) return false
        if (onBackPressed()) return true
        if (popupInfo!!.isDismissOnBackPressed &&
            (popupInfo.xPopupCallback?.onBackPressed(this@BasePopupView) != true)
        ) {
            dismissOrHideSoftInput()
        }
        return true
    }

    internal inner class BackPressListener : OnKeyListener {
        override fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean {
            return processKeyEvent(keyCode, event)
        }
    }

    /**
     * 根据PopupInfo的popupAnimation字段来生成对应的内置的动画执行器
     */
    protected fun genAnimatorByPopupType(): PopupAnimator? {
        if (popupInfo == null || popupInfo!!.popupAnimation == null) return null
        if ((popupInfo!!.popupAnimation == PopupAnimation.ScaleAlphaFromCenter)
            || (popupInfo!!.popupAnimation == PopupAnimation.ScaleAlphaFromLeftTop)
            || (popupInfo!!.popupAnimation == PopupAnimation.ScaleAlphaFromRightTop)
            || (popupInfo!!.popupAnimation == PopupAnimation.ScaleAlphaFromLeftBottom)
            || (popupInfo!!.popupAnimation == PopupAnimation.ScaleAlphaFromRightBottom)
        ) {
            return ScaleAlphaAnimator(
                this.popupContentView,
                this@BasePopupView.animationDuration,
                popupInfo!!.popupAnimation
            )
        } else if (popupInfo!!.popupAnimation == PopupAnimation.TranslateAlphaFromLeft || popupInfo!!.popupAnimation == PopupAnimation.TranslateAlphaFromTop || popupInfo!!.popupAnimation == PopupAnimation.TranslateAlphaFromRight || popupInfo!!.popupAnimation == PopupAnimation.TranslateAlphaFromBottom) {
            return TranslateAlphaAnimator(
                this.popupContentView,
                this@BasePopupView.animationDuration,
                popupInfo!!.popupAnimation
            )
        } else if (popupInfo!!.popupAnimation == PopupAnimation.TranslateFromLeft || popupInfo!!.popupAnimation == PopupAnimation.TranslateFromTop || popupInfo!!.popupAnimation == PopupAnimation.TranslateFromRight || popupInfo!!.popupAnimation == PopupAnimation.TranslateFromBottom) {
            return TranslateAnimator(
                this.popupContentView,
                this@BasePopupView.animationDuration,
                popupInfo!!.popupAnimation
            )
        } else if (popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromLeft || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromLeftTop || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromTop || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromRightTop || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromRight || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromRightBottom || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromBottom || popupInfo!!.popupAnimation == PopupAnimation.ScrollAlphaFromLeftBottom) {
            return ScrollScaleAnimator(
                this.popupContentView,
                this@BasePopupView.animationDuration,
                popupInfo!!.popupAnimation
            )
        } else if (popupInfo!!.popupAnimation == PopupAnimation.NoAnimation) {
            return EmptyAnimator(this.popupContentView, this@BasePopupView.animationDuration)
        }

        return null
    }

    /**
     * 内部使用，自定义弹窗的时候不要重新这个方法
     * @return
     */
    protected abstract val innerLayoutId: Int

    protected open val implLayoutId: Int
        /**
         * 如果你自己继承BasePopupView来做，这个不用实现
         *
         * @return
         */
        get() = -1

    protected open val popupAnimator: PopupAnimator?
        /**
         * 获取PopupAnimator，用于每种类型的PopupView自定义自己的动画器
         *
         * @return
         */
        get() = null

    /**
     * 请使用onCreate，主要给弹窗内部用，不要去重写。
     */
    protected open fun initPopupContent() {}

    /**
     * do init.
     */
    protected open fun onCreate() {}

    protected open fun applyDarkTheme() {}

    protected open fun applyLightTheme() {}


    /**
     * 执行显示动画：动画由2部分组成，一个是背景渐变动画，一个是Content的动画；
     * 背景动画由父类实现，Content由子类实现
     */
    protected open fun doShowAnimation() {
        if (popupInfo == null) return
        if (popupInfo!!.hasShadowBg && !popupInfo!!.hasBlurBg && shadowBgAnimator != null) {
            shadowBgAnimator!!.animateShow()
        } else if (popupInfo!!.hasBlurBg && blurAnimator != null) {
            blurAnimator!!.animateShow()
        }
        if (popupContentAnimator != null) popupContentAnimator!!.animateShow()
    }

    /**
     * 执行消失动画：动画由2部分组成，一个是背景渐变动画，一个是Content的动画；
     * 背景动画由父类实现，Content由子类实现
     */
    protected open fun doDismissAnimation() {
        if (popupInfo == null) return
        if (popupInfo!!.hasShadowBg && !popupInfo!!.hasBlurBg && shadowBgAnimator != null) {
            shadowBgAnimator!!.animateDismiss()
        } else if (popupInfo!!.hasBlurBg && blurAnimator != null) {
            blurAnimator!!.animateDismiss()
        }

        if (popupContentAnimator != null) popupContentAnimator!!.animateDismiss()
    }

    val popupContentView: View
        /**
         * 获取内容View，本质上PopupView显示的内容都在这个View内部。
         * 而且我们对PopupView执行的动画，也是对它执行的动画
         *
         * @return
         */
        get() = getChildAt(0)

    open val popupImplView: View
        get() = (this.popupContentView as ViewGroup).getChildAt(0)

    val animationDuration: Int
        get() {
            if (popupInfo == null) return 0
            if (popupInfo!!.popupAnimation == PopupAnimation.NoAnimation) return 1
            return if (popupInfo!!.animationDuration >= 0) popupInfo!!.animationDuration else XPopup.animationDuration + 1
        }

    val shadowBgColor: Int
        get() = if (popupInfo != null && popupInfo!!.shadowBgColor != 0) popupInfo!!.shadowBgColor else XPopup.shadowBgColor

    val statusBarBgColor: Int
        get() = if (popupInfo != null && popupInfo!!.statusBarBgColor != 0) popupInfo!!.statusBarBgColor else XPopup.statusBarBgColor

    protected open val maxWidth: Int
        /**
         * 弹窗的最大宽度，用来限制弹窗的最大宽度
         * 返回0表示不限制，默认为0
         *
         * @return
         */
        get() = if (popupInfo == null) 0 else popupInfo!!.maxWidth

    protected open val maxHeight: Int
        /**
         * 弹窗的最大高度，用来限制弹窗的最大高度
         * 返回0表示不限制，默认为0
         *
         * @return
         */
        get() = if (popupInfo == null) 0 else popupInfo!!.maxHeight

    protected open val popupWidth: Int
        /**
         * 弹窗的宽度，用来动态设定当前弹窗的宽度，受maxWidth限制
         * 返回0表示不设置，默认为0
         *
         * @return
         */
        get() = if (popupInfo == null) 0 else popupInfo!!.popupWidth

    protected val popupHeight: Int
        /**
         * 弹窗的高度，用来动态设定当前弹窗的高度，受maxHeight限制
         * 返回0表示不设置，默认为0
         *
         * @return
         */
        get() = if (popupInfo == null) 0 else popupInfo!!.popupHeight

    /**
     * 消失
     */
    open fun dismiss() {
        popupHandler.removeCallbacks(initTask)
        if (popupStatus == PopupStatus.Dismissing || popupStatus == PopupStatus.Dismiss) return
        popupStatus = PopupStatus.Dismissing
        clearFocus()
        if (popupInfo != null && popupInfo!!.xPopupCallback != null) popupInfo.xPopupCallback?.beforeDismiss(
            this
        )
        beforeDismiss()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        doDismissAnimation()
        doAfterDismiss()
    }

    /**
     * 会等待弹窗show动画执行完毕再消失
     */
    fun smartDismiss() {
        popupHandler.post(object : Runnable {
            override fun run() {
                delayDismiss((this@BasePopupView.animationDuration + 50).toLong())
            }
        })
    }

    fun delayDismiss(delay: Long) {
        var delay = delay
        if (delay < 0) delay = 0
        popupHandler.postDelayed(object : Runnable {
            override fun run() {
                dismiss()
            }
        }, delay)
    }

    fun delayDismissWith(delay: Long, runnable: Runnable?) {
        this.dismissWithRunnable = runnable
        delayDismiss(delay)
    }

    protected open fun doAfterDismiss() {
        // PartShadowPopupView要等到完全关闭再关闭输入法，不然有问题
        if (popupInfo != null && popupInfo!!.autoOpenSoftInput && (this !is PartShadowPopupView)) KeyboardUtils.hideSoftInput(
            this
        )
        popupHandler.removeCallbacks(doAfterDismissTask)
        popupHandler.postDelayed(doAfterDismissTask, this@BasePopupView.animationDuration.toLong())
    }

    protected var doAfterDismissTask: Runnable = object : Runnable {
        override fun run() {
            popupStatus = PopupStatus.Dismiss
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            if (popupInfo == null) return
            if (popupInfo!!.autoOpenSoftInput && this@BasePopupView is PartShadowPopupView) KeyboardUtils.hideSoftInput(
                this@BasePopupView
            )
            onDismiss()
            XPopup.longClickPoint = null
            if (popupInfo!!.xPopupCallback != null) {
                popupInfo.xPopupCallback?.onDismiss(this@BasePopupView)
            }
            if (dismissWithRunnable != null) {
                dismissWithRunnable!!.run()
                dismissWithRunnable = null //no cache, avoid some bad edge effect.
            }
            if (popupInfo!!.isRequestFocus && popupInfo!!.isViewMode) {
                // 让根布局拿焦点，避免布局内RecyclerView类似布局获取焦点导致布局滚动
                val decorView = this@BasePopupView.windowDecorView
                if (decorView != null) {
                    val needFocusView: View? =
                        decorView.findViewById<View?>(R.id.content)
                    if (needFocusView != null) {
                        needFocusView.setFocusable(true)
                        needFocusView.setFocusableInTouchMode(true)
                    }
                }
            }
            // 移除弹窗，GameOver
            detachFromHost()
        }
    }

    var dismissWithRunnable: Runnable? = null

    fun dismissWith(runnable: Runnable?) {
        this.dismissWithRunnable = runnable
        dismiss()
    }

    val isShow: Boolean
        get() = popupStatus != PopupStatus.Dismiss

    val isDismiss: Boolean
        get() = popupStatus == PopupStatus.Dismiss

    fun toggle() {
        if (this.isShow) {
            dismiss()
        } else {
            show()
        }
    }

    /**
     * 尝试移除弹窗内的Fragment，如果提供了Fragment的名字
     */
    protected fun tryRemoveFragments() {
        if (getContext() is FragmentActivity) {
            val manager = (getContext() as FragmentActivity).getSupportFragmentManager()
            val fragments = manager.getFragments()
            val internalFragmentNames =
                this.internalFragmentNames
            if (fragments != null && fragments.size > 0 && internalFragmentNames != null) {
                for (i in fragments.indices) {
                    val name = fragments.get(i)!!.javaClass.getSimpleName()
                    if (internalFragmentNames.contains(name)) {
                        manager.beginTransaction()
                            .remove(fragments.get(i)!!)
                            .commitAllowingStateLoss()
                    }
                }
            }
        }
    }

    protected open val internalFragmentNames: MutableList<String?>?
        /**
         * 在弹窗内嵌入Fragment的场景中，当弹窗消失后，由于Fragment被Activity的FragmentManager缓存，
         * 会导致弹窗重新创建的时候，Fragment会命中缓存，生命周期不再执行。为了处理这种情况，只需重写：
         * getInternalFragmentNames() 方法，返回嵌入的Fragment名称，XPopup会自动移除Fragment缓存。
         * 名字是: Fragment.getClass().getSimpleName()
         *
         * @return
         */
        get() = null

    /**
     * 消失动画执行完毕后执行
     */
    protected open fun onDismiss() {
    }

    /**
     * 执行返回监听
     */
    protected open fun onBackPressed(): Boolean {
        return false
    }

    /**
     * onDismiss之前执行一次
     */
    protected open fun beforeDismiss() {
    }

    /**
     * onCreated之后，onShow之前执行
     */
    protected open fun beforeShow() {
    }

    /**
     * 显示动画执行完毕后执行
     */
    protected open fun onShow() {
    }

    protected open fun onKeyboardHeightChange(height: Int) {}

    override fun onDestroy(owner: LifecycleOwner) {
        onDestroy()
    }

    fun onDestroy() {
        onDetachedFromWindow()
        detachFromHost()
        destroy()
    }

    open fun destroy() {
        if (backPressedCallback != null) {
            backPressedCallback!!.remove()
            backPressedCallback = null
        }
        ViewCompat.removeOnUnhandledKeyEventListener(this, this)
        if (isCreated) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        lifecycleRegistry.removeObserver(this)
        if (popupInfo != null) {
            popupInfo!!.atView = null
            popupInfo!!.xPopupCallback = null
            if (popupInfo!!.hostLifecycle != null) {
                popupInfo.hostLifecycle?.removeObserver(this)
                popupInfo!!.hostLifecycle = null
            }
            if (popupInfo!!.customAnimator != null) {
                if (popupInfo.customAnimator?.targetView != null) {
                    popupInfo.customAnimator?.targetView!!.animate().cancel()
                    popupInfo.customAnimator?.targetView = null
                }
                popupInfo!!.customAnimator = null
            }
            if (popupInfo!!.isViewMode) tryRemoveFragments()
        }
        if (dialog != null) {
            if (dialog!!.isShowing()) dialog!!.dismiss()
            dialog!!.contentView = null
            dialog = null
        }
        if (shadowBgAnimator != null && shadowBgAnimator!!.targetView != null) {
            shadowBgAnimator!!.targetView!!.animate().cancel()
        }
        if (blurAnimator != null && blurAnimator!!.targetView != null) {
            blurAnimator!!.targetView!!.animate().cancel()
            if (blurAnimator!!.decorBitmap != null && !blurAnimator!!.decorBitmap!!.isRecycled()) {
                blurAnimator!!.decorBitmap!!.recycle()
                blurAnimator!!.decorBitmap = null
            }
        }
    }

    protected val statusBarHeight: Int
        get() = getStatusBarHeight(this@BasePopupView.hostWindow)
    protected val navBarHeight: Int
        get() = getNavBarHeight(this@BasePopupView.hostWindow)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (this@BasePopupView.windowDecorView != null) KeyboardUtils.removeLayoutChangeListener(
            this@BasePopupView.hostWindow, this@BasePopupView
        )
        popupHandler.removeCallbacksAndMessages(null)
        if (popupInfo != null) {
            if (popupInfo!!.isViewMode && hasModifySoftMode) {
                //还原WindowSoftMode
                this@BasePopupView.hostWindow?.setSoftInputMode(preSoftMode)
                hasModifySoftMode = false
            }
            if (popupInfo!!.isDestroyOnDismiss) destroy() //如果开启isDestroyOnDismiss，强制释放资源
        }
        if (popupInfo != null && popupInfo!!.hostLifecycle != null) {
            popupInfo.hostLifecycle?.removeObserver(this)
        } else {
            if (getContext() != null && getContext() is FragmentActivity) {
                (getContext() as FragmentActivity).lifecycle.removeObserver(this)
            }
        }
        popupStatus = PopupStatus.Dismiss
        showSoftInputTask = null
        hasMoveUp = false
    }

    fun passTouchThrough(event: MotionEvent?) {
        if (popupInfo != null && (popupInfo!!.isClickThrough || popupInfo!!.isTouchThrough)) {
            if (popupInfo!!.isViewMode) {
                //需要从DecorView分发，并且要排除自己，否则死循环
                val decorView = this.activity!!.getWindow().getDecorView() as ViewGroup
                for (i in 0 until decorView.getChildCount()) {
                    val view = decorView.getChildAt(i)
                    //自己和兄弟弹窗都不互相分发，否则死循环
                    if (view !is BasePopupView) view.dispatchTouchEvent(event)
                }
            } else {
                this.activity!!.dispatchTouchEvent(event)
            }
        }
    }

    private var x = 0f
    private var y = 0f

    init {
        require(context !is Application) { "XPopup的Context必须是Activity类型！" }
        lifecycleRegistry = LifecycleRegistry(this)
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop()
        setId(generateViewId())
        val contentView = LayoutInflater.from(context).inflate(this.innerLayoutId, this, false)
        contentView.setAlpha(0f)
        addView(contentView)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 如果自己接触到了点击，并且不在PopupContentView范围内点击，则进行判断是否是点击事件,如果是，则dismiss
        val rect = Rect()
        this.popupImplView.getGlobalVisibleRect(rect)
        if (!isInRect(event.getX(), event.getY(), rect)) {
            val dx: Float
            val dy: Float
            val distance: Float

            val action = event.getAction()
            if (action == MotionEvent.ACTION_DOWN) {
                x = event.getX()
                y = event.getY()
                if (popupInfo != null && popupInfo!!.xPopupCallback != null) {
                    popupInfo.xPopupCallback?.onClickOutside(this)
                }
                passTouchThrough(event)
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (popupInfo != null) {
                    if (popupInfo!!.isDismissOnTouchOutside) {
                        checkDismissArea(event)
                    }
                    if (popupInfo!!.isTouchThrough) passTouchThrough(event)
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dx = event.getX() - x
                dy = event.getY() - y
                distance = sqrt(dx * dx + dy * dy)
                passTouchThrough(event)
                if (distance < touchSlop && popupInfo != null && popupInfo!!.isDismissOnTouchOutside) {
                    checkDismissArea(event)
                }
                x = 0f
                y = 0f
            }
        }
        return true
    }

    private fun checkDismissArea(event: MotionEvent) {
        //查看是否在排除区域外
        val rects = popupInfo!!.notDismissWhenTouchInArea
        if (rects != null && rects.size > 0) {
            var inRect = false
            for (r in rects) {
                if (isInRect(event.getX(), event.getY(), r)) {
                    inRect = true
                    break
                }
            }
            if (!inRect) {
                dismiss()
            }
        } else {
            dismiss()
        }
    }
}
