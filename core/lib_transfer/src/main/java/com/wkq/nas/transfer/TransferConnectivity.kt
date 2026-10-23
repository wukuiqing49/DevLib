package com.wkq.nas.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class TransferNetwork(val connected: Boolean = false, val metered: Boolean = true) {
    fun allowed(allowMetered: Boolean) = connected && (allowMetered || !metered)
}

/** 局域网无需互联网验证；离线和计费限制在认领文件前检查，不消耗文件重试次数。 */
object TransferConnectivity {
    fun current(context: Context): TransferNetwork {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return TransferNetwork()
        val connected = Build.VERSION.SDK_INT < 28 || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        return TransferNetwork(connected, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    fun observe(context: Context) = callbackFlow {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current(app)) }
            override fun onLost(network: Network) { trySend(current(app)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(current(app)) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(current(app))
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
