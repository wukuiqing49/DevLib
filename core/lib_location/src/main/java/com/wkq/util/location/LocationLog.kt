package com.wkq.util.location

import android.util.Log
import com.wkq.location.BuildConfig

/**
 * 定位调试日志统一入口。
 *
 * 现场坐标、精度和 provider 只允许在 Debug 构建中输出，Release 不写入 logcat。
 */
internal object LocationLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun e(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message)
        }
    }
}
