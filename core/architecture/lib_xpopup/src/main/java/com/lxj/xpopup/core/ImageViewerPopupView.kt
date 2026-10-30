package com.lxj.xpopup.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.ChangeImageTransform
import androidx.transition.ChangeTransform
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.lxj.xpopup.R
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.interfaces.OnDragChangeListener
import com.lxj.xpopup.interfaces.OnImageViewerLongPressListener
import com.lxj.xpopup.interfaces.OnSrcViewUpdateListener
import com.lxj.xpopup.interfaces.XPopupImageLoader
import com.lxj.xpopup.photoview.PhotoView
import com.lxj.xpopup.util.PermissionConstants
import com.lxj.xpopup.util.XPermission
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.util.XPopupUtils.dp2px
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import com.lxj.xpopup.widget.BlankView
import com.lxj.xpopup.widget.HackyViewPager
import com.lxj.xpopup.widget.PhotoViewContainer

/**
 * Description: 大图预览的弹窗，使用Transition实现
 * Create by lxj, at 2019/1/22
 */
open class ImageViewerPopupView(context: Context) : BasePopupView(context), OnDragChangeListener,
    View.OnClickListener {
    protected var container: FrameLayout
    protected var photoViewContainer: PhotoViewContainer? = null
    protected var placeholderView: BlankView? = null
    protected var tv_pager_indicator: TextView? = null
    protected var tv_save: TextView? = null
    protected var pager: HackyViewPager? = null
    protected var argbEvaluator: ArgbEvaluator = ArgbEvaluator()
    protected var urls: MutableList<Any?>? = ArrayList<Any?>()
    protected var imageLoader: XPopupImageLoader? = null
    protected var srcViewUpdateListener: OnSrcViewUpdateListener? = null
    protected var position: Int = 0
    protected var rect: Rect? = null
    protected var srcView: ImageView? = null //动画起始的View，如果为null，移动和过渡动画效果会没有，只有弹窗的缩放功能
    protected var snapshotView: PhotoView? = null
    protected var isShowPlaceholder: Boolean = true //是否显示占位白色，当图片切换为大图时，原来的地方会有一个白色块
    protected var placeholderColor: Int = Color.parseColor("#f1f1f1") //占位View的颜色
    protected var placeholderStrokeColor: Int = -1 // 占位View的边框色
    protected var placeholderRadius: Int = -1 // 占位View的圆角
    protected var isShowSaveBtn: Boolean = true //是否显示保存按钮
    protected var isShowIndicator: Boolean = true //是否页码指示器
    protected var isInfinite: Boolean = false //是否需要无限滚动
    protected var customView: View? = null
    protected var bgColor: Int = Color.rgb(32, 36, 46) //弹窗的背景颜色，可以自定义
    var longPressListener: OnImageViewerLongPressListener? = null

    init {
        container = findViewById<FrameLayout>(R.id.container)
        if (implLayoutId > 0) {
            customView = LayoutInflater.from(getContext()).inflate(implLayoutId, container, false)
            customView!!.setVisibility(INVISIBLE)
            customView!!.setAlpha(0f)
            container.addView(customView)
        }
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_image_viewer_popup_view

    override fun initPopupContent() {
        super.initPopupContent()
        tv_pager_indicator = findViewById<TextView>(R.id.tv_pager_indicator)
        tv_save = findViewById<TextView>(R.id.tv_save)
        placeholderView = findViewById<BlankView>(R.id.placeholderView)
        photoViewContainer = findViewById<PhotoViewContainer>(R.id.photoViewContainer)
        photoViewContainer!!.setOnDragChangeListener(this)
        pager = findViewById<HackyViewPager>(R.id.pager)
        val photoViewAdapter = PhotoViewAdapter()
        pager!!.setAdapter(photoViewAdapter)
        pager!!.setCurrentItem(position)
        pager!!.setVisibility(INVISIBLE)
        addOrUpdateSnapshot()
        pager!!.setOffscreenPageLimit(2)
        pager!!.addOnPageChangeListener(photoViewAdapter)
        if (!isShowIndicator) tv_pager_indicator!!.setVisibility(GONE)
        if (!isShowSaveBtn) {
            tv_save!!.setVisibility(GONE)
        } else {
            tv_save!!.setOnClickListener(this)
        }
    }

    private fun setupPlaceholder() {
        placeholderView!!.setVisibility(if (isShowPlaceholder) VISIBLE else INVISIBLE)
        if (isShowPlaceholder) {
            if (placeholderColor != -1) {
                placeholderView!!.color = placeholderColor
            }
            if (placeholderRadius != -1) {
                placeholderView!!.radius = placeholderRadius
            }
            if (placeholderStrokeColor != -1) {
                placeholderView!!.strokeColor = placeholderStrokeColor
            }
            XPopupUtils.setWidthHeight(placeholderView!!, rect!!.width(), rect!!.height())
            placeholderView!!.setTranslationX(rect!!.left.toFloat())
            placeholderView!!.setTranslationY(rect!!.top.toFloat())
            placeholderView!!.invalidate()
        }
    }

    private fun showPagerIndicator() {
        if (urls!!.size > 1) {
            val posi = this.realPosition
            tv_pager_indicator!!.setText((posi + 1).toString() + "/" + urls!!.size)
        }
        if (isShowSaveBtn) tv_save!!.setVisibility(VISIBLE)
    }

    private fun addOrUpdateSnapshot() {
        if (srcView == null) return
        if (snapshotView == null) {
            snapshotView = PhotoView(getContext())
            snapshotView!!.setEnabled(false)
            photoViewContainer!!.addView(snapshotView)
            snapshotView!!.setScaleType(srcView!!.getScaleType())
            snapshotView!!.setTranslationX(rect!!.left.toFloat())
            snapshotView!!.setTranslationY(rect!!.top.toFloat())
            XPopupUtils.setWidthHeight(snapshotView!!, rect!!.width(), rect!!.height())
        }
        val realPosition = this.realPosition
        snapshotView!!.setTag(realPosition)
        setupPlaceholder()
        if (imageLoader != null) imageLoader!!.loadSnapshot(
            urls!!.get(realPosition)!!,
            snapshotView!!,
            srcView
        )
    }

    public override fun doShowAnimation() {
        if (srcView == null) {
            photoViewContainer!!.setBackgroundColor(bgColor)
            pager!!.setVisibility(VISIBLE)
            showPagerIndicator()
            photoViewContainer!!.isReleasing = false
            doAfterShow()
            if (customView != null) {
                customView!!.setAlpha(1f)
                customView!!.setVisibility(VISIBLE)
            }
            return
        }
        photoViewContainer!!.isReleasing = true
        if (customView != null) customView!!.setVisibility(VISIBLE)
        snapshotView!!.setVisibility(VISIBLE)
        doAfterShow()
        snapshotView!!.post(object : Runnable {
            override fun run() {
                TransitionManager.beginDelayedTransition(
                    (snapshotView!!.getParent() as ViewGroup?)!!,
                    TransitionSet()
                        .setDuration(animationDuration.toLong())
                        .addTransition(ChangeBounds())
                        .addTransition(ChangeTransform())
                        .addTransition(ChangeImageTransform())
                        .setInterpolator(FastOutSlowInInterpolator())
                        .addListener(object : TransitionListenerAdapter() {
                            override fun onTransitionEnd(transition: Transition) {
                                pager!!.setVisibility(VISIBLE)
                                snapshotView!!.setVisibility(INVISIBLE)
                                showPagerIndicator()
                                photoViewContainer!!.isReleasing = false
                            }
                        })
                )
                snapshotView!!.setTranslationY(0f)
                snapshotView!!.setTranslationX(0f)
                snapshotView!!.setScaleType(ImageView.ScaleType.FIT_CENTER)
                XPopupUtils.setWidthHeight(
                    snapshotView!!,
                    photoViewContainer!!.getWidth(),
                    photoViewContainer!!.getHeight()
                )

                // do shadow anim.
                animateShadowBg(bgColor)
                if (customView != null) customView!!.animate().alpha(1f)
                    .setDuration(animationDuration.toLong()).start()
            }
        })
    }

    private fun animateShadowBg(endColor: Int) {
        val start = (photoViewContainer!!.getBackground() as ColorDrawable).getColor()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener(object : AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                photoViewContainer!!.setBackgroundColor(
                    (argbEvaluator.evaluate(
                        animation.getAnimatedFraction(),
                        start, endColor
                    ) as Int?)!!
                )
            }
        })
        animator.setDuration(animationDuration.toLong())
            .setInterpolator(LinearInterpolator())
        animator.start()
    }

    public override fun doDismissAnimation() {
        if (srcView == null) {
            photoViewContainer!!.setBackgroundColor(Color.TRANSPARENT)
            doAfterDismiss()
            pager!!.setVisibility(INVISIBLE)
            placeholderView!!.setVisibility(INVISIBLE)
            if (customView != null) {
                customView!!.setAlpha(0f)
                customView!!.setVisibility(INVISIBLE)
            }
            return
        }
        tv_pager_indicator!!.setVisibility(INVISIBLE)
        tv_save!!.setVisibility(INVISIBLE)
        pager!!.setVisibility(INVISIBLE)
        photoViewContainer!!.isReleasing = true
        snapshotView!!.setVisibility(VISIBLE)
        snapshotView!!.post(object : Runnable {
            override fun run() {
                TransitionManager.beginDelayedTransition(
                    (snapshotView!!.getParent() as ViewGroup?)!!,
                    TransitionSet()
                        .setDuration(animationDuration.toLong())
                        .addTransition(ChangeBounds())
                        .addTransition(ChangeTransform())
                        .addTransition(ChangeImageTransform())
                        .setInterpolator(FastOutSlowInInterpolator())
                        .addListener(object : TransitionListenerAdapter() {
                            override fun onTransitionStart(transition: Transition) {
                                super.onTransitionStart(transition)
                                doAfterDismiss()
                            }

                            override fun onTransitionEnd(transition: Transition) {
                                pager!!.setScaleX(1f)
                                pager!!.setScaleY(1f)
                                snapshotView!!.setScaleX(1f)
                                snapshotView!!.setScaleY(1f)
                                placeholderView!!.setVisibility(INVISIBLE)
                                snapshotView!!.setTranslationX(rect!!.left.toFloat())
                                snapshotView!!.setTranslationY(rect!!.top.toFloat())
                                XPopupUtils.setWidthHeight(
                                    snapshotView!!,
                                    rect!!.width(),
                                    rect!!.height()
                                )
                            }
                        })
                )

                snapshotView!!.setScaleX(1f)
                snapshotView!!.setScaleY(1f)
                snapshotView!!.setTranslationX(rect!!.left.toFloat())
                snapshotView!!.setTranslationY(rect!!.top.toFloat())
                snapshotView!!.setScaleType(srcView!!.getScaleType())
                XPopupUtils.setWidthHeight(snapshotView!!, rect!!.width(), rect!!.height())

                // do shadow anim.
                animateShadowBg(Color.TRANSPARENT)
                if (customView != null) customView!!.animate().alpha(0f)
                    .setDuration(animationDuration.toLong())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            super.onAnimationEnd(animation)
                            if (customView != null) customView!!.setVisibility(INVISIBLE)
                        }
                    })
                    .start()
            }
        })
    }

    public override fun dismiss() {
        if (popupStatus != PopupStatus.Show) return
        popupStatus = PopupStatus.Dismissing
        doDismissAnimation()
    }

    fun setImageUrls(urls: MutableList<Any?>?): ImageViewerPopupView {
        this.urls = urls
        return this
    }

    fun setSrcViewUpdateListener(srcViewUpdateListener: OnSrcViewUpdateListener?): ImageViewerPopupView {
        this.srcViewUpdateListener = srcViewUpdateListener
        return this
    }

    fun setXPopupImageLoader(imageLoader: XPopupImageLoader?): ImageViewerPopupView {
        this.imageLoader = imageLoader
        return this
    }

    /**
     * 是否显示白色占位区块
     *
     * @param isShow
     * @return
     */
    fun isShowPlaceholder(isShow: Boolean): ImageViewerPopupView {
        this.isShowPlaceholder = isShow
        return this
    }

    /**
     * 是否显示页码指示器
     *
     * @param isShow
     * @return
     */
    fun isShowIndicator(isShow: Boolean): ImageViewerPopupView {
        this.isShowIndicator = isShow
        return this
    }

    /**
     * 是否显示保存按钮
     *
     * @param isShowSaveBtn
     * @return
     */
    fun isShowSaveButton(isShowSaveBtn: Boolean): ImageViewerPopupView {
        this.isShowSaveBtn = isShowSaveBtn
        return this
    }

    fun isInfinite(isInfinite: Boolean): ImageViewerPopupView {
        this.isInfinite = isInfinite
        return this
    }

    fun setPlaceholderColor(color: Int): ImageViewerPopupView {
        this.placeholderColor = color
        return this
    }

    fun setPlaceholderRadius(radius: Int): ImageViewerPopupView {
        this.placeholderRadius = radius
        return this
    }

    fun setPlaceholderStrokeColor(strokeColor: Int): ImageViewerPopupView {
        this.placeholderStrokeColor = strokeColor
        return this
    }

    fun setBgColor(bgColor: Int): ImageViewerPopupView {
        this.bgColor = bgColor
        return this
    }

    fun setLongPressListener(longPressListener: OnImageViewerLongPressListener?): ImageViewerPopupView {
        this.longPressListener = longPressListener
        return this
    }

    /**
     * 设置单个使用的源View。单个使用的情况下，无需设置url集合和SrcViewUpdateListener
     *
     * @param srcView
     * @return
     */
    fun setSingleSrcView(srcView: ImageView?, url: Any?): ImageViewerPopupView {
        if (this.urls == null) {
            urls = ArrayList<Any?>()
        }
        urls!!.clear()
        urls!!.add(url)
        setSrcView(srcView, 0)
        return this
    }

    fun setSrcView(srcView: ImageView?, position: Int): ImageViewerPopupView {
        this.srcView = srcView
        this.position = position
        if (srcView != null) {
            val locations = IntArray(2)
            this.srcView!!.getLocationInWindow(locations)
            var left = locations[0] /*- activityContentLeft*/
            if (isLayoutRtl(getContext())) {
                left = -(getAppWidth(getContext()) - locations[0] - srcView.getWidth())
                rect = Rect(
                    left,
                    locations[1],
                    left + srcView.getWidth(),
                    locations[1] + srcView.getHeight()
                )
            } else {
                rect = Rect(
                    left,
                    locations[1],
                    left + srcView.getWidth(),
                    locations[1] + srcView.getHeight()
                )
            }
        }
        return this
    }

    fun updateSrcView(srcView: ImageView?) {
        setSrcView(srcView, position)
        addOrUpdateSnapshot()
    }

    override fun onRelease() {
        dismiss()
    }

    override fun onDragChange(dy: Int, scale: Float, fraction: Float) {
        tv_pager_indicator!!.setAlpha(1 - fraction)
        if (customView != null) customView!!.setAlpha(1 - fraction)
        if (isShowSaveBtn) tv_save!!.setAlpha(1 - fraction)
        photoViewContainer!!.setBackgroundColor(
            (argbEvaluator.evaluate(
                fraction * .8f,
                bgColor,
                android.graphics.Color.TRANSPARENT
            ) as Int?)!!
        )
    }

    override fun onDismiss() {
        super.onDismiss()
        srcView = null
        srcViewUpdateListener = null
    }

    override fun onClick(v: View?) {
        if (v === tv_save) save()
    }

    public override fun destroy() {
        super.destroy()
        pager!!.removeOnPageChangeListener((pager!!.getAdapter() as PhotoViewAdapter?)!!)
        imageLoader = null
    }

    protected val realPosition: Int
        get() = if (isInfinite) position % urls!!.size else position

    /**
     * 保存图片到相册，会自动检查是否有保存权限
     */
    protected open fun save() {
        XPermission.create(getContext(), PermissionConstants.STORAGE)!!
            .callback(object : XPermission.SimpleCallback {
                override fun onGranted() {
                    XPopupUtils.saveBmpToAlbum(
                        getContext(),
                        imageLoader!!,
                        urls!!.get(this@ImageViewerPopupView.realPosition)!!
                    )
                }

                override fun onDenied() {}
            })
            .request()
    }

    inner class PhotoViewAdapter : PagerAdapter(), OnPageChangeListener {
        override fun getCount(): Int {
            return if (isInfinite) 100000 else urls!!.size
        }

        override fun isViewFromObject(view: View, o: Any): Boolean {
            return o === view
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val realPosition = if (isInfinite) position % urls!!.size else position
            //1. build container
            val fl = buildContainer(container.getContext())
            val progressBar = buildProgressBar(container.getContext())

            //2. add ImageView，maybe PhotoView or SubsamplingScaleImageView
            val view = imageLoader!!.loadImage(
                realPosition, urls!!.get(realPosition)!!, this@ImageViewerPopupView, snapshotView!!,
                progressBar
            )

            //3. add View
            fl.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            //4. add ProgressBar
            fl.addView(progressBar)

            container.addView(fl)
            return fl
        }

        private fun buildContainer(context: Context): FrameLayout {
            val fl = FrameLayout(context)
            fl.setLayoutParams(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return fl
        }

        private fun buildProgressBar(context: Context?): ProgressBar {
            val progressBar = ProgressBar(context)
            progressBar.setIndeterminate(true)
            val size = dp2px(container.getContext(), 40f)
            val params = LayoutParams(size, size)
            params.gravity = Gravity.CENTER
            progressBar.setLayoutParams(params)
            progressBar.setVisibility(GONE)
            return progressBar
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }

        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
        }

        override fun onPageSelected(i: Int) {
            position = i
            showPagerIndicator()
            //更新srcView
            if (srcViewUpdateListener != null) {
                srcViewUpdateListener!!.onSrcViewUpdate(
                    this@ImageViewerPopupView,
                    this@ImageViewerPopupView.realPosition
                )
            }
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }
}
