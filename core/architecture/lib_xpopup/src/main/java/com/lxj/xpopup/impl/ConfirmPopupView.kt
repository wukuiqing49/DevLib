package com.lxj.xpopup.impl

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.CenterPopupView
import com.lxj.xpopup.interfaces.OnCancelListener
import com.lxj.xpopup.interfaces.OnConfirmListener
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 确定和取消的对话框
 * Create by dance, at 2018/12/16
 */
open class ConfirmPopupView(context: Context, bindLayoutId: Int) : CenterPopupView(context),
    View.OnClickListener {
    var cancelListener: OnCancelListener? = null
    var confirmListener: OnConfirmListener? = null
    var tv_title: TextView? = null
    var tv_content: TextView? = null
    var tv_cancel: TextView? = null
    var tv_confirm: TextView? = null
    var title: CharSequence? = null
    var content: CharSequence? = null
    var hint: CharSequence? = null
    var cancelText: CharSequence? = null
    var confirmText: CharSequence? = null
    var et_input: EditText? = null
    var divider1: View? = null
    var divider2: View? = null
    var isHideCancel: Boolean = false

    /**
     *
     * @param context
     * @param bindLayoutId layoutId 要求布局中必须包含的TextView以及id有：tv_title，tv_content，tv_cancel，tv_confirm
     */
    init {
        this.bindLayoutId = bindLayoutId
        addInnerContent()
    }

    override val implLayoutId: Int
        get() = if (bindLayoutId != 0) bindLayoutId else R.layout._xpopup_center_impl_confirm

    override fun onCreate() {
        super.onCreate()
        tv_title = findViewById<TextView>(R.id.tv_title)
        tv_content = findViewById<TextView>(R.id.tv_content)
        tv_cancel = findViewById<TextView>(R.id.tv_cancel)
        tv_confirm = findViewById<TextView>(R.id.tv_confirm)
        tv_content!!.setMovementMethod(LinkMovementMethod.getInstance())
        et_input = findViewById<EditText?>(R.id.et_input)
        divider1 = findViewById<View?>(R.id.xpopup_divider1)
        divider2 = findViewById<View?>(R.id.xpopup_divider2)

        tv_cancel!!.setOnClickListener(this)
        tv_confirm!!.setOnClickListener(this)

        if (!TextUtils.isEmpty(title)) {
            tv_title!!.setText(title)
        } else {
            XPopupUtils.setVisible(tv_title, false)
        }

        if (!TextUtils.isEmpty(content)) {
            tv_content!!.setText(content)
        } else {
            XPopupUtils.setVisible(tv_content, false)
        }
        if (!TextUtils.isEmpty(cancelText)) {
            tv_cancel!!.setText(cancelText)
        }
        if (!TextUtils.isEmpty(confirmText)) {
            tv_confirm!!.setText(confirmText)
        }
        if (isHideCancel) {
            XPopupUtils.setVisible(tv_cancel, false)
            XPopupUtils.setVisible(divider2, false)
        }
        applyTheme()
    }

    override fun applyLightTheme() {
        super.applyLightTheme()
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_content_color))
        tv_content!!.setTextColor(getResources().getColor(R.color._xpopup_content_color))
        tv_cancel!!.setTextColor(Color.parseColor("#666666"))
        tv_confirm!!.setTextColor(XPopup.primaryColor)
        if (divider1 != null) divider1!!.setBackgroundColor(getResources().getColor(R.color._xpopup_list_divider))
        if (divider2 != null) divider2!!.setBackgroundColor(getResources().getColor(R.color._xpopup_list_divider))
    }

    val titleTextView: TextView?
        get() = findViewById<TextView?>(R.id.tv_title)

    val contentTextView: TextView?
        get() = findViewById<TextView?>(R.id.tv_content)

    val cancelTextView: TextView?
        get() = findViewById<TextView?>(R.id.tv_cancel)

    val confirmTextView: TextView?
        get() = findViewById<TextView?>(R.id.tv_confirm)

    override fun applyDarkTheme() {
        super.applyDarkTheme()
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        tv_content!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        tv_cancel!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        tv_confirm!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        if (divider1 != null) divider1!!.setBackgroundColor(getResources().getColor(R.color._xpopup_list_dark_divider))
        if (divider2 != null) divider2!!.setBackgroundColor(getResources().getColor(R.color._xpopup_list_dark_divider))
    }

    fun setListener(
        confirmListener: OnConfirmListener?,
        cancelListener: OnCancelListener?
    ): ConfirmPopupView {
        this.cancelListener = cancelListener
        this.confirmListener = confirmListener
        return this
    }

    fun setTitleContent(
        title: CharSequence?,
        content: CharSequence?,
        hint: CharSequence?
    ): ConfirmPopupView {
        this.title = title
        this.content = content
        this.hint = hint
        return this
    }

    fun setCancelText(cancelText: CharSequence?): ConfirmPopupView {
        this.cancelText = cancelText
        return this
    }

    fun setConfirmText(confirmText: CharSequence?): ConfirmPopupView {
        this.confirmText = confirmText
        return this
    }

    override fun onClick(v: View?) {
        if (v === tv_cancel) {
            if (cancelListener != null) cancelListener!!.onCancel()
            dismiss()
        } else if (v === tv_confirm) {
            if (confirmListener != null) confirmListener!!.onConfirm()
            if (popupInfo.autoDismiss) dismiss()
        }
    }

    override val maxHeight: Int
        get() = if (popupInfo.maxHeight == 0) (XPopupUtils.getAppHeight(getContext()) * 0.8).toInt() else popupInfo.maxHeight
}
