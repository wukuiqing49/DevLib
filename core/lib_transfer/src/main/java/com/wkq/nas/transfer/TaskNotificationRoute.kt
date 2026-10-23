package com.wkq.nas.transfer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** 所有任务族统一进入活动任务列表；仅携带布尔路由，不携带路径或凭据。 */
object TaskNotificationRoute {
    const val EXTRA_TASKS = "com.wkq.dev.transfer.OPEN_TASKS"
    /** 宿主可提供任务页 Intent；未配置时使用宿主 launcher，无界面宿主不设置点击动作。 */
    @Volatile var intentFactory: ((Context) -> Intent)? = null
    fun pendingIntent(context: Context): PendingIntent? {
        val intent = intentFactory?.invoke(context)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(context, 4100,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TASKS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
