package com.lxj.xpopup.impl

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.interfaces.OnInputConfirmListener
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 带输入框，确定和取消的对话框
 * Create by dance, at 2018/12/16
 */
open class InputConfirmPopupView
/**
 * @param context
 * @param bindLayoutId 在Confirm弹窗基础上需要增加一个id为et_input的EditText
 */
    (context: Context, bindLayoutId: Int) : ConfirmPopupView(context, bindLayoutId),
    View.OnClickListener {
    var inputContent: CharSequence? = null

    protected override fun onCreate() {
        super.onCreate()
        XPopupUtils.setVisible(et_input, true)
        if (!TextUtils.isEmpty(hint)) {
            et_input!!.setHint(hint)
        }
        if (!TextUtils.isEmpty(inputContent)) {
            et_input!!.setText(inputContent)
            et_input!!.setSelection(inputContent!!.length)
        }

        XPopupUtils.setCursorDrawableColor(et_input, XPopup.primaryColor)
        if (bindLayoutId == 0) {
            et_input!!.post(Runnable {
                if (et_input!!.getMeasuredWidth() > 0) {
                    val defaultDrawable = XPopupUtils.createBitmapDrawable(
                        getContext(),
                        et_input!!.getMeasuredWidth(),
                        Color.parseColor("#888888")
                    )
                    val focusDrawable = XPopupUtils.createBitmapDrawable(
                        getContext(),
                        et_input!!.getMeasuredWidth(),
                        XPopup.primaryColor
                    )
                    et_input!!.setBackgroundDrawable(
                        XPopupUtils.createSelector(
                            defaultDrawable,
                            focusDrawable
                        )
                    )
                }
            })
        }
    }

    val editText: EditText?
        get() = et_input

    protected override fun applyLightTheme() {
        super.applyLightTheme()
        et_input!!.setHintTextColor(Color.parseColor("#888888"))
        et_input!!.setTextColor(Color.parseColor("#333333"))
    }

    protected override fun applyDarkTheme() {
        super.applyDarkTheme()
        et_input!!.setHintTextColor(Color.parseColor("#888888"))
        et_input!!.setTextColor(Color.parseColor("#dddddd"))
    }

    var inputConfirmListener: OnInputConfirmListener? = null

    fun setListener(
        inputConfirmListener: OnInputConfirmListener?,
        cancelListener: com.lxj.xpopup.interfaces.OnCancelListener?
    ) {
        this.cancelListener = cancelListener
        this.inputConfirmListener = inputConfirmListener
    }

    override fun onClick(v: View?) {
        if (v === tv_cancel) {
            if (cancelListener != null) cancelListener!!.onCancel()
            dismiss()
        } else if (v === tv_confirm) {
            if (inputConfirmListener != null) inputConfirmListener!!.onConfirm(
                et_input!!.getText().toString().trim { it <= ' ' })
            if (popupInfo.autoDismiss) dismiss()
        }
    }
}
