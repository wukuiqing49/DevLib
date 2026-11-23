package com.wkq.dev

import android.app.Application
import com.wkq.util.CoreUtils
import com.wkq.util.CoreUtilsConfig

/** 初始化工作台公共能力，诊断收集仍由用户主动开启。 */
class DevLibApplication : Application() {

    /** 安装诊断与路由，并统一初始化工具库。 */
    override fun onCreate() {
        super.onCreate()
        com.wkq.logreport.LogReport.install(this)
        com.wkq.util.log.ALog.reportSink = { tag, message -> com.wkq.logreport.LogReport.event("logcat", "$tag: $message") }
        com.wkq.router.api.Router.init(this)
        CoreUtils.init(
            context = this,
            config = CoreUtilsConfig(
                debug = BuildConfig.DEBUG,
                initLog = false,
                logCaptureCrash = false
            )
        )
    }
}
