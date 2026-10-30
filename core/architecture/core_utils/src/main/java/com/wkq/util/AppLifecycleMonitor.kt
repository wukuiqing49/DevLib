package com.wkq.util

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用进程的前后台状态。 */
enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND
}

/**
 * 应用前后台监听工具。
 *
 * 通过 [Application.ActivityLifecycleCallbacks] 统计已启动 Activity。后台状态会延迟
 * 短暂确认，以避免旋转屏幕等配置变更期间错误地发出前后台切换事件。
 */
object AppLifecycleMonitor {

    private const val BACKGROUND_CONFIRM_DELAY_MS = 700L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(AppLifecycleState.BACKGROUND)
    private val lock = Any()

    private var initializedApplication: Application? = null
    private var startedActivityCount = 0
    private var backgroundCheck: Runnable? = null

    /** 可观察的应用前后台状态流。 */
    val state: StateFlow<AppLifecycleState> = mutableState.asStateFlow()

    /** 应用当前是否在前台。 */
    fun isInForeground(): Boolean = state.value == AppLifecycleState.FOREGROUND

    /**
     * 注册应用生命周期监听。建议在 [Application.onCreate] 或 [CoreUtils.init] 后调用。
     * 同一 [Application] 可重复调用；传入不同 Application 时会抛出异常。
     */
    fun init(application: Application) {
        synchronized(lock) {
            val previous = initializedApplication
            if (previous != null) {
                check(previous === application) { "AppLifecycleMonitor has already been initialized." }
                return
            }
            initializedApplication = application
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            synchronized(lock) {
                startedActivityCount += 1
                backgroundCheck?.let(mainHandler::removeCallbacks)
                backgroundCheck = null
                mutableState.value = AppLifecycleState.FOREGROUND
            }
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            synchronized(lock) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) scheduleBackgroundCheck()
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun scheduleBackgroundCheck() {
        backgroundCheck?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            synchronized(lock) {
                if (startedActivityCount == 0) {
                    mutableState.value = AppLifecycleState.BACKGROUND
                }
                backgroundCheck = null
            }
        }
        backgroundCheck = runnable
        mainHandler.postDelayed(runnable, BACKGROUND_CONFIRM_DELAY_MS)
    }
}
