package com.wkq.naslib

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.util.Collections
import kotlin.coroutines.resume

/** 只发现广播 DNS-SD 的局域网服务，不扫描网段或自动信任主机。 */
class NasDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    @Suppress("DEPRECATION")
    suspend fun discover(): List<DiscoveredNas> = discover { }
    @Suppress("DEPRECATION")
    suspend fun discover(updated: (List<DiscoveredNas>) -> Unit): List<DiscoveredNas> {
        val found = mutableListOf<DiscoveredNas>()
        for ((type, protocol) in listOf("_smb._tcp." to NasProtocol.SMB, "_sftp-ssh._tcp." to NasProtocol.SFTP,
            "_webdavs._tcp." to NasProtocol.WEBDAV)) {
            currentCoroutineContext().ensureActive()
            val services = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
            val failure = java.util.concurrent.atomic.AtomicBoolean(false)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) { failure.set(true) }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onServiceFound(service: NsdServiceInfo) { synchronized(services) {
                    if (services.size < 20 && services.none { it.serviceName == service.serviceName }) services += service
                } }
                override fun onServiceLost(service: NsdServiceInfo) { synchronized(services) { services.removeAll { it.serviceName == service.serviceName } } }
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                delay(3000)
                if (failure.get()) throw TransferException(TransferError.NETWORK)
            } finally { runCatching { manager.stopServiceDiscovery(listener) } }
            val snapshot = synchronized(services) { services.toList() }
            for (service in snapshot.take(10)) {
                val resolved = withTimeoutOrNull(2000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        val callback = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (continuation.isActive) continuation.resume(info)
                            }
                        }
                        try { manager.resolveService(service, callback) }
                        catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(null) }
                    }
                }
                val host = resolved?.host?.hostAddress
                if (host != null && resolved.port in 1..65535) {
                    found += DiscoveredNas(resolved.serviceName, protocol, host, resolved.port)
                    currentCoroutineContext().ensureActive()
                    updated(found.distinctBy { Triple(it.protocol, it.host, it.port) })
                }
            }
        }
        return found.distinctBy { Triple(it.protocol, it.host, it.port) }
    }
}

data class DiscoveredNas(val name: String, val protocol: NasProtocol, val host: String, val port: Int)
