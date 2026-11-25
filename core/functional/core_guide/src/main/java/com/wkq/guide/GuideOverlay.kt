package com.wkq.guide

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.wkq.guide.databinding.GuideCardBinding
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 页内覆盖层：坐标、Insets、动画和触摸均局限于当前容器，不修改宿主 Window 属性。 */
@SuppressLint("ViewConstructor") // 内部组件需要明确的宿主与生命周期，不提供 XML 构造入口。
internal class GuideOverlay(
    private val host: ViewGroup,
    private val owner: LifecycleOwner,
    private val steps: List<GuideStep>,
    private val options: GuideOptions,
    private val backDispatcher: OnBackPressedDispatcher?,
    private val finished: (GuideResult) -> Unit,
) : FrameLayout(host.context), GuideActions, DefaultLifecycleObserver {
    private var index = 0
    private var closed = false
    private var ending = false
    private var ready = false
    private var card: View? = null
    private var announcedIndex = -1
    private var waitingSince = SystemClock.uptimeMillis()
    private var currentTarget: View? = null
    private var downX = 0f
    private var downY = 0f
    private val targetRect = RectF()
    private val drawnRect = RectF()
    private val holeRect = RectF()
    private var cardSurface = 0
    private val visibleRect = Rect()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val lastSafe = RectF()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var movement: ValueAnimator? = null
    private var emphasis: ValueAnimator? = null
    private var emphasisFraction = 0f
    private val hiddenAccessibility = linkedMapOf<View, Int>()
    private val oldFocus = host.findFocus()
    private var back: OnBackPressedCallback? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { updateGeometry(); true }
    private val timeout = Runnable { if (!ready && !closed && !ending) finish(GuideResult.TARGET_UNAVAILABLE) }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusableInTouchMode = true
        ViewCompat.setAccessibilityPaneTitle(this, steps.first().title)
    }

    /** 注册唯一会话、生命周期、返回及布局观察；目标未出现时等待有上限。 */
    fun start() {
        host.addView(this, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        owner.lifecycle.addObserver(this)
        back = object : OnBackPressedCallback(true) {
            /** 引导存在时返回关闭引导，不擅自回退业务页面。 */
            override fun handleOnBackPressed() { finish(GuideResult.BACK) }
        }.also { callback -> backDispatcher?.addCallback(owner, callback) }
        host.viewTreeObserver.addOnPreDrawListener(preDraw)
        var branch: View = this
        var ancestor: ViewGroup? = host
        while (ancestor != null) {
            ancestor.children.filter { it !== branch }.forEach {
                hiddenAccessibility[it] = it.importantForAccessibility
                it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            branch = ancestor
            ancestor = ancestor.parent as? ViewGroup
        }
        showStep()
    }

    /** 创建当前提示内容，清理旧步动画，等待目标布局后再显示。 */
    private fun showStep() {
        if (closed || ending) return
        card?.animate()?.cancel()
        movement?.cancel()
        emphasis?.cancel()
        emphasisFraction = 0f
        ready = false
        waitingSince = SystemClock.uptimeMillis()
        removeCallbacks(timeout)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) postDelayed(timeout, options.targetTimeoutMillis)
        removeAllViews()
        currentTarget = null
        val step = steps[index]
        val content = step.customContent?.invoke(this) ?: defaultCard(step)
        require(content.parent == null) { "Custom guide content must not have a parent" }
        card = content
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.visibility = INVISIBLE
        ViewCompat.setAccessibilityPaneTitle(this, step.title)
        requestLayout()
        invalidate()
    }

    /** 默认提示分开标题、正文与主次操作，导航按实际文字宽度自适应排列。 */
    private fun defaultCard(step: GuideStep): View {
        val binding = GuideCardBinding.inflate(LayoutInflater.from(context), this, false)
        val style = step.style ?: options.style
        val colors = style.colors
        val hostSurface = resolvedThemeColor(R.attr.colorSurface) ?: themeColor(android.R.attr.colorBackground, R.color.guide_surface)
        val contrast = style.appearance == GuideAppearance.CONTRAST
        val inverse = if (ColorUtils.calculateLuminance(ColorUtils.compositeColors(hostSurface, context.getColor(R.color.guide_surface))) > .25)
            R.color.guide_tip_dark_surface else R.color.guide_tip_light_surface
        val surface = ColorUtils.compositeColors(colors.surface ?: if (contrast) context.getColor(inverse) else hostSurface, context.getColor(R.color.guide_surface))
        val primary = ColorUtils.compositeColors(colors.primary ?: accentColor(), surface)
        val light = 0xffffffff.toInt()
        val dark = 0xff101114.toInt()
        val foreground = colors.onPrimary ?: if (ColorUtils.calculateContrast(dark, primary) >= ColorUtils.calculateContrast(light, primary)) dark else light
        val darkPanel = ColorUtils.calculateLuminance(surface) < .25
        val titleColor = colors.title ?: if (contrast || colors.surface != null)
            context.getColor(if (darkPanel) R.color.guide_tip_dark_title else R.color.guide_tip_light_title)
            else themeColor(android.R.attr.textColorPrimary, R.color.guide_title)
        val bodyColor = colors.body ?: if (contrast || colors.surface != null)
            context.getColor(if (darkPanel) R.color.guide_tip_dark_body else R.color.guide_tip_light_body)
            else themeColor(android.R.attr.textColorSecondary, R.color.guide_body)
        cardSurface = surface
        binding.root.background = GradientDrawable().apply {
            cornerRadius = dp(style.cornerDp)
            setColor(surface)
            setStroke(dp(1f).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(titleColor, 20))
        }
        binding.progress.text = context.getString(R.string.guide_step_compact, index + 1, steps.size)
        binding.progress.contentDescription = context.getString(R.string.guide_step, index + 1, steps.size)
        binding.progress.setTextColor(bodyColor)
        binding.title.text = step.title
        binding.title.textSize = style.titleSp
        binding.title.setTextColor(titleColor)
        ViewCompat.setAccessibilityHeading(binding.title, true)
        binding.message.text = step.message
        binding.message.textSize = style.bodySp
        binding.message.setTextColor(bodyColor)
        binding.message.setLineSpacing(dp(style.lineSpacingDp), 1f)
        binding.next.setText(if (index == steps.lastIndex) R.string.guide_done else R.string.guide_next)
        binding.next.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (index == steps.lastIndex) 0 else R.drawable.guide_chevron_forward, 0)
        binding.next.compoundDrawablePadding = dp(4f).toInt()
        binding.next.setOnClickListener { next() }
        binding.previous.visibility = if (index == 0) GONE else VISIBLE
        binding.previous.setOnClickListener { previous() }
        binding.skip.setOnClickListener { skip() }
        binding.target.visibility = if (step.targetAction == TargetAction.NONE) GONE else VISIBLE
        binding.target.setOnClickListener { activateTarget() }
        listOf(binding.next, binding.previous, binding.skip, binding.target).forEach { button ->
            button.textSize = style.buttonSp
            button.isAllCaps = false
            button.setSingleLine(false)
            button.ellipsize = null
            button.stateListAnimator = null
            button.minimumHeight = dp(48f).toInt()
            button.setPaddingRelative(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            val prominent = button === binding.next
            val filled = prominent && style.filledPrimaryAction
            val textColor = if (filled) foreground else if (prominent) readableAccent(primary, surface) else colors.secondary ?: bodyColor
            val shape = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(if (filled) primary else android.graphics.Color.TRANSPARENT)
            }
            val mask = GradientDrawable().apply { cornerRadius = dp(12f); setColor(android.graphics.Color.WHITE) }
            button.backgroundTintList = null
            button.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 32)), shape, mask)
            button.setTextColor(textColor)
            button.compoundDrawableTintList = ColorStateList.valueOf(textColor)
        }
        return GuideCardHost(binding.root, binding.actions, binding.actionSpacer).apply {
            background = binding.root.background
            binding.root.background = null
            elevation = dp(4f)
            clipToOutline = true
        }
    }

    /** 文本动作保持品牌色相，在反差面板上逐级提高对比度，避免深蓝字落在深灰上。 */
    private fun readableAccent(accent: Int, surface: Int): Int {
        if (ColorUtils.calculateContrast(accent, surface) >= 4.5) return accent
        val destination = if (ColorUtils.calculateLuminance(surface) < .25) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        for (step in 1..10) {
            val candidate = ColorUtils.blendARGB(accent, destination, step / 10f)
            if (ColorUtils.calculateContrast(candidate, surface) >= 4.5) return candidate
        }
        return destination
    }

    /** 解析宿主主题色，未定义或无法解析时使用库自身的日夜资源。 */
    private fun themeColor(attribute: Int, fallback: Int): Int {
        return resolvedThemeColor(attribute) ?: context.getColor(fallback)
    }

    /** 优先读取现代宿主的主色，普通系统主题则回退 colorAccent，不强制第三方主题。 */
    private fun accentColor(): Int = resolvedThemeColor(R.attr.colorPrimary) ?: themeColor(android.R.attr.colorAccent, R.color.guide_primary)

    /** 读取真实主题属性；未声明值或无法解析时由调用方选择下一级回退。 */
    private fun resolvedThemeColor(attribute: Int): Int? {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attribute, value, true)) {
            if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
            if (value.resourceId != 0) return runCatching { context.getColorStateList(value.resourceId).defaultColor }.getOrNull()
        }
        return null
    }

    /** 根据真实屏幕坐标转为覆盖层坐标；绝不猜测状态栏高度或重复给宿主加 padding。 */
    private fun updateGeometry() {
        if (closed || ending || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || width == 0 || height == 0) return
        val target = steps[index].target()
        val valid = target != null && target !== this && !isAncestorOf(target) && target.isAttachedToWindow &&
            target.windowToken == windowToken && target.isShown && target.alpha > 0f && target.getGlobalVisibleRect(visibleRect) && target.width > 0 && target.height > 0
        if (!valid) {
            awaitTarget()
            return
        }
        getLocationOnScreen(location)
        // getGlobalVisibleRect 使用 root 坐标；加入 root 的屏幕偏移后再统一转换。
        rootView.getLocationOnScreen(rootLocation)
        val rect = RectF(visibleRect).apply { offset((rootLocation[0] - location[0]).toFloat(), (rootLocation[1] - location[1]).toFloat()) }
        if (!rect.intersect(0f, 0f, width.toFloat(), height.toFloat())) { awaitTarget(); return }
        val first = !ready
        val targetChanged = targetRect != rect
        currentTarget = target
        if (first || targetChanged) {
            targetRect.set(rect)
            if (first && !drawnRect.isEmpty && animationsEnabled()) animateTarget(rect) else { movement?.cancel(); drawnRect.set(rect) }
            invalidate()
        }
        val safe = safeBounds()
        if (safe.width() < dp(48f) || safe.height() < dp(96f)) {
            awaitTarget()
            return
        }
        if (first || targetChanged || safe != lastSafe || card?.isLayoutRequested == true) {
            lastSafe.set(safe)
            ready = true
            removeCallbacks(timeout)
            layoutCard(safe)
        }
        if (first) {
            card?.visibility = VISIBLE
            if (animationsEnabled()) {
                if (announcedIndex < 0) {
                    alpha = 0f
                    animate().setListener(null).alpha(1f).setDuration(options.motion.enterMillis).start()
                }
                card?.alpha = 0f
                card?.translationY = dp(4f)
                card?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(if (announcedIndex < 0) options.motion.enterMillis else options.motion.transitionMillis)?.start()
            }
            startEmphasis()
            if (steps[index].customContent == null) card?.findViewById<View>(R.id.next)?.requestFocus() else card?.requestFocus()
            if (announcedIndex != index) {
                announcedIndex = index
                options.onStep(steps[index].id, index)
            }
        }
    }

    /** 目标或窗口暂不可用时清除旧坐标，有上限地等待重新布局。 */
    private fun awaitTarget() {
        val wasReady = ready
        currentTarget = null
        card?.visibility = INVISIBLE
        if (ready) {
            ready = false
            waitingSince = SystemClock.uptimeMillis()
            removeCallbacks(timeout)
            postDelayed(timeout, options.targetTimeoutMillis)
        }
        if (SystemClock.uptimeMillis() - waitingSince >= options.targetTimeoutMillis) finish(GuideResult.TARGET_UNAVAILABLE)
        if (wasReady) invalidate()
    }

    /** 排除自定义卡片内部目标，防止目标与覆盖层递归定位。 */
    private fun isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent is View) { if (parent === this) return true; parent = parent.parent }
        return false
    }

    /** 可见系统栏、caption、刘海、手势区与 IME 按每边最大值避让，隐藏状态栏不强制留空。 */
    private fun safeBounds(): RectF {
        val insets = ViewCompat.getRootWindowInsets(this)
        val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.mandatorySystemGestures())
        rootView.getLocationOnScreen(rootLocation)
        getLocationOnScreen(location)
        val margin = dp((steps[index].style ?: options.style).marginDp)
        return RectF(
            max(0f, (rootLocation[0] + (bars?.left ?: 0) - location[0]).toFloat()) + margin,
            max(0f, (rootLocation[1] + (bars?.top ?: 0) - location[1]).toFloat()) + margin,
            min(width.toFloat(), (rootLocation[0] + rootView.width - (bars?.right ?: 0) - location[0]).toFloat()) - margin,
            min(height.toFloat(), (rootLocation[1] + rootView.height - (bars?.bottom ?: 0) - location[1]).toFloat()) - margin,
        )
    }

    /** 测量真实换行高度，优先放目标上下；长文本限制在可用区域并保持正文可滚动。 */
    private fun layoutCard(safe: RectF) {
        val content = card ?: return
        val style = steps[index].style ?: options.style
        val width = min(dp(style.maxWidthDp), safe.width()).toInt().coerceAtLeast(1)
        val gap = dp(16f)
        val available = max(safe.bottom - targetRect.bottom - gap, targetRect.top - safe.top - gap)
        val maxHeight = if (available >= dp(220f)) min(safe.height(), available) else safe.height()
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxHeight.toInt().coerceAtLeast(1), MeasureSpec.AT_MOST))
        val result = GuidePlacement.place(GuideBounds(safe.left, safe.top, safe.right, safe.bottom),
            GuideBounds(targetRect.left, targetRect.top, targetRect.right, targetRect.bottom),
            content.measuredWidth.toFloat(), content.measuredHeight.toFloat(), gap)
        content.layout(result.left.toInt(), result.top.toInt(), result.right.toInt(), result.bottom.toInt())
    }

    /** 覆盖层使用自身约束，不让默认 FrameLayout 把提示卡片重置到左上角。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (ready) layoutCard(safeBounds())
    }

    /** 绘制偶奇遮罩形成真实透孔，避免整窗离屏位图和系统栏状态变更。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready) return
        val step = steps[index]
        val style = step.style ?: options.style
        val hole = holeRect.apply { set(drawnRect); inset(-dp(step.paddingDp), -dp(step.paddingDp)) }
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (step.shape == HighlightShape.CIRCLE) path.addCircle(hole.centerX(), hole.centerY(), hypot(hole.width(), hole.height()) / 2, Path.Direction.CW)
        else path.addRoundRect(hole, dp(step.radiusDp), dp(step.radiusDp), Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = style.colors.scrim ?: context.getColor(R.color.guide_scrim)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = style.colors.highlight ?: style.colors.primary ?: accentColor()
        paint.alpha = (255 * (1f - emphasisFraction * .65f)).toInt()
        val spread = if (options.motion.emphasis == GuideEmphasis.RIPPLE) dp(12f) * emphasisFraction else dp(2f) * emphasisFraction
        hole.inset(-spread, -spread)
        if (step.shape == HighlightShape.CIRCLE) canvas.drawCircle(hole.centerX(), hole.centerY(), hypot(hole.width(), hole.height()) / 2, paint)
        else canvas.drawRoundRect(hole, dp(step.radiusDp), dp(step.radiusDp), paint)
        paint.alpha = 255
        drawPointer(canvas, style)
    }

    /** 小箭头连接提示与目标；空间降级导致重叠时隐藏，避免指向错误或穿过内容。 */
    private fun drawPointer(canvas: Canvas, style: GuideStyle) {
        val content = card ?: return
        if (!style.showArrow || steps[index].customContent != null) return
        val yTop = content.top + content.translationY
        val yBottom = content.bottom + content.translationY
        val down = yTop >= targetRect.bottom
        val up = yBottom <= targetRect.top
        if (!down && !up) return
        val inset = dp(style.cornerDp + 12f).coerceAtMost(content.width / 2f)
        val x = targetRect.centerX().coerceIn(content.left + inset, content.right - inset)
        val edge = if (down) yTop + dp(1f) else yBottom - dp(1f)
        val tip = if (down) yTop - dp(8f) else yBottom + dp(8f)
        path.reset()
        path.moveTo(x - dp(9f), edge)
        path.lineTo(x, tip)
        path.lineTo(x + dp(9f), edge)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = cardSurface
        paint.alpha = (255 * content.alpha).toInt()
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    /** 遮罩拦截完整手势；高亮区点击采用显式策略，不向下层注入 MotionEvent。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ending || !ready) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; return true }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (hypot(event.x - downX, event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) return true
            if (targetRect.contains(event.x, event.y) && targetRect.contains(downX, downY)) activateTarget()
            else if (options.dismissOnOutside) finish(GuideResult.DISMISSED)
            performClick()
        }
        return true
    }

    /** 提供触摸点击语义；业务目标只由 activateTarget 调用。 */
    override fun performClick(): Boolean { super.performClick(); return true }

    /** 目标点击最多派发一次；业务导航可能销毁页面，调用后先检查会话仍然有效。 */
    private fun activateTarget() {
        if (!ready || ending || closed) return
        val step = steps[index]
        when (step.targetAction) {
            TargetAction.NONE -> Unit
            TargetAction.NEXT -> next()
            TargetAction.CLICK_AND_NEXT -> {
                val target = currentTarget ?: return
                if (!target.isEnabled || !target.isClickable) return
                ready = false
                target.performClick()
                if (!closed && !ending) next()
            }
        }
    }

    /** 下一步或阅读完成，不代表宿主业务已成功执行。 */
    override fun next() {
        if (closed || ending) return
        if (index == steps.lastIndex) finish(GuideResult.COMPLETED)
        else { index++; showStep() }
    }

    /** 返回前一步并重新定位目标。 */
    override fun previous() { if (!closed && !ending && index > 0) { index--; showStep() } }

    /** 用户主动跳过全部剩余步骤。 */
    override fun skip() { finish(GuideResult.SKIPPED) }

    /** 检查系统动画开关，尊重减少动态效果的系统设置。 */
    private fun animationsEnabled() = options.motion.enabled && ValueAnimator.areAnimatorsEnabled()

    /** 在不同步骤间插值移动高亮，实际滚动追踪直接更新坐标。 */
    private fun animateTarget(destination: RectF) {
        val start = RectF(drawnRect)
        movement?.cancel()
        movement = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = options.motion.transitionMillis
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                drawnRect.set(start.left + (destination.left - start.left) * f, start.top + (destination.top - start.top) * f,
                    start.right + (destination.right - start.right) * f, start.bottom + (destination.bottom - start.bottom) * f)
                invalidate()
            }
            start()
        }
    }

    /** 强调效果有明确次数，不在阅读过程中无限循环。 */
    private fun startEmphasis() {
        if (!animationsEnabled() || options.motion.emphasis == GuideEmphasis.NONE || options.motion.emphasisCount == 0) return
        emphasis = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 900
            repeatCount = options.motion.emphasisCount - 1
            addUpdateListener { emphasisFraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** dp 统一在当前窗口资源密度下换算。 */
    private fun dp(value: Float) = value * resources.displayMetrics.density

    /** 宿主离开前台时隐藏引导并暂停等待，不抢占权限页或后台窗口。 */
    override fun onStop(owner: LifecycleOwner) {
        visibility = INVISIBLE
        removeCallbacks(timeout)
        movement?.cancel()
        emphasis?.cancel()
    }

    /** 恢复时重新等待目标并定位，不重新派发同一步展示回调。 */
    override fun onStart(owner: LifecycleOwner) {
        visibility = VISIBLE
        ready = false
        waitingSince = SystemClock.uptimeMillis()
        removeCallbacks(timeout)
        postDelayed(timeout, options.targetTimeoutMillis)
        invalidate()
    }

    /** 生命周期销毁立即清理，不等待动画结束。 */
    override fun onDestroy(owner: LifecycleOwner) { finish(GuideResult.DESTROYED, false) }

    /** 容器脱离窗口时清理监听，防止宿主未销毁却保留无效覆盖层。 */
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); if (!closed) finish(GuideResult.DESTROYED, false) }

    /** 统一结束入口；先停止交互，退出动画完成后移除，销毁和替换立即处理。 */
    fun finish(result: GuideResult, animate: Boolean = true) {
        if (closed) return
        if (ending && animate) return
        ending = true
        removeCallbacks(timeout)
        card?.animate()?.cancel()
        movement?.cancel()
        emphasis?.cancel()
        animate().cancel()
        if (animate && isAttachedToWindow && animationsEnabled() && result != GuideResult.DESTROYED) {
            animate().alpha(0f).setDuration(options.motion.exitMillis).setListener(object : AnimatorListenerAdapter() {
                /** 退出完成后执行一次释放；动画取消由调用方明确完成清理。 */
                override fun onAnimationEnd(animation: Animator) { release(result) }
            }).start()
        } else release(result)
    }

    /** 释放全部监听、返回拦截及可访问性状态；结束回调仅派发一次。 */
    private fun release(result: GuideResult) {
        if (closed) return
        closed = true
        animate().setListener(null)
        animate().cancel()
        owner.lifecycle.removeObserver(this)
        back?.remove()
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(preDraw)
        hiddenAccessibility.forEach { (view, previous) ->
            if (view.importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) view.importantForAccessibility = previous
        }
        hiddenAccessibility.clear()
        (parent as? ViewGroup)?.removeView(this)
        if (oldFocus?.isAttachedToWindow == true) oldFocus.requestFocus()
        currentTarget = null
        card = null
        removeAllViews()
        finished(result)
    }
}
