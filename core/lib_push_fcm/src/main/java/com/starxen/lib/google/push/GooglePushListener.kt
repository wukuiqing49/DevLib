package com.starxen.lib.google.push

interface GooglePushListener {
    fun onTokenChanged(token: String)
    fun onMessageReceived(message: GooglePushMessage)
    fun onNotificationClicked(message: GooglePushMessage)
    fun onError(error: GooglePushError)
}

open class GooglePushListenerAdapter : GooglePushListener {
    override fun onTokenChanged(token: String) = Unit
    override fun onMessageReceived(message: GooglePushMessage) = Unit
    override fun onNotificationClicked(message: GooglePushMessage) = Unit
    override fun onError(error: GooglePushError) = Unit
}
