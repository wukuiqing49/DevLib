package com.lxj.xpopup.interfaces

import com.lxj.xpopup.core.ImageViewerPopupView

/**
 * Description:
 * Create by dance, at 2019/1/29
 */
fun interface OnSrcViewUpdateListener {
    fun onSrcViewUpdate(popupView: ImageViewerPopupView, position: Int)
}
