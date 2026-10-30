package com.wkq.iptc.feature.press.metadata

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.wkq.exif.R
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 仅在原生元数据任务期间提升进程优先级，不常驻、不自动重启、不点亮屏幕。 */
internal class MetadataExecutionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var leases = 0
    @Volatile private var failure: Throwable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopIdle = Runnable { if (leases == 0) stopSelf() }
    private inner class LocalBinder : Binder() { val service get() = this@MetadataExecutionService }

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.metadata_processing_title), NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.metadata_processing_title))
                .setContentText(getString(R.string.metadata_processing_message))
                .setOngoing(true).setOnlyAlertOnce(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PressIPTC:NativeMetadata").apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L)
                }
        } catch (error: Exception) {
            failure = IllegalStateException("Cannot protect background metadata processing", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(stopIdle)
        // 绑定失败或启动调用方被取消时，也不能留下空闲服务。
        if (leases == 0) handler.postDelayed(stopIdle, 2_000)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    private fun open() {
        checkRunning()
        handler.removeCallbacks(stopIdle)
        // 每次进入续期，长批次也不能在十分钟边界出现无锁间隙。
        wakeLock?.acquire(10 * 60 * 1000L)
        leases++
    }

    private fun close() {
        check(leases > 0)
        leases--
        if (leases == 0) {
            // 连续命令间也保持 CPU 锁，防止部分 OEM 在下一次绑定前冻结整个应用。
            // 空闲两秒后由 onDestroy 统一释放，不常驻。
            handler.postDelayed(stopIdle, 2_000)
        }
    }

    private fun checkRunning() { failure?.let { throw it } }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() } }

    override fun onTimeout(startId: Int, fgsType: Int) {
        failure = IllegalStateException("Background metadata processing time limit reached")
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        failure = IllegalStateException("Background metadata processing stopped")
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "press_metadata_processing"
        private const val NOTIFICATION = 4072

        suspend fun <T> protect(context: Context, action: suspend (() -> Unit) -> T): T {
            val app = context.applicationContext
            var service: MetadataExecutionService? = null
            var bound = false
            var opened = false
            val connection = object : ServiceConnection {
                var continuation: CancellableContinuation<MetadataExecutionService>? = null
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    continuation?.let { if (it.isActive) it.resume((binder as MetadataExecutionService.LocalBinder).service) }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    service?.failure = IllegalStateException("Background metadata service disconnected")
                }
                override fun onNullBinding(name: ComponentName) {
                    continuation?.let { if (it.isActive) it.resumeWithException(IllegalStateException("Metadata service unavailable")) }
                }
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    val intent = Intent(app, MetadataExecutionService::class.java)
                    // Android 12+ 拒绝不合法的后台启动时明确失败，不能静默降级为无保护执行。
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
                    service = withTimeout(5_000) {
                        suspendCancellableCoroutine { continuation ->
                            connection.continuation = continuation
                            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                            check(bound) { "Cannot bind metadata processing service" }
                        }
                    }
                    service!!.open()
                    opened = true
                }
                return action { service!!.checkRunning() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (opened) service!!.close()
                    if (bound) app.unbindService(connection)
                }
            }
        }
    }
}
