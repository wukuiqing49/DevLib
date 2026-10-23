package com.starxen.lib.google.push

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.starxen.lib.google.push.internal.GooglePushMessageCodec

class GooglePushEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = GooglePushMessageCodec().decode(
            intent?.getStringExtra(EXTRA_PUSH_MESSAGE)
        )
        if (message != null) {
            val handled = GooglePushManager.handleNotificationClick(this, message)
            if (!handled) {
                launchHostApplication()
            }
        }
        finish()
    }

    private fun launchHostApplication() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        launchIntent.putExtra(EXTRA_HAS_PENDING_PUSH, true)
        startActivity(launchIntent)
    }

    companion object {
        internal const val EXTRA_PUSH_MESSAGE = "lib_google_push_message"
        const val EXTRA_HAS_PENDING_PUSH = "lib_google_has_pending_push"
    }
}
