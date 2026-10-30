package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lxj.xpopup.R
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description:
 * Create by dance, at 2018/12/12
 */
class VerticalRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {
    init {
        setLayoutManager(LinearLayoutManager(getContext()))
    }

    fun setupDivider(isDark: Boolean) {
        val decoration = SmartDivider(getContext(), SmartDivider.VERTICAL)
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(getResources().getColor(if (isDark) R.color._xpopup_list_dark_divider else R.color._xpopup_list_divider))
        drawable.setSize(10, XPopupUtils.dp2px(getContext(), .5f))
        decoration.drawable = drawable
        addItemDecoration(decoration)
    }
}
