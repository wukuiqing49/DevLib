package com.wkq.location.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wkq.location.LocationKit
import com.wkq.location.LocationSensorFusionManager
import com.wkq.location.R

/** 可选前台定位服务；宿主在可见界面完成定位授权后显式启动，不自动打开设置页。 */
class SensorService : Service() {
    private var fusionManager: LocationSensorFusionManager? = null

    /** 该服务仅用于显式启动采集，不提供绑定接口。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 先校验位置授权，再建立显式位置类型前台通知；系统拒绝启动时停止服务。 */
    override fun onCreate() {
        super.onCreate()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission is required before starting the service")
            stopSelf()
            return
        }
        val channelId = "sensor_service_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            channelId, getString(R.string.location_service_title), NotificationManager.IMPORTANCE_LOW
        ))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.location_service_title))
            .setContentText(getString(R.string.location_service_running))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).build()
        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
            ServiceCompat.startForeground(this, 1, notification, serviceType)
        } catch (error: SecurityException) {
            // Android 14 起还会核验 while-in-use 授权；预检查不替代系统校验。
            Log.w(TAG, "Location foreground service permission rejected", error)
            stopSelf()
            return
        } catch (error: IllegalStateException) {
            // 包括 Android 12 起的 ForegroundServiceStartNotAllowedException。
            Log.w(TAG, "Foreground service start is not allowed in the current app state", error)
            stopSelf()
            return
        }
        LocationKit.init(applicationContext)
        fusionManager = LocationSensorFusionManager(applicationContext).also { fusion ->
            fusion.start(object : LocationCallback {
                /** 服务保留采集生命周期，数据消费由宿主的定位入口负责。 */
                override fun onLocationUpdate(info: SensorPointInfo) = Unit
                /** 服务不弹出传感器能力提示或自动跳转界面。 */
                override fun onSensorSupportStatus(sensors: List<SensorStatus>) = Unit
            })
        }
    }

    /** 不在进程被杀后自动恢复位置采集，宿主重新确认任务和权限后启动。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** 停止已初始化的融合管理器，避免未初始化字段崩溃及服务销毁后继续采集。 */
    override fun onDestroy() {
        fusionManager?.stop()
        fusionManager = null
        super.onDestroy()
    }

    /** 服务日志标识，不包含位置和用户数据。 */
    private companion object {
        const val TAG = "LocationSensorService"
    }
}
