package com.starxen.lib.google.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GoogleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        GooglePushManager.handleNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        GooglePushManager.handleRemoteMessage(applicationContext, message)
    }
}
