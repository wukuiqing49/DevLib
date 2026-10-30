package com.wkq.util

import android.content.Context
import android.app.Application
import com.wkq.util.coil.CacheManager
import com.wkq.util.log.ALog

/**
 * CoreUtils 初始化配置。
 *
 * 默认初始化存储、图片加载和应用前后台监听；日志保持关闭，避免工具库发布后抢占宿主 App 行为。
 */
data class CoreUtilsConfig(
    /** 是否初始化 MMKV 存储能力。 */
    val initStorage: Boolean = true,
    /** 是否初始化 Coil ImageLoader 与图片缓存。 */
    val initImageLoader: Boolean = true,
    /** 是否初始化 ALog。 */
    val initLog: Boolean = false,
    /** 是否输出控制台日志，通常传 BuildConfig.DEBUG。 */
    val debug: Boolean = false,
    /** 是否写入文件日志。 */
    val logToFile: Boolean = false,
    /** 是否接管全局崩溃并写入日志，建议仅宿主 App 显式开启。 */
    val logCaptureCrash: Boolean = false,
    /** 日志中是否追加调用文件、行号和方法。 */
    val showLogStackInfo: Boolean = false,
    /** 日志文件名前缀。 */
    val logFilePrefix: String = "app",
    /** 单个日志文件最大大小，超过后按日期后缀滚动。 */
    val logMaxFileSize: Long = 10 * 1024 * 1024L,
    /** 日志保留天数。 */
    val logCacheDays: Int = 7,
    /** 可选的命名 MMKV id。 */
    val storageId: String? = null,
    /** 命名 MMKV 的可选加密 key。 */
    val storageCryptKey: String? = null,
    /** FileProvider authority，默认 ${applicationId}.fileprovider。 */
    val fileProviderAuthority: String? = null,
    /** 图片永久内存缓存大小。 */
    val pinnedMemoryMB: Int = 20,
    /** Coil 内存缓存占可用内存比例。 */
    val imageMemoryCachePercent: Double = 0.25,
    /** 图片永久磁盘缓存大小。 */
    val pinnedDiskMB: Int = 200,
    /** Coil 磁盘缓存大小。 */
    val imageDiskCacheMB: Int = 200,
    /** 是否注册为 Coil 全局单例，默认不抢占宿主配置。 */
    val registerCoilSingleton: Boolean = false,
    /** 是否初始化应用前后台监听。 */
    val initAppLifecycleMonitor: Boolean = true
)

/**
 * AndroidCoreUtils 统一初始化入口。
 */
object CoreUtils {
    @Volatile
    private var initialized = false

    @Volatile
    private lateinit var appContext: Context

    @Volatile
    private var config: CoreUtilsConfig = CoreUtilsConfig()

    /**
     * 初始化工具库。建议在 Application.onCreate 中调用。
     *
     * 重复调用只接受与首次调用完全相同的配置，避免已初始化的子组件与公开配置不一致。
     */
    @Synchronized
    fun init(context: Context, config: CoreUtilsConfig = CoreUtilsConfig()) {
        if (initialized) {
            check(this.config == config) {
                "CoreUtils has already been initialized with a different configuration. " +
                    "Initialize it once in Application.onCreate()."
            }
            return
        }

        config.validate()
        val applicationContext = context.applicationContext

        if (config.initStorage) {
            SpUtils.init(applicationContext)
            if (!config.storageId.isNullOrBlank()) {
                SpUtils.getMMKV(config.storageId, config.storageCryptKey)
            }
        }

        if (config.initImageLoader) {
            CacheManager.init(
                context = applicationContext,
                pinnedMemoryMB = config.pinnedMemoryMB,
                memoryCachePercent = config.imageMemoryCachePercent,
                pinnedDiskMB = config.pinnedDiskMB,
                diskCacheMB = config.imageDiskCacheMB,
                registerSingleton = config.registerCoilSingleton
            )
        }

        if (config.initLog) {
            ALog.init(
                context = applicationContext,
                isShow = config.debug,
                showStackInfo = config.showLogStackInfo,
                enableFile = config.logToFile,
                logFilePrefix = config.logFilePrefix,
                maxFileSize = config.logMaxFileSize,
                cacheDays = config.logCacheDays,
                captureCrash = config.logCaptureCrash
            )
        }

        FileUtil.setFileProviderAuthority(
            config.fileProviderAuthority ?: "${applicationContext.packageName}.fileprovider"
        )
        if (config.initAppLifecycleMonitor) {
            (applicationContext as? Application)?.let(AppLifecycleMonitor::init)
        }
        appContext = applicationContext
        this.config = config
        initialized = true
    }

    fun isInitialized(): Boolean = initialized

    /** 获取初始化时保存的 Application Context。未初始化会抛出明确异常。 */
    fun getContext(): Context {
        check(::appContext.isInitialized) {
            "CoreUtils has not been initialized. Call CoreUtils.init(context) first."
        }
        return appContext
    }

    fun getConfig(): CoreUtilsConfig = config

    /** 获取当前 FileProvider authority。 */
    fun getFileProviderAuthority(context: Context? = null): String {
        return config.fileProviderAuthority
            ?: FileUtil.getFileProviderAuthority(context ?: getContext())
    }
}

internal fun CoreUtilsConfig.validate() {
    require(logFilePrefix.isNotBlank()) { "logFilePrefix must not be blank." }
    require(logMaxFileSize > 0L) { "logMaxFileSize must be greater than 0." }
    require(logCacheDays >= 0) { "logCacheDays must not be negative." }
    CacheManager.validateConfiguration(
        pinnedMemoryMB = pinnedMemoryMB,
        memoryCachePercent = imageMemoryCachePercent,
        pinnedDiskMB = pinnedDiskMB,
        diskCacheMB = imageDiskCacheMB
    )
}
