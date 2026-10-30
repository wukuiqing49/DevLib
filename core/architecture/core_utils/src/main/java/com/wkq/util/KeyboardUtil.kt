package com.wkq.util

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * 软键盘显示、隐藏与可见性判断工具。
 */
object KeyboardUtil {

    /** 请求焦点并显示软键盘。 */
    fun showKeyboard(view: View): Boolean {
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) == true
    }

    /** 隐藏指定 View 所在窗口的软键盘。 */
    fun hideKeyboard(view: View): Boolean {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.hideSoftInputFromWindow(view.windowToken, 0) == true
    }

    /** 隐藏 Activity 当前焦点 View 的软键盘。 */
    fun hideKeyboard(activity: Activity): Boolean {
        val view = activity.currentFocus ?: activity.window.decorView
        return hideKeyboard(view)
    }

    /** 切换软键盘显示状态。 */
    fun toggleKeyboard(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        @Suppress("DEPRECATION")
        imm?.toggleSoftInput(0, 0)
    }

    /** 通过可见窗口高度粗略判断软键盘是否显示。 */
    fun isKeyboardVisible(activity: Activity): Boolean {
        val root = activity.window.decorView
        val rect = Rect()
        root.getWindowVisibleDisplayFrame(rect)
        val height = root.rootView.height
        if (height <= 0) return false
        return height - rect.bottom > height * 0.15f
    }
}
