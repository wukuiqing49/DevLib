package com.wkq.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.wkq.location.address.LocationBroadcast
import com.wkq.location.address.LocationConfig
import com.wkq.location.address.LocationResult
import com.wkq.location.address.LocationType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/2 13:52
 *
 *@Desc:  定位获取的工具类
 */
object LocationKit : LocationListener {

    // -------------------- 全局字段 --------------------
    private var locationManager: LocationManager? = null                 // 系统 LocationManager
    private lateinit var appContext: Context                              // 保存 ApplicationContext，避免内存泄漏
    private var currentConfig: LocationConfig = LocationConfig()          // 当前定位配置
    private val handler = Handler(Looper.getMainLooper())                 // 主线程 Handler，用于超时回调
    private var timeoutRunnable: Runnable? = null                         // 定位超时处理
    var callback: ((LocationResult) -> Unit)? = null                     // 外部回调定位结果

    private val lock = Any()                                              // 同步锁，防止并发回调冲突
    private var lastLocation: Location? = null                             // 上一次有效位置，用于过滤微抖动/大跳变
    private var running = false
    private var session = 0L
    private var singleLocationReceived = false                             // SINGLE 模式是否已经收到一次有效回调

    // -------------------- 初始化 --------------------
    /**
     * 初始化 LocationKit
     * @param context ApplicationContext 或 Activity
     * @param config 可选配置对象
     *
     * 注意：必须调用 init() 后才能 startLocation()
     */
    fun init(context: Context, config: LocationConfig = LocationConfig()) {
        stopLocation()
        appContext = context.applicationContext          // 统一使用 ApplicationContext
        locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        currentConfig = config
    }

    // -------------------- 开始定位 --------------------
    /**
     * 开始定位
     * 回调 listener 返回 LocationResult
     */
    fun startLocation(listener: (LocationResult) -> Unit) {
        stopLocation()
        if (locationManager == null) {
            listener(LocationResult(false, createDefaultLocation(), "LocationManager unavailable"))
            return
        }
        if (!hasPermission()) {
            listener(LocationResult(false, createDefaultLocation(), appContext.getString(R.string.location_permission_missing)))
            return
        }

        // 2. 判断是否有可用 Provider
        val providers = locationManager?.getProviders(true).orEmpty()
        if (providers.isEmpty()) {
            listener(LocationResult(false, createDefaultLocation(), appContext.getString(R.string.location_service_unavailable)))
            return
        }

        callback = listener
        running = true
        val expectedSession = session
        singleLocationReceived = false
        lastLocation = null

        val type = currentConfig.getLocationType()
        val minTimeMs = currentConfig.getMinTimeMs()
        val minDistanceM = currentConfig.getMinDistanceM()

        // 根据定位类型请求不同 Provider
        when (type) {
            LocationType.FAST -> {
                val last = getValidLastKnownLocation()
                if (last != null) processLocation(last, force = true)   // 立即回调最后位置
                if (running && session == expectedSession) requestAllProviders(minTimeMs, minDistanceM)
            }

            LocationType.FUSION, LocationType.SINGLE -> requestAllProviders(minTimeMs, minDistanceM)
            LocationType.LOCATION_NET -> requestProvider(
                LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM
            )

            LocationType.LOCATION_GPS -> requestProvider(
                LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM
            )

        }

        if (!running || session != expectedSession) return

        // 设置超时回调
        timeoutRunnable = Runnable {
            synchronized(lock) {
                if (running && session == expectedSession && !singleLocationReceived) {
                    val cached = getValidLastKnownLocation()
                    if (cached != null) {
                        processLocation(cached, force = true)
                    } else {
                        // 缓存无效，返回默认值
                        processLocation(createDefaultLocation(), force = true)
                    }
                }
            }
        }
        handler.postDelayed(timeoutRunnable!!, currentConfig.getTimeout())
    }

    // -------------------- 停止定位 --------------------
    /** 停止定位并移除回调 */
    fun stopLocation() {
        running = false
        session++
        callback = null
        handler.removeCallbacksAndMessages(null)
        timeoutRunnable = null
        try { locationManager?.removeUpdates(this) } catch (_: SecurityException) {
            // 权限可能已撤回；本地任务与回调已清理，不再次注册监听。
        }
    }

