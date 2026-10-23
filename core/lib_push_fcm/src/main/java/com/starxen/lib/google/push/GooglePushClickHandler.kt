package com.starxen.lib.google.push

import android.content.Context

/**
 * Lets the host application route a notification click to one of its own screens.
 * Return true only after the click has been handled; return false to use pending-click fallback.
 */
fun interface GooglePushClickHandler {
    fun onNotificationClick(context: Context, message: GooglePushMessage): Boolean
}
