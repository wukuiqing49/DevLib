package com.wkq.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 网络传输类型。 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    BLUETOOTH,
    VPN
}

/** 当前活跃网络的状态快照。 */
data class NetworkStatus(
    /** 是否存在具备 Internet capability 的活跃网络。 */
    val isAvailable: Boolean,
    /** 网络是否已经通过系统联网验证，通常比 [isAvailable] 更适合判断实际可访问互联网。 */
    val isValidated: Boolean,
    /** 当前网络使用的传输类型集合，例如 Wi-Fi、蜂窝网络或 VPN。 */
    val transports: Set<NetworkTransport>
)

/**
 * 基于 Flow 监听网络连接变化。
 *
 * 需要 ACCESS_NETWORK_STATE 权限，库 Manifest 已声明。停止收集 Flow 时会自动注销 NetworkCallback。
 */
object NetworkMonitor {
    /** 返回冷 Flow；每次收集都会独立注册网络回调，并先发射一次当前状态。 */
    fun observe(context: Context): Flow<NetworkStatus> = callbackFlow {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish(manager)

            override fun onLost(network: Network) = publish(manager)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish(manager)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        publish(manager)
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (error: SecurityException) {
            close(error)
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun kotlinx.coroutines.channels.ProducerScope<NetworkStatus>.publish(manager: ConnectivityManager) {
        trySend(manager.currentStatus())
    }

    private fun ConnectivityManager.currentStatus(): NetworkStatus {
        val network = activeNetwork ?: return NetworkStatus(false, false, emptySet())
        val capabilities = getNetworkCapabilities(network) ?: return NetworkStatus(false, false, emptySet())
        return NetworkStatus(
            isAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transports = buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkTransport.BLUETOOTH)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
            }
        )
    }
}
