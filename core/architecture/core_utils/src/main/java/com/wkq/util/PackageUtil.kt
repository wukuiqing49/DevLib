package com.wkq.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/**
 * 应用包信息工具。
 *
 * Android 11+ 查询其他应用受 package visibility 限制，宿主如需查询第三方包，
 * 需要在 manifest 的 queries 中声明对应包名或 intent。
 */
object PackageUtil {

    /** 获取当前应用包名。 */
    fun getPackageName(context: Context): String = context.packageName

    /** 获取版本名，失败返回空字符串。 */
    fun getVersionName(context: Context, packageName: String = context.packageName): String {
        return getPackageInfo(context, packageName)?.versionName.orEmpty()
    }

    /** 获取版本号，兼容 Android P 之后的 longVersionCode。 */
    fun getVersionCode(context: Context, packageName: String = context.packageName): Long {
        val info = getPackageInfo(context, packageName) ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** 获取首次安装时间戳；查询失败时返回 `0`。 */
    fun getFirstInstallTime(context: Context, packageName: String = context.packageName): Long {
        return getPackageInfo(context, packageName)?.firstInstallTime ?: 0L
    }

    /** 获取最后更新安装包的时间戳；查询失败时返回 `0`。 */
    fun getLastUpdateTime(context: Context, packageName: String = context.packageName): Long {
        return getPackageInfo(context, packageName)?.lastUpdateTime ?: 0L
    }

    /** 判断当前应用是否为 debuggable 构建。 */
    fun isDebuggable(context: Context, packageName: String = context.packageName): Boolean {
        val appInfo = getApplicationInfo(context, packageName) ?: return false
        return appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** 获取 APK 安装路径；查询失败时返回 `null`。 */
    fun getSourceDir(context: Context, packageName: String = context.packageName): String? {
        return getApplicationInfo(context, packageName)?.sourceDir
    }

    /** 判断指定包名是否已安装且对当前应用可见。 */
    fun isInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return getPackageInfo(context, packageName) != null
    }

    /** 获取应用名称。 */
    fun getAppName(context: Context, packageName: String = context.packageName): String {
        return runCatching {
            val appInfo = getApplicationInfo(context, packageName) ?: return ""
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault("")
    }

    /** 获取应用图标。 */
    fun getAppIcon(context: Context, packageName: String = context.packageName): Drawable? {
        return runCatching {
            val appInfo = getApplicationInfo(context, packageName) ?: return null
            context.packageManager.getApplicationIcon(appInfo)
        }.getOrNull()
    }

    /** 获取应用启动 Intent。 */
    fun getLaunchIntent(context: Context, packageName: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    /** 打开指定应用。 */
    fun openApp(context: Context, packageName: String): Boolean {
        val intent = getLaunchIntent(context, packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 打开指定应用详情设置页。 */
    fun openAppSettings(context: Context, packageName: String = context.packageName): Boolean {
        val intent = Intent(SettingsAction, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 获取安装来源包名。 */
    fun getInstallerPackageName(context: Context, packageName: String = context.packageName): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
    }

    /** 获取 APK 签名证书 SHA-256 指纹，格式为 AA:BB:CC。 */
    fun getSignatureSha256(context: Context, packageName: String = context.packageName): String? {
        val signatures = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures
            }
        }.getOrNull()

        val firstSignature = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(firstSignature.toByteArray())
        return digest.joinToString(":") { "%02X".format(Locale.US, it) }
    }

    private fun getPackageInfo(context: Context, packageName: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private fun getApplicationInfo(context: Context, packageName: String): ApplicationInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private const val SettingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
}