    // -------------------- Provider 请求 --------------------
    /** 请求指定 Provider */
    @SuppressLint("MissingPermission")
    private fun requestProvider(provider: String, time: Long, distance: Float) {
        locationManager?.let {
            if (!running || !it.isProviderEnabled(provider)) return
            if (provider == LocationManager.GPS_PROVIDER && ActivityCompat.checkSelfPermission(
                    appContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) return
            try {
                it.requestLocationUpdates(provider, time, distance, this, Looper.getMainLooper())
            } catch (_: SecurityException) {
                val listener = callback
                stopLocation()
                listener?.invoke(LocationResult(false, null, appContext.getString(R.string.location_permission_missing)))
            }
        }
    }

    /** 请求所有可用 Provider（GPS/网络/PASSIVE） */
    private fun requestAllProviders(minTimeMs: Long, minDistanceM: Float) {
        // 获取可用 Provider
        val providers = locationManager?.getProviders(true).orEmpty()
        val finalProviders = if (providers.isEmpty()) {
            // ROM 返回空时，强制使用 NETWORK + PASSIVE
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else providers

        finalProviders.forEach { provider -> requestProvider(provider, minTimeMs, minDistanceM) }
    }

    @SuppressLint("MissingPermission")
    /** 从系统缓存选择一分钟内精度最好的位置，撤回权限时跳过不可读取的 Provider。 */
    private fun getValidLastKnownLocation(): Location? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        var best: Location? = null
        locationManager?.getProviders(true)?.forEach { provider ->
            val loc = try { locationManager?.getLastKnownLocation(provider) }
                catch (_: SecurityException) { null } ?: return@forEach
            if (now - loc.time > 60_000) return@forEach
            if (best == null || loc.accuracy < best.accuracy) best = loc
        }
        return best
    }

    // -------------------- 权限检查 --------------------
    /** 检查定位权限 */
    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // -------------------- 系统缓存位置 --------------------
    /** 获取系统缓存的最后一次定位 */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (!hasPermission()) return null
        locationManager?.let {
            val providers = it.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                val l = it.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best.accuracy) best = l
            }
            return best
        }
        return null
    }

    /** 默认位置（定位失败时使用） */
    private fun createDefaultLocation(): Location {
        val loc = Location("default")
        loc.latitude = currentConfig.getDefaultLatitude()
        loc.longitude = currentConfig.getDefaultLongitude()
        return loc
    }

    // -------------------- 工具方法 --------------------
    /** Haversine公式计算两点距离（单位：米） */
    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(
                dLon / 2
            ).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (R * c).toFloat()
    }

    /** 微抖动 + 大跳变过滤 */
    private fun isValidLocation(last: Location?, current: Location): Boolean {
        last ?: return true
        val distance =
            distanceBetween(last.latitude, last.longitude, current.latitude, current.longitude)
        if (distance < currentConfig.getFilterMin()) return false
        if (distance > currentConfig.getFilterMax()) return false
        return true
    }

    // -------------------- 核心处理逻辑 --------------------
    /**
     * 核心处理逻辑
     * @param force 是否强制回调（忽略微抖动/大跳变过滤）
     */
    fun processLocation(location: Location, force: Boolean = false) {
        synchronized(lock) {
            if (!running || singleLocationReceived) return
            // 临时授权可能已失效，不能继续转发已排队的位置或缓存坐标。
            if (!hasPermission()) {
                val listener = callback
                stopLocation()
                listener?.invoke(LocationResult(false, null, appContext.getString(R.string.location_permission_missing)))
                return
            }
            if (location.provider == "default") {
                val listener = callback
                stopLocation()
                listener?.invoke(LocationResult(false, location, appContext.getString(R.string.location_timeout)))
                return
            }

            val type = currentConfig.getLocationType()

            if (!force && currentConfig.getFilter() && !isValidLocation(lastLocation, location)) return

            if (!force && type == LocationType.FUSION && lastLocation != null) {
                if (location.accuracy >= lastLocation!!.accuracy) return
            }

            if (!force && lastLocation != null && lastLocation!!.latitude == location.latitude && lastLocation!!.longitude == location.longitude) return

            lastLocation = location
            val listener = callback
            val sendBroadcast = currentConfig.isUseBroadcast()

            if (type == LocationType.SINGLE) {
                singleLocationReceived = true
                stopLocation()
            }

            timeoutRunnable?.let { handler.removeCallbacks(it) }

            // 位置广播仅发送给宿主应用包。
            if (sendBroadcast) {
                val intent = Intent(LocationBroadcast.ACTION_LOCATION_UPDATE).setPackage(appContext.packageName)
                intent.putExtra(LocationBroadcast.EXTRA_LOCATION, location)
                appContext.sendBroadcast(intent)
            }
            listener?.invoke(LocationResult(true, location, appContext.getString(R.string.location_success)))
        }
    }

    // -------------------- LocationListener 回调 --------------------
    /** 接收系统位置更新并交由当前会话过滤，停止后不再转发。 */
    override fun onLocationChanged(location: Location) {
        processLocation(location)
    }

    /** 接收系统位置更新并交由当前会话过滤，停止后不再转发。 */
    override fun onLocationChanged(locations: List<Location>) {
        if (locations.isNotEmpty()) processLocation(locations[0])
    }

    /** 记录 Provider 启用状态，不自动发起新的定位任务。 */
    override fun onProviderEnabled(provider: String) {
        Log.d("定位状态", "$provider 已启用")
    }

    /** 当前任务收到 Provider 停用时报告失败，不伪造默认位置成功。 */
    override fun onProviderDisabled(provider: String) {
        if (running) callback?.invoke(LocationResult(false, null, appContext.getString(R.string.location_service_unavailable)))
    }
}
