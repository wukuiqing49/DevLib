package com.wkq.guide

import kotlin.math.max
import kotlin.math.min

/** 纯几何边界，统一使用覆盖层局部坐标，便于独立验证窗口定位。 */
internal data class GuideBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = max(0f, right - left)
    val height get() = max(0f, bottom - top)
}

/** 布局算法只接收已扣除系统栏、刘海及键盘的安全范围，不再次叠加 Insets。 */
internal object GuidePlacement {
    /** 优先目标下方、其次上方；空间不足使用安全范围底部的滚动说明面板。 */
    fun place(safe: GuideBounds, target: GuideBounds, width: Float, height: Float, gap: Float): GuideBounds {
        val w = min(width, safe.width)
        val h = min(height, safe.height)
        val left = ((target.left + target.right - w) / 2).coerceIn(safe.left, max(safe.left, safe.right - w))
        val top = when {
            target.bottom + gap + h <= safe.bottom -> max(safe.top, target.bottom + gap)
            target.top - gap - h >= safe.top -> target.top - gap - h
            else -> max(safe.top, safe.bottom - h)
        }
        return GuideBounds(left, top, left + w, top + h)
    }
}
