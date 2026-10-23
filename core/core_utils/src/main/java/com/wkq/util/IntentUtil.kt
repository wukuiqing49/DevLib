package com.wkq.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 常见系统 Intent 跳转工具。
 *
 * 方法统一返回是否成功发起跳转；不代表目标 App 一定完成了后续动作。
 */
object IntentUtil {

    /** 使用浏览器打开链接，缺少协议时默认补 https://。 */
    fun openBrowser(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val fixedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return startActivitySafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl)))
    }

    /** 打开拨号盘，不需要 CALL_PHONE 权限。 */
    fun dial(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        return startActivitySafely(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
    }

    /** 打开短信编辑页。 */
    fun sendSms(context: Context, phone: String, content: String = ""): Boolean {
        if (phone.isBlank()) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
            .putExtra("sms_body", content)
        return startActivitySafely(context, intent)
    }

    /** 打开邮件客户端。 */
    fun sendEmail(
        context: Context,
        email: String,
        subject: String = "",
        body: String = ""
    ): Boolean {
        if (email.isBlank()) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return startActivitySafely(context, intent)
    }

    /** 打开应用商店详情页，失败时回退到 Google Play Web 页面。 */
    fun openMarket(context: Context, packageName: String = context.packageName): Boolean {
        if (packageName.isBlank()) return false
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        if (startActivitySafely(context, marketIntent)) return true
        return openBrowser(context, "https://play.google.com/store/apps/details?id=$packageName")
    }

    /** 打开应用详情设置页。 */
    fun openAppSettings(context: Context, packageName: String = context.packageName): Boolean {
        if (packageName.isBlank()) return false
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        return startActivitySafely(context, intent)
    }

    /** 打开系统设置首页。 */
    fun openSystemSettings(context: Context): Boolean {
        return startActivitySafely(context, Intent(Settings.ACTION_SETTINGS))
    }

    /** 打开当前 App 的通知设置页。 */
    fun openNotificationSettings(context: Context): Boolean {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        return startActivitySafely(context, intent)
    }

    /** 安全启动 Activity，自动添加 NEW_TASK，适合 Application Context 调用。 */
    fun startActivitySafely(context: Context, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
