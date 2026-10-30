package com.lxj.xpopup.impl

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.CenterPopupView
import com.lxj.xpopup.interfaces.OnSelectListener
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.widget.CheckView
import com.lxj.xpopup.widget.VerticalRecyclerView

/**
 * Description: 在中间的列表对话框
 * Create by dance, at 2018/12/16
 */
open class CenterListPopupView(context: Context, bindLayoutId: Int, bindItemLayoutId: Int) :
    CenterPopupView(context) {
    var recyclerView: RecyclerView? = null
    var tv_title: TextView? = null

    override val implLayoutId: Int
        get() = if (bindLayoutId == 0) R.layout._xpopup_center_impl_list else bindLayoutId

    override fun onCreate() {
        super.onCreate()
        recyclerView = findViewById<RecyclerView?>(R.id.recyclerView)
        if (bindLayoutId != 0) {
            recyclerView!!.setLayoutManager(LinearLayoutManager(getContext()))
        }
        tv_title = findViewById<TextView?>(R.id.tv_title)

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
                    ).setVisibility(GONE)
                    //如果没有选择，则文字居中
                    holder.getView<TextView>(R.id.tv_text).setGravity(Gravity.CENTER)
                }
            }
        }
        adapter.setOnItemClickListener(object : PopupListClickListener() {
            override fun onItemClick(view: View, holder: RecyclerView.ViewHolder, position: Int) {
                if (selectListener != null) {
                    if (position >= 0 && position < adapter.data.size) selectListener!!.onSelect(
                        position,
                        adapter.data.get(position)
                    )
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

    override fun applyDarkTheme() {
        super.applyDarkTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(true)
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_white_color))
        findViewById<View?>(R.id.xpopup_divider).setBackgroundColor(getResources().getColor(R.color._xpopup_list_dark_divider))
    }

    override fun applyLightTheme() {
        super.applyLightTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(false)
        tv_title!!.setTextColor(getResources().getColor(R.color._xpopup_dark_color))
        findViewById<View?>(R.id.xpopup_divider).setBackgroundColor(getResources().getColor(R.color._xpopup_list_divider))
    }

    var title: CharSequence? = null
    var data: Array<String> = emptyArray()
    var iconIds: IntArray? = null

    fun setStringData(
        title: CharSequence?,
        data: Array<String>,
        iconIds: IntArray?
    ): CenterListPopupView {
        this.title = title
        this.data = data
        this.iconIds = iconIds
        return this
    }

    private var selectListener: OnSelectListener? = null

    fun setOnSelectListener(selectListener: OnSelectListener?): CenterListPopupView {
        this.selectListener = selectListener
        return this
    }

    var checkedPosition: Int = -1

    /**
     *
     * @param context
     * @param bindLayoutId  要求layoutId中必须有一个id为recyclerView的RecyclerView，如果你需要显示标题，则必须有一个id为tv_title的TextView
     * @param bindItemLayoutId  条目的布局id，要求布局中有id为iv_image的ImageView（非必须），和id为tv_text的TextView
     */
    init {
        this.bindLayoutId = bindLayoutId
        this.bindItemLayoutId = bindItemLayoutId
        addInnerContent()
    }

    /**
     * 设置默认选中的位置
     *
     * @param position
     * @return
     */
    fun setCheckedPosition(position: Int): CenterListPopupView {
        this.checkedPosition = position
        if (recyclerView != null && recyclerView!!.getAdapter() != null) {
            recyclerView!!.getAdapter()!!.notifyDataSetChanged()
        }
        return this
    }

    override val maxWidth: Int
        get() = if (popupInfo.maxWidth == 0) super.maxWidth else popupInfo.maxWidth
}
