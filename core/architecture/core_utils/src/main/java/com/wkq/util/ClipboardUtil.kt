package com.wkq.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/**
 * 剪贴板工具。
 *
 * Android 10+ 后台读取剪贴板会受到系统限制，建议只在用户主动操作后读取。
 */
object ClipboardUtil {

    /** 复制纯文本到剪贴板。 */
    fun copyText(context: Context, text: CharSequence, label: String = "text") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** 复制 Uri 到剪贴板。 */
    fun copyUri(context: Context, uri: Uri, label: String = "uri") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
    }

    /** 获取剪贴板第一项的文本。 */
    fun getText(context: Context): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    /** 获取原始 ClipData，适合需要判断 MIME 或 Uri 的场景。 */
    fun getPrimaryClip(context: Context): ClipData? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip
    }

    fun hasText(context: Context): Boolean = getText(context).isNotEmpty()

    /** 清空剪贴板。 */
    fun clear(context: Context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
