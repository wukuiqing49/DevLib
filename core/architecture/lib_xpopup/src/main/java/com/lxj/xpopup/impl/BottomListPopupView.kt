package com.lxj.xpopup.impl

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BottomPopupView
import com.lxj.xpopup.interfaces.OnSelectListener
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.widget.CheckView
import com.lxj.xpopup.widget.VerticalRecyclerView

/**
 * Description: 底部的列表对话框
 * Create by dance, at 2018/12/16
 */
open class BottomListPopupView(
    context: Context,
    protected var bindLayoutId: Int,
    protected var bindItemLayoutId: Int
) : BottomPopupView(context) {
    var recyclerView: RecyclerView? = null
    var tv_title: TextView? = null
    var tv_cancel: TextView? = null
    var vv_divider: View? = null

    override val implLayoutId: Int
        get() = if (bindLayoutId == 0) R.layout._xpopup_bottom_impl_list else bindLayoutId

    override fun onCreate() {
        super.onCreate()
        recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        if (bindLayoutId != 0) {
            recyclerView!!.setLayoutManager(LinearLayoutManager(getContext()))
        }
        tv_title = findViewById<TextView?>(R.id.tv_title)
        tv_cancel = findViewById<TextView?>(R.id.tv_cancel)
        vv_divider = findViewById<View?>(R.id.vv_divider)
        if (tv_cancel != null) {
            tv_cancel!!.setOnClickListener(object : OnClickListener {
                override fun onClick(v: View?) {
                    dismiss()
                }
            })
        }

        if (tv_title != null) {
            if (TextUtils.isEmpty(title)) {
                tv_title!!.setVisibility(GONE)
                findViewById<View?>(R.id.xpopup_divider)?.visibility = GONE
            } else {
                tv_title!!.setText(title)
            }
        }

        val adapter: PopupListAdapter<String?> = object : PopupListAdapter<String?>(
            data.asList(),
            if (bindItemLayoutId == 0) R.layout._xpopup_adapter_text_match else bindItemLayoutId
        ) {
            override fun bind(holder: PopupListViewHolder, s: String?, position: Int) {
                holder.setText(R.id.tv_text, s.orEmpty())
                val imageView = holder.getViewOrNull<ImageView>(R.id.iv_image)
                if (iconIds != null && iconIds!!.size > position) {
                    if (imageView != null) {
                        imageView.setVisibility(VISIBLE)
                        imageView.setBackgroundResource(iconIds!![position])
                    }
                } else {
                    if (imageView != null) imageView.setVisibility(GONE)
                }

                if (bindItemLayoutId == 0) {
                    if (popupInfo.isDarkTheme) {
                        holder.getView<TextView>(R.id.tv_text)
                            .setTextColor(getResources().getColor(R.color._xpopup_white_color))
                    } else {
                        holder.getView<TextView>(R.id.tv_text)
                            .setTextColor(getResources().getColor(R.color._xpopup_dark_color))
                    }
                }

                // 对勾View
                if (checkedPosition != -1) {
                    if (holder.getViewOrNull<View>(R.id.check_view) != null) {
                        holder.getView<View>(R.id.check_view)
                            .setVisibility(if (position == checkedPosition) VISIBLE else GONE)
                        holder.getView<CheckView>(R.id.check_view)
                            .setColor(XPopup.primaryColor)
                    }
                    holder.getView<TextView>(R.id.tv_text).setTextColor(
                        if (position == checkedPosition) XPopup.primaryColor else getResources().getColor(
                            R.color._xpopup_title_color
                        )
                    )
                    holder.getView<TextView>(R.id.tv_text)
                        .setGravity(if (XPopupUtils.isLayoutRtl(getContext())) Gravity.END else Gravity.START)
                } else {
                    if (holder.getViewOrNull<View>(R.id.check_view) != null) holder.getView<View>(
                        R.id.check_view
                    ).setVisibility(
                        GONE
                    )
                    holder.getView<TextView>(R.id.tv_text).setGravity(Gravity.CENTER)
                }
            }
        }
        adapter.setOnItemClickListener(object : PopupListClickListener() {
            override fun onItemClick(view: View, holder: RecyclerView.ViewHolder, position: Int) {
                if (selectListener != null) {
                    selectListener!!.onSelect(position, adapter.data.get(position))
                }
                if (checkedPosition != -1) {
                    checkedPosition = position
                    adapter.notifyDataSetChanged()
                }
                if (popupInfo.autoDismiss) dismiss()
            }
        })
        recyclerView!!.setAdapter(adapter)
        applyTheme()
    }

    var title: CharSequence? = null
    var data: Array<String> = emptyArray()
    var iconIds: IntArray? = null

    fun setStringData(
        title: CharSequence?,
        data: Array<String>,
        iconIds: IntArray?
    ): BottomListPopupView {
        this.title = title
        this.data = data
        this.iconIds = iconIds
        return this
    }

    private var selectListener: OnSelectListener? = null

    fun setOnSelectListener(selectListener: OnSelectListener?): BottomListPopupView {
        this.selectListener = selectListener
        return this
    }

    var checkedPosition: Int = -1

    /**
     *
     * @param context
     * @param bindLayoutId layoutId 要求layoutId中必须有一个id为recyclerView的RecyclerView，如果你需要显示标题，则必须有一个id为tv_title的TextView
     * @param bindItemLayoutId itemLayoutId 条目的布局id，要求布局中有id为iv_image的ImageView（非必须），和id为tv_text的TextView
     */
    init {
        addInnerContent()
    }

    /**
     * 设置默认选中的位置
     *
     * @param position
     * @return
     */
    fun setCheckedPosition(position: Int): BottomListPopupView {
        this.checkedPosition = position
        return this
    }

    protected open fun applyTheme() {
        if (bindLayoutId == 0) {
            if (popupInfo.isDarkTheme) {
                applyDarkTheme()
            } else {
                applyLightTheme()
            }
        }
    }

    override fun applyDarkTheme() {
        super.applyDarkTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(true)
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        if (tv_cancel != null) tv_cancel!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        findViewById<View?>(R.id.xpopup_divider).setBackgroundColor(
            getResources().getColor(R.color._xpopup_list_dark_divider)
        )
        if (vv_divider != null) vv_divider!!.setBackgroundColor(Color.parseColor("#1B1B1B"))
        popupImplView.setBackground(
            XPopupUtils.createDrawable(
                getResources().getColor(R.color._xpopup_dark_color),
                popupInfo.borderRadius, popupInfo.borderRadius, 0f, 0f
            )
        )
    }

    override fun applyLightTheme() {
        super.applyLightTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(false)
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_dark_color))
        if (tv_cancel != null) tv_cancel!!.setTextColor(getResources().getColor(R.color._xpopup_dark_color))
        findViewById<View?>(R.id.xpopup_divider).setBackgroundColor(getResources().getColor(R.color._xpopup_list_divider))
        if (vv_divider != null) vv_divider!!.setBackgroundColor(getResources().getColor(R.color._xpopup_white_color))
        popupImplView.setBackground(
            XPopupUtils.createDrawable(
                getResources().getColor(R.color._xpopup_light_color),
                popupInfo.borderRadius, popupInfo.borderRadius, 0f, 0f
            )
        )
    }
}
