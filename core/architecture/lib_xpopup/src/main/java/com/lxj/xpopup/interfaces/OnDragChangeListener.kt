package com.lxj.xpopup.interfaces

interface OnDragChangeListener {
    fun onRelease()
    fun onDragChange(dy: Int, scale: Float, fraction: Float)
}
