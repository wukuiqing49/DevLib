package com.lxj.xpopup.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lxj.xpopup.core.BasePopupView

/**
 * Description:
 * Create by dance, at 2018/12/17
 */
class KeyboardUtils private constructor() {
    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }

    interface OnSoftInputChangedListener {
        fun onSoftInputChanged(height: Int)
    }

    companion object {
        var sDecorViewInvisibleHeightPre: Int = 0
        private fun getDecorViewInvisibleHeight(window: Window): Int {
            val insets = ViewCompat.getRootWindowInsets(window.getDecorView())
            if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())) return 0
            return insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }

        /**
         * Register soft input changed listener.
         *
         * @param window The activity.
         * @param listener The soft input changed listener.
         */
        fun registerSoftInputChangedListener(
            window: Window,
            popupView: BasePopupView?,
            listener: OnSoftInputChangedListener
        ) {
            if (popupView == null) return
            val decorViewInvisibleHeightPre = intArrayOf(getDecorViewInvisibleHeight(window))
            ViewCompat.setOnApplyWindowInsetsListener(
                popupView,
                OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                    val currentInsets = requireNotNull(insets)
                    popupView.applySystemWindowInsets(currentInsets)
                    val height = if (currentInsets.isVisible(WindowInsetsCompat.Type.ime()))
                        currentInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    else
                        0
                    if (decorViewInvisibleHeightPre[0] != height) {
                        listener.onSoftInputChanged(height)
                        decorViewInvisibleHeightPre[0] = height
                    }
                    currentInsets
                })
            ViewCompat.requestApplyInsets(popupView)
        }

        fun removeLayoutChangeListener(window: Window?, popupView: BasePopupView?) {
            if (popupView == null) return
            ViewCompat.setOnApplyWindowInsetsListener(popupView, null)
        }

        fun showSoftInput(view: View?) {
            if (view == null) return
            val imm = view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            if (imm == null) return
            view.setFocusable(true)
            view.setFocusableInTouchMode(true)
            view.requestFocus()
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            val controller = ViewCompat.getWindowInsetsController(view)
            if (controller != null) controller.show(WindowInsetsCompat.Type.ime())
        }

        fun toggleSoftInput(context: Context?) {
            if (context == null) return
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            if (imm == null) return
            imm.toggleSoftInput(0, 0)
        }

        fun hideSoftInput(view: View) {
            val imm = view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0)
            val controller = ViewCompat.getWindowInsetsController(view)
            if (controller != null) controller.hide(WindowInsetsCompat.Type.ime())
        }

        fun hideSoftInput(window: Window) {
            var view = window.getCurrentFocus()
            if (view == null) {
                val decorView = window.getDecorView()
                val focusView = decorView.findViewWithTag<View?>("keyboardTagView")
                if (focusView == null) {
                    view = EditText(window.getContext())
                    view.setTag("keyboardTagView")
                    (decorView as ViewGroup).addView(view, 0, 0)
                } else {
                    view = focusView
                }
                view.requestFocus()
            }
            hideSoftInput(view)
        }
    }
}
