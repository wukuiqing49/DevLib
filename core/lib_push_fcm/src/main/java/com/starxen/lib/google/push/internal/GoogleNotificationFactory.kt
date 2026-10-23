package com.starxen.lib.google.push.internal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starxen.lib.google.pushlib.R
import com.starxen.lib.google.push.GooglePushConfig
import com.starxen.lib.google.push.GooglePushEntryActivity
import com.starxen.lib.google.push.GooglePushError
import com.starxen.lib.google.push.GooglePushErrorCode
import com.starxen.lib.google.push.GooglePushMessage

internal sealed class NotificationCreationResult {
    object Success : NotificationCreationResult()
    data class Failure(val error: GooglePushError) : NotificationCreationResult()
}

internal class GoogleNotificationFactory(
    private val codec: GooglePushMessageCodec = GooglePushMessageCodec()
) {
    fun show(
        context: Context,
        message: GooglePushMessage,
        config: GooglePushConfig
    ): NotificationCreationResult {
        if (!config.notificationsEnabled) return NotificationCreationResult.Success
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationCreationResult.Failure(
                GooglePushError(
                    GooglePushErrorCode.NOTIFICATION_PERMISSION_DENIED,
                    "Notification permission has not been granted"
                )
            )
        }

        return try {
            createChannel(context, config)
            val entryIntent = Intent(context, GooglePushEntryActivity::class.java)
                .putExtra(GooglePushEntryActivity.EXTRA_PUSH_MESSAGE, codec.encode(message))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(
                context,
                message.messageId.hashCode(),
                entryIntent,
                flags
            )

            val title = message.title.ifBlank {
                context.getString(R.string.lib_google_default_notification_title)
            }
            val smallIcon = config.smallIconResId.takeIf { it != 0 }
                ?: context.applicationInfo.icon
            val builder = NotificationCompat.Builder(context, normalizedChannelId(config))
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(message.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            config.notificationColor?.let(builder::setColor)
            NotificationManagerCompat.from(context).notify(message.messageId.hashCode(), builder.build())
            NotificationCreationResult.Success
        } catch (error: Exception) {
            NotificationCreationResult.Failure(
                GooglePushError(
                    GooglePushErrorCode.NOTIFICATION_FAILED,
                    error.message ?: "Unable to create notification"
                )
            )
        }
    }

    private fun createChannel(context: Context, config: GooglePushConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelName = config.channelName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.lib_google_default_channel_name)
        val channel = NotificationChannel(
            normalizedChannelId(config),
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        config.channelDescription?.takeIf { it.isNotBlank() }?.let {
            channel.description = it
        }
        manager.createNotificationChannel(channel)
    }

    private fun normalizedChannelId(config: GooglePushConfig): String {
        return config.channelId.ifBlank { GooglePushConfig.DEFAULT_CHANNEL_ID }
    }
}
