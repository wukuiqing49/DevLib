package com.lxj.xpopup.impl

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.lxj.xpopup.R
import com.lxj.xpopup.core.CenterPopupView
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 加载对话框
 * Create by dance, at 2018/12/16
 */
open class LoadingPopupView(context: Context, bindLayoutId: Int) : CenterPopupView(context) {
    enum class Style {
        Spinner, ProgressBar
    }

    private var loadingStyle: Style? = Style.Spinner
    private var tv_title: TextView? = null
    private var progressBar: View? = null
    private var spinnerView: View? = null

    override val implLayoutId: Int
        get() = if (bindLayoutId != 0) bindLayoutId else R.layout._xpopup_center_impl_loading

    override fun onCreate() {
        super.onCreate()
        tv_title = findViewById<TextView?>(R.id.tv_title)
        progressBar = findViewById<View?>(R.id.loadProgress)
        spinnerView = findViewById<View?>(R.id.loadview)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupImplView.setElevation(10f)
        }
        if (bindLayoutId == 0) {
            popupImplView.setBackground(
                XPopupUtils.createDrawable(
                    Color.parseColor("#212121"),
                    popupInfo.borderRadius
                )
            )
        }
        setup()
    }

    private var firstShow = true

    override fun onShow() {
        super.onShow()
        firstShow = false
    }

    protected open fun setup() {
        post(object : Runnable {
            override fun run() {
                if (!firstShow) {
                    val set = TransitionSet()
                        .setDuration(animationDuration.toLong())
                        .addTransition(Fade())
                        .addTransition(ChangeBounds())
                    TransitionManager.beginDelayedTransition(centerPopupContainer, set)
                }
                if (title == null || title!!.length == 0) {
                    XPopupUtils.setVisible(tv_title, false)
                } else {
                    XPopupUtils.setVisible(tv_title, true)
                    if (tv_title != null) tv_title!!.setText(title)
                }

                if (loadingStyle == Style.Spinner) {
                    XPopupUtils.setVisible(progressBar, false)
                    XPopupUtils.setVisible(spinnerView, true)
                } else {
                    XPopupUtils.setVisible(progressBar, true)
                    XPopupUtils.setVisible(spinnerView, false)
                }
            }
        })
    }

    private var title: CharSequence? = null

    /**
     * @param context
     * @param bindLayoutId layoutId 如果要显示标题，则要求必须有id为tv_title的TextView，否则无任何要求
     */
    init {
        this.bindLayoutId = bindLayoutId
        addInnerContent()
    }

    fun setTitle(title: CharSequence?): LoadingPopupView {
        this.title = title
        setup()
        return this
    }

    fun setStyle(style: Style?): LoadingPopupView {
        this.loadingStyle = style
        setup()
        return this
    }

    override fun onDismiss() {
        super.onDismiss()
        firstShow = true
    }
}
