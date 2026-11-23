package com.wkq.logreport

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wkq.logreport.databinding.LogReportFloatingBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/** 应用内单例悬浮窗口，只绑定当前前台Activity；移动与换页保留相对位置，不改宿主布局。 */
internal class FloatingReportIndicator(private val open: (Activity) -> Unit, private val failed: () -> Unit) {
    private var owner: Activity? = null
    private var binding: LogReportFloatingBinding? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var decor: View? = null
    private var observer: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var rightEdge: Boolean? = null
    private var verticalFraction = 0.18f
    private var focusedRoot = java.lang.ref.WeakReference<View>(null)
    private var lastFocusToken: IBinder? = null
    private val monitor = object : Runnable {
        /** 只在应用内采集期间观察窗口焦点，不读取页面文本或系统/其他App窗口。 */
        override fun run() {
            val activity = owner ?: return
            if (Build.VERSION.SDK_INT >= 29) {
                val focused = android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull {
                    it !== binding?.root && it.isShown && it.hasWindowFocus() && activityOf(it.context) === activity
                }
                if (focused != null) {
                    focusedRoot = java.lang.ref.WeakReference(focused)
                    if (lastFocusToken != focused.windowToken && !dragging) {
                        lastFocusToken = focused.windowToken
                        raise(activity)
                    }
                }
            }
            if (!dragging) reposition()
            decor?.postDelayed(this, 400)
        }
    }
    private var latestLabel = R.string.log_report_active
    private var latestBusy = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    /** 主线程展示或更新独立非抢焦点窗口；首次待宿主token就绪，旧Activity回调不能重新附着。 */
    fun show(activity: Activity, label: Int, busy: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        latestLabel = label; latestBusy = busy
        if (owner !== activity) { detach(); owner = activity }
        if (binding == null) {
            activity.window.decorView.post {
                if (owner === activity && binding == null && !activity.isFinishing && !activity.isDestroyed) {
                    create(activity)
                    update(activity, latestLabel, latestBusy)
                }
            }
        } else update(activity, label, busy)
    }

    /** 更新状态和无障碍说明，整理期间暂时禁用交互，避免重复提交。 */
    private fun update(activity: Activity, label: Int, busy: Boolean) {
        val views = binding ?: return
        views.logReportFloatingStatus.setText(label)
        views.root.contentDescription = activity.getString(R.string.log_report_floating_hint, activity.getString(label))
        views.root.isEnabled = !busy
        views.root.alpha = if (busy) 0.8f else 1f
        views.root.post { if (owner === activity) reposition() }
    }

