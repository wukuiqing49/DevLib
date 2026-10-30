package com.wkq.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

/**
 * 网络状态工具。
 *
 * 依赖 ACCESS_NETWORK_STATE 权限；未授权或系统服务不可用时返回保守结果。
 */
object NetworkUtil {

    /** 当前网络是否具备 Internet 能力。 */
    fun isNetworkAvailable(context: Context): Boolean {
        return getActiveCapabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** 当前网络是否已经通过系统联网验证。 */
    fun isNetworkValidated(context: Context): Boolean {
        return getActiveCapabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** 当前是否使用 Wi-Fi。 */
    fun isWifi(context: Context): Boolean {
        return getActiveCapabilities(context)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** 当前是否使用蜂窝网络。 */
    fun isMobileData(context: Context): Boolean {
        return getActiveCapabilities(context)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    /** 当前是否使用以太网。 */
    fun isEthernet(context: Context): Boolean {
        return getActiveCapabilities(context)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }

    /** 当前活跃网络是否是计费网络。 */
    fun isActiveNetworkMetered(context: Context): Boolean {
        return runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.isActiveNetworkMetered
        }.getOrDefault(false)
    }

    /** 获取当前网络类型：wifi/mobile/ethernet/bluetooth/none/unknown。 */
    fun getNetworkType(context: Context): String {
        val capabilities = getActiveCapabilities(context) ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "unknown"
        }
    }

    /** 打开无线网络设置页。Android 10+ 使用 Internet Connectivity Panel。 */
    fun openWirelessSettings(context: Context): Boolean {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIRELESS_SETTINGS
        }
        return runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun getActiveCapabilities(context: Context): NetworkCapabilities? {
        return runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return null
            manager.getNetworkCapabilities(network)
        }.getOrNull()
    }
}
