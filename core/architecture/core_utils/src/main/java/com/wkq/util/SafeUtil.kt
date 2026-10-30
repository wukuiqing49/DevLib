package com.wkq.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import es.dmoral.toasty.Toasty

/**
 * UI 安全扩展工具。
 *
 * 适合放置依赖 Context / Fragment / View 的轻量 UI 能力，例如 Toast、
 * Activity 生命周期判断、字符串/颜色/图片/尺寸资源读取。
 */
fun Context.showToast(msg: String?) {
    if (this.isFinishing()) return
    if (!msg.isNullOrEmpty()) {
        Toasty.Config.getInstance()
            .allowQueue(false) // 可选（阻止多个吐司排队显示）
            .apply()
        Toasty.normal(this, msg).show()
    }
}

fun Context.showToast(@StringRes resId: Int) {
    showToast(string(resId))
}

fun Fragment.showToast(msg: String?) {
    if (context.isFinishing()) return
    context?.let {
        if (!msg.isNullOrEmpty()) {
            Toasty.Config.getInstance()
                .allowQueue(false) // 可选（阻止多个吐司排队显示）
                .apply()
            Toasty.normal(it, msg).show()
        }
    }
}

fun Fragment.showToast(@StringRes resId: Int) {
    context?.showToast(resId)
}

fun View.showToast(msg: String?) {
    if (context.isFinishing()) return
    context.showToast(msg)
}

fun View.showToast(@StringRes resId: Int) {
    context.showToast(resId)
}

/**
 * 获取字符串资源。
 */
fun Context.string(@StringRes resId: Int, vararg formatArgs: Any): String {
    return if (formatArgs.isEmpty()) getString(resId) else getString(resId, *formatArgs)
}

fun Fragment.string(@StringRes resId: Int, vararg formatArgs: Any): String {
    return requireContext().string(resId, *formatArgs)
}

fun View.string(@StringRes resId: Int, vararg formatArgs: Any): String {
    return context.string(resId, *formatArgs)
}

/**
 * 获取复数字符串资源。
 */
fun Context.quantityString(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String {
    return if (formatArgs.isEmpty()) {
        resources.getQuantityString(resId, quantity)
    } else {
        resources.getQuantityString(resId, quantity, *formatArgs)
    }
}

fun Fragment.quantityString(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String {
    return requireContext().quantityString(resId, quantity, *formatArgs)
}

fun View.quantityString(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String {
    return context.quantityString(resId, quantity, *formatArgs)
}

/**
 * 获取颜色资源，返回可直接设置到 View/TextView 的 color int。
 */
@ColorInt
fun Context.color(@ColorRes resId: Int): Int {
    return ContextCompat.getColor(this, resId)
}

@ColorInt
fun Fragment.color(@ColorRes resId: Int): Int {
    return requireContext().color(resId)
}

@ColorInt
fun View.color(@ColorRes resId: Int): Int {
    return context.color(resId)
}

/**
 * 获取 Drawable 资源。
 */
fun Context.drawable(@DrawableRes resId: Int): Drawable? {
    return ContextCompat.getDrawable(this, resId)
}

fun Fragment.drawable(@DrawableRes resId: Int): Drawable? {
    return requireContext().drawable(resId)
}

fun View.drawable(@DrawableRes resId: Int): Drawable? {
    return context.drawable(resId)
}

/**
 * 获取 dimens.xml 中的像素值。
 */
fun Context.dimenPx(@DimenRes resId: Int): Int {
    return resources.getDimensionPixelSize(resId)
}

fun Fragment.dimenPx(@DimenRes resId: Int): Int {
    return requireContext().dimenPx(resId)
}

fun View.dimenPx(@DimenRes resId: Int): Int {
    return context.dimenPx(resId)
}

/**
 * 从 Context 中解析 Activity；Application/Service Context 返回 null。
 */
fun Context?.findActivity(): Activity? {
    return this?.unwrapActivity()
}

/**
 * 判断 Context 关联的 Activity 是否处于 finishing/destroyed。
 *
 * 非 Activity Context（Application/Service）没有生命周期销毁语义，这里返回 false，
 * 方便 Toast 等只需要 Context 的能力继续工作。
 */
fun Context?.isFinishing(): Boolean {
    if (this == null) {
        return true
    }

    val activity = findActivity() ?: return false

    return activity.isFinishing || activity.isDestroyed
}

/**
 * 从 Context 中解析出底层的 Activity（处理 ContextWrapper 嵌套包装的情况）
 * @return 解析到的 Activity，若无法解析（非 Activity 类型）则返回 null
 */
private fun Context.unwrapActivity(): Activity? {
    var context = this
    // 循环解开 ContextWrapper 包装，直到找到 Activity 或无法继续解开
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null  // 非 Activity 类型的 Context（如 Application/Service）
}
