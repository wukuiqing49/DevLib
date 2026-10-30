package com.wkq.util.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量级日志工具。
 *
 * 支持控制台日志、本地文件日志、按日期/大小滚动、过期日志清理、Tag 白名单过滤和可选崩溃捕获。
 * 作为公共工具库时默认行为偏保守：是否输出控制台、是否写文件、是否捕获崩溃都由宿主显式配置。
 */
object ALog {

    private const val TAG = "ALog"
    private const val SYNC_TIMEOUT_SECONDS = 2L

    private var showStackInfo = false
    private var enableConsole = true
    private var enableFile = false
    private var logFilePrefix = "app"
    private var maxFileSize = 10 * 1024 * 1024L
    private var cacheDays = 7

    private lateinit var logDir: File
    private var currentLogFile: File? = null
    private var fileWriter: FileWriter? = null

    private val initialized = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val enableTags = mutableSetOf<String>()
    private var useTagFilter = false

    /**
     * 初始化日志系统。
     *
     * 日志目录固定为 `context.filesDir/logs`。重复调用不会重新应用配置，建议在 Application 或主入口只调用一次。
     * 过期日志清理只会在初始化时执行一次。
     *
     * @param context 建议传 Application Context。
     * @param isShow 是否输出到 Logcat 控制台。
     * @param showStackInfo 是否在日志内容后追加调用文件、行号和方法名。
     * @param enableFile 是否写入本地日志文件。
     * @param logFilePrefix 日志文件名前缀，例如 app-2026-07-02.log。
     * @param maxFileSize 单个日志文件最大大小，超过后追加 -1、-2 等后缀滚动。
     * @param cacheDays 日志保留天数，按文件 lastModified 判断。
     * @param captureCrash 是否注册全局 UncaughtExceptionHandler 捕获崩溃日志。
     */
    fun init(
        context: Context,
        isShow: Boolean = true,
        showStackInfo: Boolean = false,
        enableFile: Boolean = true,
        logFilePrefix: String = "app",
        maxFileSize: Long = 10 * 1024 * 1024L,
        cacheDays: Int = 7,
        captureCrash: Boolean = false
    ) {
        if (initialized.getAndSet(true)) return

        this.enableConsole = isShow
        this.showStackInfo = showStackInfo
        this.enableFile = enableFile
        this.logFilePrefix = logFilePrefix
        this.maxFileSize = maxFileSize
        this.cacheDays = cacheDays

        logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        rotateLogFileIfNeeded()
        cleanOldLogs()

        if (captureCrash) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        }

