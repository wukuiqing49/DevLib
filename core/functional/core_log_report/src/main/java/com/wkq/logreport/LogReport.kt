package com.wkq.logreport

import android.app.*
import android.os.*
import android.content.*
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 可独立接入的用户主动日志收集器；所有文件位于私有目录。 */
object LogReport {
    private lateinit var app: Application
    private lateinit var root: File
    private val lock = Any()
    @Volatile private var current: File? = null
    @Volatile private var phase = ReportPhase.IDLE
    @Volatile private var pending: File? = null
    @Volatile private var foreground = false
    private var watchdog: MainStallWatchdog? = null
    /** IO线程刷新的封存索引，供页面无磁盘读取地展示。 */
    private data class SavedReport(val folder: File, val entry: ReportEntry)
    @Volatile private var savedReports = emptyList<SavedReport>()
    private var logcat: java.lang.Process? = null
    private val ui = Handler(Looper.getMainLooper())
    private val mutablePageState = androidx.lifecycle.MutableLiveData(ReportPageState())
    internal val pageState: androidx.lifecycle.LiveData<ReportPageState> = mutablePageState
    @Volatile private var historyLoading = true
    @Volatile private var historyError = false
    @Volatile private var operationError = false
    private var resumedActivity = java.lang.ref.WeakReference<Activity>(null)
    private val floating = FloatingReportIndicator({ show(it) }, { event("capture_gap", "floating_indicator_unavailable") })
    private val crashing = java.util.concurrent.atomic.AtomicBoolean()
    private val writer = ReportWriter()
    private var email = ""
    private var hostBuildId = ""
    private var cameraProvider: (() -> List<CameraLogInfo>)? = null
    private val captures = java.util.concurrent.ConcurrentHashMap<String, CaptureState>()

    /** 每个采集会话独立保存计数，旧任务不能污染新会话。 */
    private class CaptureState {
        var stallEvidence: StallEvidence? = null
        val recent = java.util.ArrayDeque<String>()
        val received = java.util.concurrent.atomic.AtomicLong()
        val written = java.util.concurrent.atomic.AtomicLong()
        val dropped = java.util.concurrent.atomic.AtomicLong()
        val checkpointErrors = java.util.concurrent.atomic.AtomicLong()
        @Volatile var stopRequestedAt = 0L
        @Volatile var rotations = 0
        @Volatile var logcat = "not_started"
    }

