package com.lxj.xpopup.impl

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lxj.xpopup.R
import com.lxj.xpopup.core.AttachPopupView
import com.lxj.xpopup.interfaces.OnSelectListener
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.widget.VerticalRecyclerView

/**
 * Description: Attach类型的列表弹窗
 * Create by dance, at 2018/12/12
 */
open class AttachListPopupView(
    context: Context,
    protected var bindLayoutId: Int,
    protected var bindItemLayoutId: Int
) : AttachPopupView(context) {
    var recyclerView: RecyclerView? = null
    protected var contentGravity: Int = Gravity.CENTER

    override val implLayoutId: Int
        get() = if (bindLayoutId == 0) R.layout._xpopup_attach_impl_list else bindLayoutId

    override fun onCreate() {
        super.onCreate()
        recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        if (bindLayoutId != 0) {
            recyclerView!!.setLayoutManager(LinearLayoutManager(getContext()))
        }
        val adapter: PopupListAdapter<String?> = object : PopupListAdapter<String?>(
            data.asList(),
            if (bindItemLayoutId == 0) R.layout._xpopup_adapter_text else bindItemLayoutId
        ) {
            override fun bind(holder: PopupListViewHolder, s: String?, position: Int) {
                holder.setText(R.id.tv_text, s.orEmpty())
                val imageView = holder.getViewOrNull<ImageView>(R.id.iv_image)
                if (iconIds != null && iconIds!!.size > position) {
                    if (imageView != null) {
                        XPopupUtils.setVisible(imageView, true)
                        imageView.setBackgroundResource(iconIds!![position])
                    }
                } else {
                    XPopupUtils.setVisible(imageView, false)
                }

                if (bindItemLayoutId == 0) {
                    if (popupInfo.isDarkTheme) {
                        holder.getView<TextView>(R.id.tv_text)
                            .setTextColor(getResources().getColor(R.color._xpopup_white_color))
                    } else {
                        holder.getView<TextView>(R.id.tv_text)
                            .setTextColor(getResources().getColor(R.color._xpopup_dark_color))
                    }
                    val linearLayout = holder.getView<LinearLayout>(R.id._ll_temp)
                    linearLayout.setGravity(contentGravity)
                }
            }
        }
        adapter.setOnItemClickListener(object : PopupListClickListener() {
            override fun onItemClick(view: View, holder: RecyclerView.ViewHolder, position: Int) {
                if (selectListener != null) {
                    selectListener!!.onSelect(position, adapter.data.get(position))
                }
                if (popupInfo.autoDismiss) dismiss()
            }
        })
        recyclerView!!.setAdapter(adapter)
        applyTheme()
    }

    protected open fun applyTheme() {
        if (bindLayoutId == 0) {
            if (popupInfo.isDarkTheme) {
                applyDarkTheme()
            } else {
                applyLightTheme()
            }
            attachPopupContainer.setBackground(
                XPopupUtils.createDrawable(
                    getResources().getColor(
                        if (popupInfo.isDarkTheme)
                            R.color._xpopup_dark_color
                        else
                            R.color._xpopup_light_color
                    ), popupInfo.borderRadius
                )
            )
        }
    }

    override fun applyDarkTheme() {
        super.applyDarkTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(true)
    }

    override fun applyLightTheme() {
        super.applyLightTheme()
        (recyclerView as VerticalRecyclerView).setupDivider(false)
    }

    var data: Array<String> = emptyArray()
    var iconIds: IntArray? = null

    fun setStringData(data: Array<String>, iconIds: IntArray?): AttachListPopupView {
        this.data = data
        this.iconIds = iconIds
        return this
    }

    fun setContentGravity(gravity: Int): AttachListPopupView {
        this.contentGravity = gravity
        return this
    }

    private var selectListener: OnSelectListener? = null

    /**
     *
     * @param context
     * @param bindLayoutId layoutId 要求layoutId中必须有一个id为recyclerView的RecyclerView
     * @param bindItemLayoutId itemLayoutId 条目的布局id，要求布局中有id为iv_image的ImageView（非必须），和id为tv_text的TextView
     */
    init {
        addInnerContent()
    }

    fun setOnSelectListener(selectListener: OnSelectListener?): AttachListPopupView {
        this.selectListener = selectListener
        return this
    }
}