        d(TAG, "ALog init success")
    }

    /** 动态开启或关闭 Logcat 控制台输出，不影响文件日志写入。 */
    fun setConsoleEnable(enable: Boolean) {
        enableConsole = enable
    }

    /** 动态开启或关闭本地文件日志，不影响 Logcat 控制台输出。 */
    fun setFileEnable(enable: Boolean) {
        enableFile = enable
    }

    /**
     * 开启或关闭 Tag 白名单过滤。
     *
     * 开启后只有通过 [addTag] 添加过的 tag 才会输出到控制台或文件。
     */
    fun enableTagFilter(enable: Boolean) {
        useTagFilter = enable
    }

    /** 添加一个允许输出的 tag，仅在 [enableTagFilter] 为 true 时生效。 */
    fun addTag(tag: String) {
        enableTags.add(tag)
    }

    /** 清空 Tag 白名单；如果过滤仍然开启，后续日志都会被过滤掉。 */
    fun clearTags() {
        enableTags.clear()
    }

    /** 输出 DEBUG 级别日志。文件日志开启时会异步写入本地文件。 */
    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg)

    /** 输出 INFO 级别日志。文件日志开启时会异步写入本地文件。 */
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg)

    /** 输出 WARN 级别日志。文件日志开启时会异步写入本地文件。 */
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg)

    /** 输出 ERROR 级别日志。文件日志开启时会异步写入本地文件。 */
    fun e(tag: String, msg: String) = log(Log.ERROR, tag, msg)

    /** 输出 ASSERT 级别日志，控制台使用 Log.wtf，文件中记录为 A/tag。 */
    fun f(tag: String, msg: String) = log(Log.ASSERT, tag, msg)

    /** 输出 WARN 日志并附带异常堆栈，适合非致命异常上报。 */
    fun w(tag: String, msg: String, throwable: Throwable) {
        log(Log.WARN, tag, "$msg\n${Log.getStackTraceString(throwable)}")
    }

    /** 输出 ERROR 日志并附带异常堆栈，适合捕获业务异常或接口失败。 */
    fun e(tag: String, msg: String, throwable: Throwable) {
        log(Log.ERROR, tag, "$msg\n${Log.getStackTraceString(throwable)}")
    }

    private fun log(priority: Int, tag: String, msg: String) {
        if (useTagFilter && !enableTags.contains(tag)) return

        val line = buildLogLine(msg)
        if (enableConsole) {
            when (priority) {
                Log.DEBUG -> Log.d(tag, line)
                Log.INFO -> Log.i(tag, line)
                Log.WARN -> Log.w(tag, line)
                Log.ERROR -> Log.e(tag, line)
                Log.ASSERT -> Log.wtf(tag, line)
            }
        }

        if (enableFile) {
            writeAsync(priority, tag, line)
        }
    }

    private fun buildLogLine(msg: String): String {
        if (!showStackInfo) return msg

        val (file, func, line) = getCallerInfo()
        return "$msg ($file:$line#$func)"
    }

    private fun getCallerInfo(): Triple<String, String, Int> {
        val stack = Thread.currentThread().stackTrace
        for (i in 5 until stack.size) {
            val e = stack[i]
            if (e.className.contains("ALog")) continue
            return Triple(e.fileName ?: "Unknown", e.methodName, e.lineNumber)
        }
        return Triple("Unknown", "Unknown", 0)
    }

    private fun writeAsync(priority: Int, tag: String, line: String) {
        executor.execute {
            writeLine(priority, tag, line)
        }
    }

    private fun writeLine(priority: Int, tag: String, line: String) {
        try {
            rotateLogFileIfNeeded()
            fileWriter?.apply {
                write("${dateFormat.format(Date())} ${levelName(priority)}/$tag: $line\n")
                flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun levelName(priority: Int): String {
        return when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> priority.toString()
        }
    }

    private fun rotateLogFileIfNeeded() {
        val date = fileDateFormat.format(Date())
        var index = 0
        var file: File

        do {
            val suffix = if (index == 0) "" else "-$index"
            file = File(logDir, "$logFilePrefix-$date$suffix.log")
            index++
        } while (file.exists() && file.length() > maxFileSize)

        if (currentLogFile?.absolutePath == file.absolutePath && fileWriter != null) {
            return
        }

        try {
            fileWriter?.close()
        } catch (_: Exception) {
        }

        currentLogFile = file
        fileWriter = try {
            FileWriter(file, true)
        } catch (_: IOException) {
            null
        }
    }

    private fun cleanOldLogs() {
        val files = logDir.listFiles() ?: return
        val expire = System.currentTimeMillis() - cacheDays * 24 * 60 * 60 * 1000L

        files.forEach {
            if (it.lastModified() < expire) {
                it.delete()
            }
        }
    }



    /**
     * 遍历日志目录中的文件并交给外部上传逻辑。
     *
     * 该方法在日志单线程队列中异步执行，避免和文件写入并发冲突。
     * 真正的网络上传逻辑由调用方在 [upload] 中实现。
     */
    fun uploadLogs(upload: (File) -> Unit) {
        executor.execute {
            val files = logDir.listFiles() ?: return@execute
            files.forEach(upload)
        }
    }

    /** 异步 flush 当前文件写入器。一般业务无需手动调用。 */
    fun flush() {
        executor.execute {
            flushInternal()
        }
    }

    /** 异步关闭当前文件写入器。关闭后再次写日志会自动重新打开当前日志文件。 */
    fun close() {
        executor.execute {
            closeInternal()
        }
    }

    /** 同步 flush 当前文件写入器，最多等待 2 秒；适合崩溃或进程退出前调用。 */
    fun flushSync() {
        runSync {
            flushInternal()
        }
    }

    /** 同步关闭当前文件写入器，最多等待 2 秒；适合崩溃或进程退出前调用。 */
    fun closeSync() {
        runSync {
            closeInternal()
        }
    }

    /** 当前是否已经初始化。 */
    fun isInitialized(): Boolean = initialized.get()

    /**
     * 获取日志目录。
     *
     * 注意：未初始化时会因为 `logDir` 尚未赋值而抛异常；不确定初始化状态时使用 [getLogDirOrNull]。
     */
    fun getLogDir(): File = logDir

    /** 安全获取日志目录，未初始化时返回 null。 */
    fun getLogDirOrNull(): File? = if (::logDir.isInitialized) logDir else null

    /** 获取当前正在写入的日志文件；未开启文件日志或初始化失败时可能为 null。 */
    fun getCurrentLogFile(): File? = currentLogFile

    /** 获取日志目录下所有 .log 文件，按修改时间倒序排列。 */
    fun getLogFiles(): List<File> {
        if (!::logDir.isInitialized) return emptyList()
        return logDir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun flushInternal() {
        try {
            fileWriter?.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeInternal() {
        try {
            fileWriter?.close()
        } catch (_: Exception) {
        } finally {
            fileWriter = null
        }
    }

    private fun runSync(block: () -> Unit) {
        if (!initialized.get()) return

        try {
            executor.submit(block).get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }
}
