package com.lxj.xpopup.interfaces

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import com.lxj.xpopup.core.ImageViewerPopupView
import com.lxj.xpopup.photoview.PhotoView
import java.io.File

interface XPopupImageLoader {
    fun loadSnapshot(uri: Any, snapshot: PhotoView, srcView: ImageView?)


    fun loadImage(
        position: Int,
        uri: Any,
        popupView: ImageViewerPopupView,
        snapshot: PhotoView,
        progressBar: ProgressBar
    ): View?

    /**
     * 获取图片对应的文件
     * @param context
     * @param uri
     * @return
     */
    fun getImageFile(context: Context, uri: Any): File?
}
