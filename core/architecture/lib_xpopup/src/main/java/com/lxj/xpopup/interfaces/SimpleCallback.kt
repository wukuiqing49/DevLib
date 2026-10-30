package com.lxj.xpopup.interfaces

import com.lxj.xpopup.core.BasePopupView

/**
 * Description:
 * Create by dance, at 2019/6/13
 */
open class SimpleCallback : XPopupCallback {
    override fun onCreated(popupView: BasePopupView?) {
    }

    override fun beforeShow(popupView: BasePopupView?) {
    }

    override fun onShow(popupView: BasePopupView?) {
    }

    override fun onDismiss(popupView: BasePopupView?) {
    }

    override fun beforeDismiss(popupView: BasePopupView?) {
    }

    override fun onBackPressed(popupView: BasePopupView?): Boolean {
        return false
    }

    override fun onKeyBoardStateChanged(popupView: BasePopupView?, height: Int) {}

    override fun onDrag(popupView: BasePopupView?, value: Int, percent: Float, upOrLeft: Boolean) {
    }

    override fun onClickOutside(popupView: BasePopupView?) {
    }
}
