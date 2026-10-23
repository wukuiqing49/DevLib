package com.beautysdk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 生命周期/控制方法在主线程调用。只解绑自己创建的 use case，不影响其他相机使用者。
 * 宿主声明并申请 CAMERA；本模块不申请权限、不持有 Activity、不自行采集音频或存储图像。
 * PreviewView 自动处理预览旋转/前摄镜像；帧输出保留原始像素并附带旋转镜像信息。
 */
class CustomCameraController @JvmOverloads constructor(
    context: Context,
    private val listener: CameraFrameListener,
    private val onStatus: (CameraStatus) -> Unit = {},
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"beauty-camera-frames") }
    private val binding = AtomicLong()
    private val epoch = AtomicLong()
    @Volatile private var active = false
    @Volatile private var closed = false
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private var config = CameraConfig()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var cameraState: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { epoch.incrementAndGet(); active = true }
            Lifecycle.Event.ON_STOP -> { active = false; epoch.incrementAndGet(); endStream() }
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    fun start(owner: LifecycleOwner, previewView: PreviewView, config: CameraConfig = CameraConfig()) {
        checkMain(); check(!closed) { "Camera is closed" }
        require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Lifecycle is destroyed" }
        stop()
        if (ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail(SecurityException("Host must grant CAMERA permission")); return
        }
        this.owner = owner; view = previewView; this.config = config
        active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        owner.lifecycle.addObserver(lifecycleObserver)
        val token = binding.incrementAndGet()
        epoch.incrementAndGet()
        onStatus(CameraStatus.STARTING)
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (closed || binding.get() != token) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = selector(config.lens)
                check(cameraProvider.hasCamera(selector)) { "Requested camera is unavailable: ${config.lens}" }
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(config.width,config.height),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val p = Preview.Builder().setTargetRotation(rotation).setResolutionSelector(resolution).build()
                val a = ImageAnalysis.Builder().setTargetRotation(rotation).setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                preview = p; analysis = a
                var lastAnalysisNs = 0L
                var lastCameraTimestamp = -1L
                a.setAnalyzer(worker) { image ->
                    val generation = epoch.get()
                    try {
                        if (closed || binding.get() != token || !active) return@setAnalyzer
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now-lastAnalysisNs < 1_000_000_000L/config.maxAnalysisFps) return@setAnalyzer
                        if (image.imageInfo.timestamp <= lastCameraTimestamp) return@setAnalyzer
                        lastAnalysisNs = now; lastCameraTimestamp = image.imageInfo.timestamp
                        val plane = image.planes.single()
                        val crop = image.cropRect
                        val pixels = RgbaPacking.copy(plane.buffer,plane.rowStride,plane.pixelStride,
                            crop.left,crop.top,crop.width(),crop.height())
                        val frame = CameraFrame(crop.width(),crop.height(),image.imageInfo.timestamp,
                            image.imageInfo.rotationDegrees,config.lens,generation,pixels)
                        if (!closed && active && binding.get() == token && epoch.get() == generation) listener.onFrame(frame)
                    } catch (error: Exception) {
                        main.execute { if (!closed && binding.get() == token && epoch.get() == generation) onError(error) }
                    } finally { image.close() }
                }
                p.setSurfaceProvider(previewView.surfaceProvider)
                val camera = cameraProvider.bindToLifecycle(owner,selector,p,a)
                val observer = Observer<CameraState> { state ->
                    if (!closed && binding.get() == token && state.error != null)
                        fail(IllegalStateException("Camera state error: ${state.error!!.code}",state.error!!.cause))
                }
                cameraState = camera.cameraInfo.cameraState; cameraObserver = observer
                cameraState!!.observe(owner,observer)
                if (binding.get() == token && !closed) onStatus(CameraStatus.BOUND)
            } catch (error: Exception) {
                if (binding.get() == token && !closed) { stop(); fail(error) }
            }
        },main)
    }

    fun switchCamera() {
        checkMain(); check(!closed)
        val currentOwner = owner ?: return
        val previewView = view ?: return
        val next = if (config.lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
        val available = provider?.hasCamera(selector(next)) ?: false
        if (!available) { onError(IllegalStateException("Requested camera is unavailable: $next")); return }
        start(currentOwner,previewView,config.copy(lens = next))
    }

    /** 可跨线程检查异步消费者是否仍属于当前活动会话。 */
    fun isCurrent(frame: CameraFrame): Boolean = !closed && active && frame.sessionId == epoch.get()

    fun stop() {
        checkMain()
        if (closed) return
        active = false; binding.incrementAndGet(); epoch.incrementAndGet()
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null; view = null
        cameraObserver?.let { cameraState?.removeObserver(it) }
        cameraObserver = null; cameraState = null
        analysis?.clearAnalyzer()
        val owned = listOfNotNull(preview,analysis).toTypedArray()
        if (owned.isNotEmpty()) provider?.unbind(*owned)
        preview = null; analysis = null
        endStream()
        onStatus(CameraStatus.STOPPED)
    }
    private fun endStream() {
        worker.execute {
            try { listener.onStreamStopped() } catch (error: Exception) {
                main.execute { if (!closed) onError(error) }
            }
        }
    }
    private fun fail(error: Exception) { onStatus(CameraStatus.ERROR); onError(error) }
    private fun selector(lens: CameraLens) = if (lens == CameraLens.FRONT)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Camera control requires the main thread" } }
    override fun close() {
        checkMain()
        if (!closed) { stop(); closed = true; worker.shutdown() }
    }
}
