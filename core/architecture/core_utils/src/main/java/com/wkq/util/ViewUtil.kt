package com.wkq.util

import android.view.View

/**
 * View 可见性与点击防抖工具。
 */
object ViewUtil {

    /** 批量设置为 VISIBLE。 */
    fun visible(vararg views: View?) {
        views.forEach { it?.visibility = View.VISIBLE }
    }

    /** 批量设置为 INVISIBLE，占位但不可见。 */
    fun invisible(vararg views: View?) {
        views.forEach { it?.visibility = View.INVISIBLE }
    }

    /** 批量设置为 GONE，不占位。 */
    fun gone(vararg views: View?) {
        views.forEach { it?.visibility = View.GONE }
    }

    /** 批量设置启用状态。 */
    fun enabled(enable: Boolean, vararg views: View?) {
        views.forEach { it?.isEnabled = enable }
    }

    /** 批量设置选中状态。 */
    fun selected(selected: Boolean, vararg views: View?) {
        views.forEach { it?.isSelected = selected }
    }

    /** 设置点击防抖，避免短时间重复触发。 */
    fun setDebouncedClickListener(
        view: View,
        intervalMs: Long = 500L,
        onClick: (View) -> Unit
    ) {
        var lastClickTime = 0L
        view.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= intervalMs) {
                lastClickTime = now
                onClick(it)
            }
        }
    }
}

/** 设置 View 为 VISIBLE。 */
fun View.visible() {
    visibility = View.VISIBLE
}

/** 设置 View 为 INVISIBLE。 */
fun View.invisible() {
    visibility = View.INVISIBLE
}

/** 设置 View 为 GONE。 */
fun View.gone() {
    visibility = View.GONE
}

/** 是否可见。 */
fun View.isVisible(): Boolean = visibility == View.VISIBLE

/** 是否占位隐藏。 */
fun View.isInvisible(): Boolean = visibility == View.INVISIBLE

/** 是否不占位隐藏。 */
fun View.isGone(): Boolean = visibility == View.GONE

/** 设置点击防抖，避免短时间重复触发。 */
fun View.setDebouncedClickListener(intervalMs: Long = 500L, onClick: (View) -> Unit) {
    ViewUtil.setDebouncedClickListener(this, intervalMs, onClick)
}
