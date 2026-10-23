package com.wkq.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * 系统分享工具。
 *
 * 所有方法都会通过系统 Chooser 发起分享，并返回是否成功发起 Intent。
 */
object ShareUtil {

    /** 分享纯文本。 */
    fun shareText(context: Context, text: String, title: String = ""): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return startChooser(context, intent, title)
    }

    /** 分享单个 Uri。跨应用分享 content:// Uri 时会自动授予临时读权限。 */
    fun shareFile(
        context: Context,
        uri: Uri,
        mimeType: String = "application/octet-stream",
        title: String = ""
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startChooser(context, intent, title)
    }

    /** 分享本地文件，会通过 FileProvider 转成安全 Uri。 */
    fun shareFile(
        context: Context,
        file: File,
        mimeType: String = FileUtil.getMimeType(file.name),
        title: String = ""
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        return shareFile(context, FileUtil.getUriForFile(context, file), mimeType, title)
    }

    /** 分享单张图片。 */
    fun shareImage(context: Context, uri: Uri, title: String = ""): Boolean {
        return shareFile(context, uri, "image/*", title)
    }

    /** 分享单张本地图片文件。 */
    fun shareImage(context: Context, file: File, title: String = ""): Boolean {
        return shareFile(context, file, FileUtil.getMimeType(file.name).takeIf { it.startsWith("image/") } ?: "image/*", title)
    }

    /** 分享多个 Uri。空列表会直接返回 false。 */
    fun shareMultipleFiles(
        context: Context,
        uris: ArrayList<Uri>,
        mimeType: String = "*/*",
        title: String = ""
    ): Boolean {
        if (uris.isEmpty()) return false
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(mimeType)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startChooser(context, intent, title)
    }

    /** 分享多个本地文件，会统一转成 FileProvider Uri。 */
    fun shareMultipleFiles(
        context: Context,
        files: List<File>,
        mimeType: String = "*/*",
        title: String = ""
    ): Boolean {
        val uris = files
            .filter { it.exists() && it.isFile }
            .mapTo(ArrayList()) { FileUtil.getUriForFile(context, it) }
        return shareMultipleFiles(context, uris, mimeType, title)
    }

    private fun startChooser(context: Context, intent: Intent, title: String): Boolean {
        val chooser = Intent.createChooser(intent, title.ifBlank { null })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