    /** 创建Activity范围的非抢焦点窗口，既不申请系统悬浮权限，也不影响原页面尺寸及系统栏。 */
    private fun create(activity: Activity) {
        try {
            val root = activity.window.decorView
            if (root.windowToken == null) return
            val views = LogReportFloatingBinding.inflate(LayoutInflater.from(activity))
            val windowManager = activity.getSystemService(WindowManager::class.java)
            val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                setTitle("LogReportIndicator")
            }
            binding = views; manager = windowManager; params = layout; decor = root
            if (rightEdge == null) rightEdge = ViewCompat.getLayoutDirection(root) != ViewCompat.LAYOUT_DIRECTION_RTL
            views.root.setOnClickListener { if (owner === activity) open(activity) }
            views.root.setOnTouchListener { view, event -> touch(view, event) }
            views.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!dragging) reposition() }
            ViewCompat.addAccessibilityAction(views.root, activity.getString(R.string.log_report_floating_move)) { _, _ ->
                rightEdge = rightEdge != true; reposition(); true
            }
            ViewCompat.setOnApplyWindowInsetsListener(views.root) { _, insets -> if (!dragging) reposition(); insets }
            val listener = ViewTreeObserver.OnGlobalLayoutListener { if (!dragging) reposition() }
            observer = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            windowManager.addView(views.root, layout)
            root.post(monitor)
            reposition()
        } catch (_: Exception) {
            detach()
            failed()
        }
    }

    /** 读取当前窗口及系统栏/键盘安全范围，横屏、分屏和字体变化后重新约束悬浮位置。 */
    private fun area(): Rect {
        val activity = owner ?: return Rect()
        val host = decor ?: return Rect()
        val size = if (Build.VERSION.SDK_INT >= 30) manager?.currentWindowMetrics?.bounds else null
        val width = size?.width() ?: host.width
        val height = size?.height() ?: host.height
        val hostInsets = ViewCompat.getRootWindowInsets(host)
        val focusedInsets = ViewCompat.getRootWindowInsets(focusedRoot.get()?.takeIf { it.isAttachedToWindow && it.hasWindowFocus() } ?: host)
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val safe = hostInsets?.getInsets(types)
        val focusedSafe = focusedInsets?.getInsets(types)
        val left = maxOf(safe?.left ?: 0, focusedSafe?.left ?: 0)
        val right = maxOf(safe?.right ?: 0, focusedSafe?.right ?: 0)
        val top = maxOf(safe?.top ?: 0, focusedSafe?.top ?: 0)
        val bottom = maxOf(safe?.bottom ?: 0, focusedSafe?.bottom ?: 0,
            hostInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
            focusedInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
        val gap = activity.resources.getDimensionPixelSize(R.dimen.log_report_floating_gap)
        return Rect(left + gap, top + gap, (width - right - gap).coerceAtLeast(left + gap),
            (height - bottom - gap).coerceAtLeast(top + gap))
    }

    /** 只更新浮窗自身参数；其余页面保留原有布局、Insets与可点击区域。 */
    private fun reposition() {
        val views = binding ?: return
        if (params == null) return
        val activity = owner ?: return
        if (!views.root.isAttachedToWindow) return
        val bounds = area()
        val textWidth = minOf(activity.resources.getDimensionPixelSize(R.dimen.log_report_floating_max_text_width),
            bounds.width() - activity.resources.getDimensionPixelSize(R.dimen.log_report_floating_inner_width)).coerceAtLeast(1)
        if (views.logReportFloatingStatus.maxWidth != textWidth) views.logReportFloatingStatus.maxWidth = textWidth
        val maxX = maxOf(bounds.left, bounds.right - views.root.width)
        val maxY = maxOf(bounds.top, bounds.bottom - views.root.height)
        val x = if (rightEdge == true) maxX else bounds.left
        val y = (bounds.top + (maxY - bounds.top) * verticalFraction).roundToInt().coerceIn(bounds.top, maxY)
        moveTo(x, avoidEditor(x, y, views.root.width, views.root.height, bounds))
    }

    /** 软键盘收缩可用范围后临时让开当前输入框，关闭键盘后仍按原拖动比例恢复。 */
    private fun avoidEditor(x: Int, y: Int, width: Int, height: Int, bounds: Rect): Int {
        val activity = owner ?: return y
        val editor = (focusedRoot.get()?.findFocus() ?: activity.currentFocus) as? android.widget.EditText ?: return y
        if (!editor.isShown || !editor.hasFocus()) return y
        val location = IntArray(2)
        editor.getLocationOnScreen(location)
        val window = if (Build.VERSION.SDK_INT >= 30) manager?.currentWindowMetrics?.bounds else null
        val target = Rect(location[0] - (window?.left ?: 0), location[1] - (window?.top ?: 0),
            location[0] - (window?.left ?: 0) + editor.width, location[1] - (window?.top ?: 0) + editor.height)
        if (!Rect.intersects(Rect(x, y, x + width, y + height), target)) return y
        val gap = activity.resources.getDimensionPixelSize(R.dimen.log_report_floating_gap)
        val above = target.top - gap - height
        val below = target.bottom + gap
        return when {
            above >= bounds.top -> above
            below + height <= bounds.bottom -> below
            else -> y
        }
    }

    /** 点击打开结束/分享操作；超过触摸阈值后仅拖动，松手吸附最近侧边。 */
    private fun touch(view: View, event: MotionEvent): Boolean {
        val layout = params ?: return false
        val activity = owner ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (!dragging && (abs(dx) > ViewConfiguration.get(activity).scaledTouchSlop || abs(dy) > ViewConfiguration.get(activity).scaledTouchSlop)) dragging = true
                if (dragging) {
                    val bounds = area()
                    moveTo((startX + dx.roundToInt()).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - view.width)),
                        (startY + dy.roundToInt()).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - view.height)))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val bounds = area()
                    rightEdge = layout.x + view.width / 2 >= bounds.centerX()
                    verticalFraction = ((layout.y - bounds.top).toFloat() / maxOf(1, bounds.height() - view.height)).coerceIn(0f, 1f)
                    dragging = false; reposition()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && view.isEnabled) view.performClick()
                return true
            }
        }
        return false
    }

    /** 主线程移动浮窗，宿主正在结束导致token失效时立即释放。 */
    private fun moveTo(x: Int, y: Int) {
        val layout = params ?: return
        val view = binding?.root ?: return
        if (layout.x == x && layout.y == y) return
        layout.x = x; layout.y = y
        runCatching { manager?.updateViewLayout(view, layout) }.onFailure { detach(); failed() }
    }

    /** 解包主题Context，仅接受当前Activity所属窗口，避免覆盖其他应用或系统页面。 */
    private fun activityOf(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current as Activity
            val wrapper = current as? ContextWrapper ?: return null
            if (wrapper.baseContext === current) return null
            current = wrapper.baseContext
        }
        return null
    }

    /** 新的应用弹窗取得焦点后把非抢焦点状态窗置于其上，不改变弹窗输入和返回路径。 */
    fun raise(activity: Activity) {
        if (owner !== activity || dragging) return
        val view = binding?.root ?: return
        val layout = params ?: return
        runCatching {
            manager?.removeViewImmediate(view)
            manager?.addView(view, layout)
            reposition()
        }.onFailure { detach(); failed() }
    }

    /** 页面离开前台只隐藏对应窗口，不结束采集或重置拖动位置。 */
    fun hide(activity: Activity) { if (owner === activity) detach() }

    /** 结束、换页或宿主销毁时移除窗口与监听，不持有已退出的Activity。 */
    fun detach() {
        val host = decor
        host?.removeCallbacks(monitor)
        focusedRoot.clear(); lastFocusToken = null
        observer?.let { if (host?.viewTreeObserver?.isAlive == true) host.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        binding?.root?.let { view -> runCatching { manager?.removeViewImmediate(view) } }
        binding = null; manager = null; params = null; decor = null; observer = null; owner = null; dragging = false
    }
}
