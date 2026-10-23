package com.beautysdk

/** 一路视频对应一个 session。生命周期/渲染同线程；异步后端由独立 worker 管理。
 * 输出所有权由 renderer 约定：CPU 独占副本；GL 输出仅有效至下一次 render/close。
 */
class BeautySession @JvmOverloads constructor(
    factory: FaceAnalyzerFactory? = null,
    private val renderer: BeautyRenderer = RgbaBeautyRenderer(),
    private val maxResultAgeNs: Long = 200_000_000,
    private val onError: (Exception) -> Unit = {},
    /** 分析采样最小间隔（纳秒）；0 表示仅按 worker 忙闲丢帧，不限制渲染帧率。 */
    private val analysisIntervalNs: Long = 0,
    /** 仅缩小分析副本；输出视频尺寸保持不变。 */
    analysisMaxSide: Int = 320,
) : FrameProcessor {
    private val lifecycle = BeautyProcessor()
    private val analysis = factory?.let { AsyncFaceAnalysis(it, maxSide = analysisMaxSide, onError = onError) }
    private var closed = false
    private var geometry: FrameInfo? = null
    private var lastAcceptedAnalysisNs = -1L
    private var lastOfferedAnalysisNs = -1L
    @Volatile var settings = BeautySettings()
    init { require(maxResultAgeNs >= 0 && analysisIntervalNs >= 0); require(analysisMaxSide in 16..2048) }
    override fun start() = lifecycle.start()
    override fun process(frame: VideoFrame): VideoFrame {
        lifecycle.process(frame)
        updateGeometry(frame.info)
        if (frame is RgbaFrame) offerAnalysis(frame)
        val face = analysis?.latest(frame.info, maxResultAgeNs)?.faces?.firstOrNull()
        return try { renderer.render(frame, settings, face) } catch (error: Exception) {
            try { onError(error) } catch (_: Exception) { }
            frame
        }
    }
    /** 纹理模式由宿主提供与逻辑纹理画面一致的 RGBA 分析图，避免 SDK 隐式 GPU 读回。
     * 与 process 在同一线程按相同时间线调用；分析图 FrameInfo 必须描述原输出尺寸。
     * 如宿主已缩图，应改用原尺寸 RGBA 输入或外部 FaceAnalyzer，不伪造尺寸。
     */
    fun submitAnalysis(frame: RgbaFrame): Boolean {
        lifecycleCheck()
        // 先登记几何信息，防止紧随其后的纹理帧把刚提交的新方向结果再次清空。
        updateGeometry(frame.info)
        return offerAnalysis(frame)
    }
    private fun updateGeometry(info: FrameInfo) {
        if (geometry?.sameGeometry(info) == false) {
            analysis?.reset()
            lastAcceptedAnalysisNs = -1L
            lastOfferedAnalysisNs = -1L
        }
        geometry = info
    }
    private fun offerAnalysis(frame: RgbaFrame): Boolean {
        val worker = analysis ?: return false
        val timestamp = frame.info.timestampNs
        // 同一时刻的 RGBA 分析图允许先于纹理渲染提交，避免重复分析。
        if (timestamp <= lastOfferedAnalysisNs) return false
        lastOfferedAnalysisNs = timestamp
        if (lastAcceptedAnalysisNs >= 0 && timestamp - lastAcceptedAnalysisNs < analysisIntervalNs) return false
        return worker.submit(frame).also { accepted -> if (accepted) lastAcceptedAnalysisNs = timestamp }
    }
    private fun lifecycleCheck() { lifecycle.requireRunning() }
    /** 只返回同方向、同镜像且未过期的结果；无脸或分析失败时返回空结果或 null。 */
    fun latestFaces(info: FrameInfo): FaceResult? = analysis?.latest(info, maxResultAgeNs)
    override fun stop() {
        lifecycle.stop(); analysis?.reset(); geometry = null
        lastAcceptedAnalysisNs = -1L; lastOfferedAnalysisNs = -1L
    }
    /** 切摄像头时 stop/start，避免同尺寸摄像头沿用旧结果。 */
    override fun close() {
        lifecycle.close()
        if (!closed) {
            closed = true
            analysis?.close()
            renderer.close()
        }
    }
}
