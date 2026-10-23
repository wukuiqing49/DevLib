package com.wkq.droplan.drop

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/** 观察所有网络（包含无互联网 Wi-Fi）；仅通知刷新，不切网、不接管热点。 */
class LocalNetworkMonitor(context: Context, private val changed: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var registered = false
    private val refresh = Runnable { if (running) changed() }
    // 部分系统不把热点接口作为 Network 暴露，页面可见期间补充低频接口检查。
    private val fallback = object : Runnable {
        override fun run() {
            if (!running) return
            changed(); handler.postDelayed(this, 5000)
        }
    }
    private fun schedule() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 300)
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = schedule()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
    }
    fun start() {
        if (running) return
        running = true
        registered = runCatching {
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback, handler)
            true
        }.getOrDefault(false)
        handler.post(refresh); handler.postDelayed(fallback, 5000)
    }
    fun stop() {
        running = false
        if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
        handler.removeCallbacks(refresh); handler.removeCallbacks(fallback)
    }
}