    /** 初始化观察器及崩溃链；邮箱由宿主配置。 */
    fun install(application: Application, supportEmail: String = "", buildId: String = "", cameras: (() -> List<CameraLogInfo>)? = null) {
        if (::app.isInitialized) return
        app = application
        email = supportEmail
        hostBuildId = buildId
        cameraProvider = cameras
        root = File(app.noBackupFilesDir, "log-reports")
        reloadHistory()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                if (crashing.compareAndSet(false, true)) (current ?: pending)?.let { folder ->
                    val crashText = "threadId=" + thread.id + "\n" + stack(failure)
                    val flush = Thread({
                        runCatching {
                            ReportFiles.atomic(File(folder, "crash.txt"), crashText)
                            checkpoint(folder, "crashed", false, "crash-capture.json")
                        }
                    }, "LogReport-crash").apply { isDaemon = true; start() }
                    flush.join(300)
                }
            } catch (_: Throwable) { /* 崩溃写入失败继续原处理。 */ }
            finally { previous?.uncaughtException(thread, failure) }
        }
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            /** 系统配置变化后刷新采集提示，沿用宿主主题与窗口。 */
            override fun onConfigurationChanged(config: android.content.res.Configuration) { refresh() }
            /** 内存紧张时不额外分配日志快照，保留已有文件。 */
            override fun onLowMemory() {}
        })
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            /** 恢复前台状态，记录页面并安装窗口提示与触摸观察。 */
            override fun onActivityResumed(activity: Activity) { foreground = true; resumedActivity = java.lang.ref.WeakReference(activity); event("page", activity.javaClass.name); attach(activity); observeActions(activity) }
            /** 注册Fragment页面观察，不持有具体页面或输入内容。 */
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                    /** 只记录恢复显示的Fragment类名，不读取页面参数。 */
                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) { event("page", f.javaClass.name) }
                }, true)
            }
            /** 停止前台卡顿检测并记录离开页面。 */
            override fun onActivityPaused(activity: Activity) { foreground = false; floating.hide(activity); if (resumedActivity.get() === activity) resumedActivity.clear(); event("page_leave", activity.javaClass.name) }
            /** 前台判断统一由恢复回调负责，此处不重复记录。 */
            override fun onActivityStarted(activity: Activity) {}
            /** 页面停止不直接封存用户仍在进行的采集。 */
            override fun onActivityStopped(activity: Activity) {}
            /** 不把页面保存状态和潜在用户输入写入日志。 */
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            /** 页面销毁后移除对应悬浮窗口和监听。 */
            override fun onActivityDestroyed(activity: Activity) { floating.hide(activity) }
        })
    }

    /** 仅接收稳定标识和状态枚举，避免录入自由文本。 */
    fun event(kind: String, code: String) {
        synchronized(lock) {
            val folder = current ?: return
            enqueue(folder, JSONObject().put("kind", safe(kind)).put("code", if (kind == "logcat") LogPrivacy.redact(code) else safe(code)))
        }
    }

    /** 返回当前采集编号，供宿主区分不同会话的持久任务更新。 */
    fun sessionId(): String? = synchronized(lock) { current?.name }

    /** 开始可跨协程传递的操作，固定本次采集身份。 */
    fun operation(name: String): ReportOperation {
        val folder = synchronized(lock) { current }
        return ReportOperation(if (folder == null) "" else UUID.randomUUID().toString(), safe(name)) { update ->
            synchronized(lock) {
                if (folder != null && current === folder) {
                    enqueue(folder, JSONObject().put("kind", "operation").put("code", safe(update.stage))
                        .put("operationId", update.id).put("operation", safe(update.name)).put("outcome", update.outcome)
                        .put("durationMs", update.elapsedMs).apply { update.count?.let { put("count", it) }; update.errorStack?.let { put("errorStack", it) } })
                }
            }
        }
    }

    /** 补充真实相机环境；只收型号、固件与协议，不接收序列号或地址。 */
    fun cameraEnvironment(model: String, firmware: String, protocol: String) {
        synchronized(lock) {
            val folder = current ?: return
            val value = JSONObject().put("model", safe(model)).put("firmware", safe(firmware)).put("protocol", safe(protocol))
            enqueue(folder, JSONObject().put("kind", "camera_environment").put("code", value.toString()).put("camera", value))
        }
    }

    /** 对标识字段限制长度与字符，日志自由文本走独立脱敏规则。 */
    private fun safe(value: String): String = value.take(200).replace(Regex("[^A-Za-z0-9_.:-]"), "_")

    /** 调用线程生成时间与序号；写盘队列只承担有界IO。 */
    private fun enqueue(folder: File, value: JSONObject) {
        val capture = captures[folder.name] ?: return
        if (value.optString("kind") in setOf("page", "navigation", "control", "business", "operation")) {
            capture.recent.addLast(value.optString("kind") + " " + safe(value.optString("operation")) + " " + safe(value.optString("code")) + " " + safe(value.optString("operationId")))
            while (capture.recent.size > 32) capture.recent.removeFirst()
        }
        val sequence = capture.received.incrementAndGet()
        value.put("sequence", sequence).put("session", folder.name).put("time", System.currentTimeMillis()).put("elapsed", SystemClock.elapsedRealtime())
        val accepted = writer.offer({
            val file = File(folder, "events.jsonl")
            if (file.length() > 2 * 1024 * 1024) {
                check(file.renameTo(File(folder, "events.previous.jsonl").also { if (it.exists()) check(it.delete()) }))
                capture.rotations++
            }
            file.appendText(value.toString() + "\n")
            capture.written.incrementAndGet()
            if (sequence % 32L == 0L) runCatching { checkpoint(folder, "collecting", false) }.onFailure { capture.checkpointErrors.incrementAndGet() }
        }, { capture.dropped.incrementAndGet() })
        if (!accepted) capture.dropped.incrementAndGet()
    }

    /** 原子提交采集检查点；崩溃计数仅为最后已知值，不能声称完整。 */
    private fun checkpoint(folder: File, state: String, exact: Boolean, fileName: String = "capture.json") {
        val capture = captures[folder.name] ?: return
        val value = JSONObject().put("state", state).put("exact", exact).put("received", capture.received.get())
            .put("written", capture.written.get()).put("dropped", capture.dropped.get()).put("rotations", capture.rotations)
            .put("stopRequestedAt", capture.stopRequestedAt).put("checkpointErrors", capture.checkpointErrors.get()).put("logcat", capture.logcat).put("checkpointTime", System.currentTimeMillis())
        ReportFiles.atomic(File(folder, fileName), value.toString())
    }

    /** 记录经过脱敏的异常类型与栈帧。 */
    fun exception(operation: String, failure: Throwable) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.size < 4 && seen.add(cause)) {
            event("exception", operation + ":" + cause.javaClass.name)
            cause.stackTrace.take(12).forEach { event("frame", it.className + "." + it.methodName + ":" + it.lineNumber) }
            cause = cause.cause
        }
    }

    /** 以不包含异常消息的栈帧记录崩溃。 */
    private fun stack(failure: Throwable): String = CrashStack.render(failure)

    /** 尽力读取当前进程日志，权限或设备不支持时明确记录缺口。 */
    private fun captureLogcat() {
        val folder = synchronized(lock) { current } ?: return
        Thread {
            try {
                val process = ProcessBuilder("logcat", "--pid=" + android.os.Process.myPid(), "-T", "1", "-v", "threadtime").redirectErrorStream(true).start()
                synchronized(lock) {
                    if (current !== folder) { process.destroy(); return@Thread }
                    logcat = process
                    captures[folder.name]?.logcat = "running"
                    writer.control({ checkpoint(folder, "collecting", false) })
                }
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) { if (current === folder) event("logcat", line) }
                    }
                }
                if (synchronized(lock) { current === folder }) {
                    synchronized(lock) { captures[folder.name]?.logcat = "ended_early" }
                    event("capture_gap", "logcat_ended")
                }
            } catch (_: Exception) {
                synchronized(lock) { if (current === folder) { captures[folder.name]?.logcat = "unavailable"; event("capture_gap", "logcat_unavailable") } }
            }
        }.apply { isDaemon = true; name = "LogReport-logcat" }.start()
    }

    private val observed = java.util.WeakHashMap<Activity, Boolean>()
    /** 记录点击的控件资源名，不读取控件文本及输入值。 */
    private fun observeActions(activity: Activity) {
        if (observed.put(activity, true) == true) return
        val original = activity.window.callback
        activity.window.callback = java.lang.reflect.Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, method, arguments ->
            if (method.name == "onWindowFocusChanged" && arguments?.firstOrNull() == false) {
                ui.post { if (resumedActivity.get() === activity) floating.raise(activity) }
            }
            if (method.name == "dispatchTouchEvent") {
                val touch = arguments?.firstOrNull() as? android.view.MotionEvent
                if (touch?.action == android.view.MotionEvent.ACTION_UP) {
                    hit(activity.window.decorView, touch.rawX.toInt(), touch.rawY.toInt())?.let {
                        if (it.id != View.NO_ID) runCatching { event("control", it.resources.getResourceEntryName(it.id)) }
                    }
                }
            }
            try { method.invoke(original, *(arguments ?: emptyArray())) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        } as Window.Callback
    }

    /** 按绘制顺序寻找触点下的可点击控件。 */
    private fun hit(view: View, x: Int, y: Int): View? {
        if (view.visibility != View.VISIBLE) return null
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return null
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) hit(view.getChildAt(index), x, y)?.let { return it }
        return view.takeIf { it.isClickable }
    }

    /** 打开独立日志管理页面；已在本页时不叠加Activity。 */
    fun show(activity: Activity) {
        if (activity is LogReportActivity) return
        activity.startActivity(Intent(activity, LogReportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** 开关只改变全局采集；离开页面与进程重建不会误启动新记录。 */
    internal fun setCollecting(enabled: Boolean) {
        if (enabled) start() else stopCollection()
    }

    /** 用户明确重试当前整理，不重放任何业务操作。 */
    internal fun retryFinalization() { if (phase == ReportPhase.FAILED) finalizeReport() }

    /** 开始前在IO队列准备目录与元数据，完成后再接受业务事件。 */
    private fun start() {
        synchronized(lock) { if (phase != ReportPhase.IDLE || historyLoading || historyError) return; phase = ReportPhase.STARTING; operationError = false }
        refresh()
        val cameraSnapshot = runCatching { cameraProvider?.invoke() }
        writer.control({
            check(root.mkdirs() || root.isDirectory)
            check(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 64L * 1024 * 1024)
            val folder = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
            ReportFiles.atomic(File(folder, "active"), System.currentTimeMillis().toString())
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            ReportFiles.atomic(File(folder, "environment.json"), JSONObject().put("formatVersion", 3).put("session", folder.name)
                .put("package", app.packageName).put("version", info.versionName).put("versionCode", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
                .put("buildId", hostBuildId.ifBlank { "not_configured" }).put("installedUpdateTime", info.lastUpdateTime)
                .put("android", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("systemBuildFingerprint", Build.FINGERPRINT.take(512)).put("securityPatch", Build.VERSION.SECURITY_PATCH)
                .put("supportedAbis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("process", android.os.Process.myPid()).put("startedAt", System.currentTimeMillis()).put("timeZone", java.util.TimeZone.getDefault().id).toString())
            captures[folder.name] = CaptureState().apply { stallEvidence = StallEvidence(folder) }
            checkpoint(folder, "collecting", false)
            synchronized(lock) { current = folder; phase = ReportPhase.RECORDING }
            event("collection", "start")
            val cameras = cameraSnapshot.getOrNull()
            when {
                cameraSnapshot.isFailure -> event("capture_gap", "camera_environment_unavailable")
                cameras == null -> event("capture_gap", "camera_provider_not_configured")
                cameras.isEmpty() -> event("camera_status", "none_connected_at_start")
                else -> cameras.forEach { cameraEnvironment(it.model, it.firmware, it.protocol) }
            }
            captureLogcat()
            val monitor = MainStallWatchdog({ current === folder && foreground }) { observation, frames ->
                synchronized(lock) {
                    if (current === folder) enqueue(folder, JSONObject().put("kind", if (observation.recovered) "stall_recovered" else "stall")
                        .put("code", if (observation.recovered) "main_thread_recovered" else "app_observed_main_thread_stall")
                        .put("stallId", observation.beat).put("sample", observation.sample).put("durationMs", observation.durationMs).put("errorStack", frames))
                }
                if (!observation.recovered && current === folder) {
                    val capture = captures[folder.name]
                    runCatching {
                        val runtime = Runtime.getRuntime()
                        val recent = synchronized(lock) { capture?.recent?.toList().orEmpty().joinToString("\n") }
                        val context = "javaHeapUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} javaHeapMaxBytes=${runtime.maxMemory()}\n" +
                            "nativeHeapBytes=${Debug.getNativeHeapAllocatedSize()} processCpuTimeMs=${android.os.Process.getElapsedCpuTime()}\n" +
                            "Recent operations / 最近操作\n$recent\n"
                        capture?.stallEvidence?.record(observation, context + ThreadSnapshot.capture(Looper.getMainLooper().thread))
                    }.onFailure { event("capture_gap", "stall_snapshot_write_failed") }
                }
            }
            synchronized(lock) { if (current === folder) watchdog = monitor else monitor.close() }
            refresh()
        }, { synchronized(lock) { phase = ReportPhase.IDLE; operationError = true }; refresh() })
    }

    /** 启动或手动重试读取磁盘历史；活动采集和正在整理的目录不被恢复或删除。 */
    internal fun reloadHistory() {
        historyLoading = true; historyError = false; refresh()
        writer.control({
            check(root.mkdirs() || root.isDirectory)
            root.listFiles().orEmpty().filter { File(it, "active").exists() && it != current && it != pending }.forEach { folder ->
                java.nio.file.Files.move(File(folder, "active").toPath(), File(folder, "interrupted").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                File(folder, "interrupted").setLastModified(System.currentTimeMillis())
            }
            refreshReports()
        }, { historyLoading = false; historyError = true; refresh() })
    }

    /** 仅在IO线程构造封存索引；原始历史不再自动按七天删除。 */
    private fun refreshReports() {
        savedReports = root.listFiles().orEmpty().filter { it.isDirectory && !File(it, "active").exists() }
            .map { folder ->
                val marker = File(folder, if (File(folder, "finished").exists()) "finished" else "interrupted")
                val environment = runCatching { JSONObject(File(folder, "environment.json").readText()) }.getOrNull()
                val time = environment?.optLong("startedAt")?.takeIf { it > 0 } ?: marker.readTextOrNull()?.toLongOrNull() ?: marker.lastModified()
                val size = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                SavedReport(folder, ReportEntry(folder.name, time, size, File(folder, "interrupted").exists(), File(folder, "crash.txt").exists() || File(folder, "native-trace.json").exists()))
            }.sortedByDescending { it.entry.time }
        historyLoading = false; historyError = false; refresh()
    }

    /** 兼容旧版本缺少标记内容的封存记录。 */
    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    /** 撤销新事件入口后排空已接受事件；UI保留整理提示直到真正封存。 */
    private fun stopCollection() {
        synchronized(lock) {
            if (phase != ReportPhase.RECORDING) return
            pending = current
            current?.let { captures[it.name]?.stopRequestedAt = System.currentTimeMillis() }
            current = null
            phase = ReportPhase.FINALIZING
        }
        watchdog?.close(); watchdog = null
        refresh()
        finalizeReport()
    }

    /** 封存可重试，失败不删除日志或active标记，也不阻断后续控制队列。 */
    private fun finalizeReport() {
        val folder = synchronized(lock) {
            val target = pending ?: return
            phase = ReportPhase.FINALIZING
            target
        }
        refresh()
        writer.control({
            logcat?.destroy(); logcat = null
            captures[folder.name]?.let { if (it.logcat == "running") it.logcat = "stopped" }
            captures[folder.name]?.stallEvidence?.close()
            ReportFiles.seal(folder) {
                checkpoint(folder, "finished", true)
                ReportFiles.atomic(File(folder, "collection.txt"), "dropped=" + (captures[folder.name]?.dropped?.get() ?: "unknown"))
            }
            File(folder, "finish-error.json").delete()
            captures.remove(folder.name)
            refreshReports()
            synchronized(lock) { pending = null; phase = ReportPhase.IDLE }
            refresh()

        }, { error ->
            runCatching { ReportFiles.atomic(File(folder, "finish-error.json"), JSONObject().put("type", error.javaClass.simpleName).put("retryable", true).toString()) }
            synchronized(lock) { phase = ReportPhase.FAILED }
            refresh()

        })
    }

    /** 详情按报告ID异步生成附件，回调仅携带数据，不持有页面窗口。 */
    internal fun prepareAttachment(id: String, complete: (Result<ReportAttachment>) -> Unit) {
        writer.control({
            val result = runCatching {
                val folder = savedReports.firstOrNull { it.entry.id == id }?.folder ?: error("Report missing")
                check(folder.isDirectory && !File(folder, "active").exists())
                buildAttachment(folder).also { refreshReports() }
            }
            ui.post { complete(result) }
        }, { error -> ui.post { complete(Result.failure(error)) } })
    }

    /** 删除用户明确选中的封存记录及缓存；任何活动目录都不可从历史删除。 */
    internal fun deleteReport(id: String, complete: (Boolean) -> Unit) {
        writer.control({
            val folder = savedReports.firstOrNull { it.entry.id == id }?.folder ?: error("Report missing")
            check(folder != current && folder != pending && !File(folder, "active").exists())
            File(app.cacheDir, "log-report-export/$id.zip").takeIf { it.exists() }?.let { check(it.delete()) }
            check(folder.deleteRecursively())
            refreshReports()
            ui.post { complete(true) }
        }, { ui.post { complete(false) } })
    }

    /** 生成包含可得系统证据的ZIP；仅IO线程调用，缓存失败不删除原始历史。 */
    private fun buildAttachment(folder: File): ReportAttachment {
        if (Build.VERSION.SDK_INT >= 30) {
            val start = folder.listFiles()?.filter { it.name == "finished" || it.name == "interrupted" }?.firstOrNull()?.readText()?.toLongOrNull() ?: Long.MAX_VALUE
            val savedStop = runCatching { JSONObject(File(folder, "capture.json").readText()).optLong("stopRequestedAt") }.getOrDefault(0)
            val end = if (savedStop > 0) savedStop else folder.listFiles()?.firstOrNull { it.name == "finished" || it.name == "interrupted" }?.lastModified() ?: System.currentTimeMillis()
            val exits = runCatching { app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 8).filter { it.timestamp in start..end } }.getOrDefault(emptyList())
            val systemFile = File(folder, "system.txt")
            if (exits.isNotEmpty() || !systemFile.exists()) systemFile.writeText(exits.joinToString("\n") { "time=" + it.timestamp + " reason=" + it.reason + " status=" + it.status })
            val traces = exits.filter { it.reason != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE }.mapNotNull { systemTrace(it) }
            if (traces.isNotEmpty()) File(folder, "system-trace.txt").writeText(traces.joinToString("\n"))
            if (Build.VERSION.SDK_INT >= 31) exits.filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }.firstOrNull()?.let { nativeExit ->
                runCatching {
                    val stream = nativeExit.traceInputStream ?: error("Native trace unavailable")
                    val data = stream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            require(output.size() + size <= 2 * 1024 * 1024)
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    }
                    val native = NativeTombstone.parse(data)
                    check(native.frames.isNotEmpty()) { "Native frames unavailable" }
                    val frames = org.json.JSONArray()
                    native.frames.forEach { frame -> frames.put(JSONObject().put("module", frame.module).put("relativePc", java.lang.Long.toHexString(frame.relativePc)).put("buildId", frame.buildId).put("function", frame.function).put("functionOffset", java.lang.Long.toHexString(frame.functionOffset))) }
                    ReportFiles.atomic(File(folder, "native-trace.json"), JSONObject().put("formatVersion", 1).put("architecture", native.architecture).put("signal", native.signal).put("thread", native.thread).put("frames", frames).toString())
                    ReportFiles.atomic(File(folder, "native-trace.txt"), native.text())
                    File(folder, "native-trace-error.txt").delete()
                }.onFailure { error -> ReportFiles.atomic(File(folder, "native-trace-error.txt"), "native_trace_unavailable type=" + error.javaClass.simpleName) }
            }

        }
        writeSummary(folder)
        val dir = File(app.cacheDir, "log-report-export").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 86400000 }?.forEach { it.delete() }
        val zip = File(dir, folder.name + ".zip")
        val temp = File(dir, folder.name + ".tmp")
        ZipOutputStream(temp.outputStream()).use { output ->
            folder.listFiles()?.filter { it.isFile && it.extension != "tmp" }?.sortedBy { it.name }?.forEach { file ->
                output.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
            }
        }
        if (zip.exists()) check(zip.delete())
        check(temp.renameTo(zip))
        return ReportAttachment(folder.name, zip, File(folder, "summary.txt").readText().take(128 * 1024), zip.length())
    }

    /** 使用已检查的附件构造系统分享，由用户选择接收端后发送；不宣称已经上传。 */
    internal fun shareIntent(attachment: ReportAttachment, mail: Boolean): Intent {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".logreport", attachment.file)
        return Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, app.packageName + " log report " + attachment.id)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData.newRawUri("report", uri)
                if (mail && email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            }
    }

    /** 从两段日志和旧版附件生成可读摘要，明确记录损坏及缺失证据。 */
    private fun writeSummary(folder: File) {
        var invalid = 0
        val events = mutableListOf<SummaryEvent>()
        listOf("events.previous.jsonl", "events.jsonl").map { File(folder, it) }.filter(File::exists).forEach { file ->
            file.bufferedReader().useLines { lines -> lines.forEach { line ->
                runCatching {
                    val value = JSONObject(line)
                    events += SummaryEvent(value.optLong("time"), value.optString("kind"), value.optString("code"), value.optString("operationId"), value.optString("operation"), value.optString("outcome"), value.optLong("durationMs"), if (value.has("count")) value.getInt("count") else null, value.optString("errorStack"))
                }.onFailure { invalid++ }
            } }
        }
        val environment = runCatching { JSONObject(File(folder, "environment.json").readText()) }.getOrDefault(JSONObject())
        val captureFile = File(folder, if (File(folder, "crash-capture.json").exists()) "crash-capture.json" else "capture.json")
        val capture = runCatching { JSONObject(captureFile.readText()) }.getOrNull()
        val state = if (File(folder, "finished").exists()) "finished / 正常结束" else "interrupted / 中断"
        val gaps = mutableListOf("Logcat scope / 日志范围 = own process only / 仅本应用进程", "Counter precision / 精确计数仅针对本采集队列")
        gaps += "Counters / 计数 = " + (capture?.toString() ?: "unknown / 旧记录或检查点缺失")
        if (capture?.optBoolean("exact") != true) gaps += "Incomplete checkpoint / 队列及丢失数量仅为最后已知值，完整性未确认"
        if ((capture?.optInt("rotations") ?: 0) > 1) gaps += "Earlier events overwritten / 较早事件已被滚动覆盖"
        if (capture == null && File(folder, "events.previous.jsonl").exists()) gaps += "Legacy rotation coverage unknown / 旧版滚动覆盖范围未知"
        gaps += "System ANR trace / 系统线程栈 = " + if (File(folder, "system-trace.txt").exists()) "available / 已取得" else "not provided / 未取得"
        gaps += "Native stack / 原生栈 = " + if (File(folder, "native-trace.json").exists()) "module/PC/BuildId available for offline symbols / 已有离线符号化信息" else "not provided / 未取得"
        gaps += "Native symbolized source / 原生源码定位 = run offline symbol tool / 由离线工具核对符号"
        File(folder, "native-trace-error.txt").takeIf(File::exists)?.let { gaps += it.readText() }
        File(folder, "finish-error.json").takeIf(File::exists)?.let { gaps += "Finalization failure / 整理失败记录 = " + it.readText() }
        val snapshots = folder.listFiles().orEmpty().filter { it.name.startsWith("main-stall-") && it.extension == "txt" }.sortedBy { it.lastModified() }
        gaps += "Proactive stall snapshots / 主动保存的卡顿现场 = ${snapshots.size} (app-observed; not system ANR)"
        snapshots.forEach { file -> gaps += file.name + "\n" + file.readText().lineSequence().take(16).joinToString("\n") }
        val cameras = events.filter { it.kind == "camera_environment" }.map { it.code }.distinct()
        gaps += "Camera context / 相机型号固件协议 = " + cameras.ifEmpty { listOf(if (events.any { it.kind == "camera_status" && it.code == "none_connected_at_start" }) "none connected at start / 开始时没有已连接相机" else "not recorded / 未记录") }.joinToString()
        val env = listOf("package", "version", "versionCode", "buildId", "model", "android", "timeZone", "securityPatch", "systemBuildFingerprint").joinToString { it + "=" + environment.optString(it, "unknown") }
        val system = File(folder, "system.txt").takeIf(File::exists)?.readText().orEmpty()
        File(folder, "native-trace.txt").takeIf(File::exists)?.let { gaps += "Native frames / 原生帧\n" + it.readText().lineSequence().take(25).joinToString("\n") }
        val crash = File(folder, "crash.txt").takeIf(File::exists)?.readText().orEmpty()
        File(folder, "summary.txt").writeText(ReportSummary.render(folder.name, env, state, events, gaps, system, crash, invalid))
    }

    /** 读取系统保留的ANR文本栈，仅导出栈帧，限制读取大小。 */
    @androidx.annotation.RequiresApi(30)
    private fun systemTrace(exit: android.app.ApplicationExitInfo): String? = runCatching {
        val bytes = ByteArray(128 * 1024)
        val count = exit.traceInputStream?.use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        } ?: return@runCatching null
        val frames = SystemTraceSummary.frames(String(bytes, 0, count, Charsets.UTF_8))
        if (frames.isBlank()) null else "system_trace time=" + exit.timestamp + " exit_reason=" + exit.reason + "\n" + frames
    }.getOrNull()

    /** 将采集模式显示为当前前台窗口的可拖动悬浮状态，不占用任何页面布局。 */
    private fun attach(activity: Activity) {
        if (phase == ReportPhase.IDLE || activity is LogReportActivity || activity is LogReportDetailActivity) { floating.detach(); return }
        val label = when (phase) {
            ReportPhase.STARTING -> R.string.log_report_starting
            ReportPhase.FINALIZING -> R.string.log_report_preparing
            ReportPhase.FAILED -> R.string.log_report_retry
            else -> R.string.log_report_active
        }
        floating.show(activity, label, phase == ReportPhase.STARTING || phase == ReportPhase.FINALIZING)
    }

    /** 主线程刷新当前应用悬浮状态；后台不创建窗口。 */
    private fun refresh() { ui.post {
        mutablePageState.value = ReportPageState(phase, historyLoading, historyError, operationError, savedReports.map { it.entry })
        resumedActivity.get()?.let { attach(it) }
    } }

}
