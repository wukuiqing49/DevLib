package com.lxj.xpopup

import android.content.Context
import android.view.View

/** Compiles the idiomatic Kotlin entry points used by Kotlin consumers. */
internal fun verifyKotlinApiCompatibility(context: Context, anchor: View) {
    XPopup.primaryColor = 0
    XPopup.animationDuration = 300
    XPopup.setIsLightStatusBar(true)
    XPopup.Builder(context)
        .atView(anchor)
        .asConfirm("Title", "Message") { }
        .show()
    XPopup.Builder(context)
        .asBottomList("Title", arrayOf("One")) { _, _ -> }
        .show()
    XPopup.Builder(context).asLoading().show()
}
