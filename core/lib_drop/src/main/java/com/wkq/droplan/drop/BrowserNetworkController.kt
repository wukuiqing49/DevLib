package com.wkq.droplan.drop

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address

/** 只建网；接收端普通 Wi-Fi 加入后继续用浏览器。由前台服务持有，凭据不持久化。 */
class BrowserNetworkController(context: Context, private val changed: (Snapshot) -> Unit = {}) : Closeable {
    enum class Mode { LOCAL_HOTSPOT, WIFI_DIRECT }
    enum class State { IDLE, STARTING, READY, FAILED }
    data class Snapshot(val state: State = State.IDLE, val mode: Mode? = null, val ssid: String? = null,
                        val password: String? = null, val ownerAddress: String? = null, val error: String? = null) {
        override fun toString() = "BrowserNetwork($state,$mode)"
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var ownsGroup = false
    private var receiverRegistered = false
    @Volatile var snapshot = Snapshot(); private set
    private val monitor = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (snapshot.mode == Mode.WIFI_DIRECT && snapshot.state == State.READY) refreshGroup(generation)
        }
    }
    private fun publish(value: Snapshot) { snapshot = value; changed(value) }
    private fun fail(code: String) {
        val mode = snapshot.mode
        close(); publish(Snapshot(State.FAILED, mode, error = code))
    }

    fun start(mode: Mode) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (snapshot.state == State.STARTING || snapshot.state == State.READY) return
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (app.checkSelfPermission(required) != PackageManager.PERMISSION_GRANTED) {
            publish(Snapshot(State.FAILED, mode, error = "permission")); return
        }
        if (Build.VERSION.SDK_INT < 33) {
            val location = app.getSystemService(LocationManager::class.java)
            @Suppress("DEPRECATION")
            val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else
                location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!enabled) { publish(Snapshot(State.FAILED, mode, error = "location")); return }
        }
        close(); val ticket = generation
        publish(Snapshot(State.STARTING, mode))
        try {
            when (mode) {
                Mode.LOCAL_HOTSPOT -> startHotspot(ticket)
                Mode.WIFI_DIRECT -> startGroup(ticket)
            }
        } catch (_: SecurityException) { fail("permission") }
          catch (_: Exception) { fail("unavailable") }
        main.postDelayed({
            if (generation == ticket && snapshot.state == State.STARTING) {
                fail("timeout")
            }
        }, 30_000)
    }

    private fun startHotspot(ticket: Long) {
        val wifi = app.getSystemService(WifiManager::class.java) ?: return fail("unsupported")
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                if (ticket != generation) { runCatching { value.close() }; return }
                reservation = value
                val ssid: String?; val password: String?
                if (Build.VERSION.SDK_INT >= 30) {
                    val config = value.softApConfiguration
                    ssid = if (Build.VERSION.SDK_INT >= 33) config.wifiSsid?.toString()?.removeSurrounding("\"") else {
                        @Suppress("DEPRECATION") val name = config.ssid; name
                    }
                    password = config.passphrase
                } else {
                    @Suppress("DEPRECATION") val config = value.wifiConfiguration
                    // Android 8–10 只能通过旧配置读取本次热点凭据。
                    @Suppress("DEPRECATION")
                    val legacySsid = config?.SSID
                    @Suppress("DEPRECATION")
                    val legacyPassword = config?.preSharedKey
                    ssid = legacySsid?.removeSurrounding("\""); password = legacyPassword?.removeSurrounding("\"")
                }
                publish(Snapshot(State.READY, Mode.LOCAL_HOTSPOT, ssid, password))
            }
            override fun onStopped() { if (ticket == generation) { reservation = null; fail("stopped") } }
            override fun onFailed(reason: Int) { if (ticket == generation) fail(if (reason == ERROR_INCOMPATIBLE_MODE) "busy" else "unavailable") }
        }, main)
    }

    private fun startGroup(ticket: Long) {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return fail("unsupported")
        val p2p = app.getSystemService(WifiP2pManager::class.java) ?: return fail("unsupported")
        manager = p2p
        val ch = p2p.initialize(app, Looper.getMainLooper()) { if (ticket == generation) fail("stopped") }
            ?: return fail("unavailable")
        channel = ch
        p2p.requestGroupInfo(ch) { existing ->
            if (ticket != generation) return@requestGroupInfo
            if (existing != null) { fail("busy"); return@requestGroupInfo }
            try { p2p.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (ticket != generation) {
                        // 结束操作后迟到的成功必须释放本次创建的组。
                        runCatching { p2p.removeGroup(ch, null) }; return
                    }
                    ownsGroup = true
                    try {
                        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(monitor, filter, Context.RECEIVER_EXPORTED)
                        else app.registerReceiver(monitor, filter)
                        receiverRegistered = true
                        refreshGroup(ticket)
                    } catch (_: Exception) { fail("unavailable") }
                }
                override fun onFailure(reason: Int) { if (ticket == generation) fail(if (reason == WifiP2pManager.BUSY) "busy" else "unavailable") }
            }) } catch (_: SecurityException) { fail("permission") }
                 catch (_: Exception) { fail("unavailable") }
        }
    }

    private fun refreshGroup(ticket: Long) {
        val p2p = manager ?: return; val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { group ->
                if (ticket != generation) return@requestGroupInfo
                if (group == null || !group.isGroupOwner) { fail("stopped"); return@requestGroupInfo }
                try { p2p.requestConnectionInfo(ch) { info ->
                    if (ticket != generation) return@requestConnectionInfo
                    if (!info.groupFormed || !info.isGroupOwner) { fail("stopped"); return@requestConnectionInfo }
                    val address = (info.groupOwnerAddress as? Inet4Address)?.hostAddress
                    publish(Snapshot(State.READY, Mode.WIFI_DIRECT, group.networkName, group.passphrase, address))
                } } catch (_: SecurityException) { fail("permission") }
                    catch (_: Exception) { fail("unavailable") }
            }
        } catch (_: SecurityException) { fail("permission") }
          catch (_: Exception) { fail("unavailable") }
    }

    override fun close() {
        generation++
        runCatching { reservation?.close() }; reservation = null
        if (receiverRegistered) { runCatching { app.unregisterReceiver(monitor) }; receiverRegistered = false }
        val ch = channel; val p2p = manager; val owned = ownsGroup
        channel = null; manager = null; ownsGroup = false
        if (ch != null) {
            fun closeChannel() { if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() } }
            if (owned && p2p != null) {
                try { p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = closeChannel()
                    override fun onFailure(reason: Int) = closeChannel()
                }) } catch (_: Exception) { closeChannel() }
            } else closeChannel()
        }
        publish(Snapshot())
    }
}
