package com.wkq.util

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 通知权限与通知渠道工具。
 *
 * Android 8.0+ 通知是否可展示同时受 App 总开关和 channel 开关影响。
 */
object NotificationUtil {

    /** 判断 App 通知总开关是否开启；传入 channelId 时会同时检查渠道状态。 */
    fun isNotificationEnabled(context: Context, channelId: String? = null): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelEnabled = channelId?.let {
                manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
            } ?: true
            appEnabled && channelEnabled
        } else {
            appEnabled
        }
    }

    /** 创建通知渠道。Android 8.0 以下直接返回 true。 */
    fun createChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int = DEFAULT_CHANNEL_IMPORTANCE,
        description: String? = null
    ): Boolean {
        if (channelId.isBlank() || channelName.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description?.let { this.description = it }
            }
            manager.createNotificationChannel(channel)
            true
        }.getOrDefault(false)
    }

    /** 删除通知渠道。Android 8.0 以下直接返回 true。 */
    fun deleteChannel(context: Context, channelId: String): Boolean {
        if (channelId.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.deleteNotificationChannel(channelId)
            true
        }.getOrDefault(false)
    }

    /** 判断通知渠道是否存在。Android 8.0 以下没有渠道概念，返回 true。 */
    fun hasChannel(context: Context, channelId: String): Boolean {
        if (channelId.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channelId) != null
    }

    /** 打开当前 App 的通知设置页。保留 Activity 参数以兼容旧调用。 */
    fun openNotificationSettings(mContext: Activity): Boolean {
        return openNotificationSettings(mContext as Context)
    }

    /** 打开当前 App 的通知设置页。 */
    fun openNotificationSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSettingsPage(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            openAppDetailsSettings(context)
        }
    }

    /** 打开指定通知渠道设置页；渠道不存在时退回 App 通知设置页。 */
    fun openChannelSettings(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelId.isBlank()) {
            return openNotificationSettings(context)
        }
        return openSettingsPage(context, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
    }

    /** 打开当前 App 的详情设置页。 */
    fun openAppDetailsSettings(mContext: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", mContext.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            mContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 通用设置页跳转。
     *
     * @param action 系统设置 action，例如 Settings.ACTION_APP_NOTIFICATION_SETTINGS
     * @param configure 可选 lambda，用于对 Intent 做额外配置
     */
    fun openSettingsPage(
        mContext: Context,
        action: String,
        configure: (Intent.() -> Unit)? = null
    ): Boolean {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            configure?.invoke(this)
        }
        return runCatching {
            mContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private const val DEFAULT_CHANNEL_IMPORTANCE = 3
}
