package com.wkq.droplan.drop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LocalAddressCandidate(val address: String, val interfaceName: String, val recognizedLan: Boolean)

object LocalAddressDiscovery {
    /** 只生成候选，必须由接收浏览器确认可达；不硬编码热点网关或接口名称。 */
    fun candidates(context: Context): List<LocalAddressCandidate> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val blocked = mutableSetOf<String>()
        val lan = mutableSetOf<String>()
        // 此处需要所有当前网络的快照以排除 VPN/蜂窝接口，不能只查询默认网络。
        @Suppress("DEPRECATION")
        val networks = manager.allNetworks
        networks.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
            val name = manager.getLinkProperties(network)?.interfaceName ?: return@forEach
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) blocked += name
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) lan += name
        }
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return Collections.list(interfaces).filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.name !in blocked }
            .flatMap { network -> Collections.list(network.inetAddresses).filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .mapNotNull { address -> address.hostAddress?.let { LocalAddressCandidate(it, network.name, network.name in lan) } } }
            .distinctBy { it.address }.sortedByDescending { it.recognizedLan }
    }
}
