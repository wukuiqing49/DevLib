package com.lxj.xpopup.impl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

internal abstract class PopupListAdapter<T>(
    val data: List<T>,
    @param:LayoutRes private val itemLayoutId: Int
) : RecyclerView.Adapter<PopupListViewHolder>() {
    private var itemClickListener: PopupListClickListener? = null

    abstract fun bind(holder: PopupListViewHolder, s: T, position: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PopupListViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(itemLayoutId, parent, false)
        return PopupListViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: PopupListViewHolder, position: Int) {
        bind(holder, data[position], position)
        holder.itemView.setOnClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION) {
                itemClickListener?.onItemClick(holder.itemView, holder, clickedPosition)
            }
        }
    }

    override fun getItemCount(): Int = data.size

    fun setOnItemClickListener(listener: PopupListClickListener?) {
        itemClickListener = listener
    }
}

internal class PopupListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun setText(@IdRes viewId: Int, text: CharSequence?) {
        getView<TextView>(viewId).text = text
    }

    fun <V : View> getView(@IdRes viewId: Int): V {
        return requireNotNull(itemView.findViewById(viewId)) {
            "Required item view $viewId was not found"
        }
    }

    fun <V : View> getViewOrNull(@IdRes viewId: Int): V? = itemView.findViewById(viewId)
}

internal abstract class PopupListClickListener {
    abstract fun onItemClick(view: View, holder: RecyclerView.ViewHolder, position: Int)
}
