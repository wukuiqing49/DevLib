package com.beautysdk

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** 最多一张正在分析的图像，无待处理队列；忙时在复制之前丢帧。 */
class AsyncFaceAnalysis @JvmOverloads constructor(
    private val factory: FaceAnalyzerFactory,
    private val maxSide: Int = 320,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "beauty-face-analysis") }
    private var analyzer: FaceAnalyzer? = null
    private var analyzerGeneration = -1L
    private var busy = false
    private var closed = false
    private var generation = 0L
    private var geometry: FrameInfo? = null
    private var submittedNs = -1L
    private var result: FaceResult? = null

    init { require(maxSide in 16..2048) }

    fun submit(frame: RgbaFrame): Boolean = synchronized(lock) {
        check(!closed) { "Analysis is closed" }
        if (geometry?.sameGeometry(frame.info) == false) resetLocked()
        geometry = frame.info
        require(frame.info.timestampNs > submittedNs) { "Analysis timestamps must strictly increase" }
        submittedNs = frame.info.timestampNs
        if (busy) return false
        val image = AnalysisImage.copyOf(frame, maxSide)
        val token = generation
        busy = true
        try {
            worker.execute {
                try {
                    if (analyzerGeneration != token) {
                        analyzer?.close(); analyzer = null
                        analyzerGeneration = token
                    }
                    val detector = analyzer ?: factory.create().also { analyzer = it }
                    val faces = detector.analyze(image).toList()
                    synchronized(lock) {
                        if (!closed && token == generation) result = FaceResult(image.info, faces)
                    }
                } catch (error: Exception) {
                    val active = synchronized(lock) {
                        if (!closed && token == generation) { result = null; true } else false
                    }
                    if (active) try { onError(error) } catch (_: Exception) { /* 错误回调不能破坏资源释放。 */ }
                } finally { synchronized(lock) { busy = false } }
            }
        } catch (error: RejectedExecutionException) {
            busy = false
            throw error
        }
        true
    }

    fun latest(info: FrameInfo, maxAgeNs: Long): FaceResult? = synchronized(lock) {
        require(maxAgeNs >= 0)
        result?.takeIf {
            !closed && it.info.sameGeometry(info) && info.timestampNs >= it.info.timestampNs &&
                info.timestampNs - it.info.timestampNs <= maxAgeNs
        }
    }

    /** 切摄像头（即使尺寸不变）必须显式调用；丢弃晚到结果，时间戳可重新开始。 */
    fun reset() = synchronized(lock) { check(!closed); resetLocked() }
    private fun resetLocked() { generation++; result = null; geometry = null; submittedNs = -1 }

    /** 不等待正在执行的推理；close 返回后不再发布结果，后端随后在 worker 上释放。 */
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            resetLocked()
            worker.execute {
                try { analyzer?.close() } catch (error: Exception) {
                    try { onError(error) } catch (_: Exception) { }
                } finally { analyzer = null }
            }
            worker.shutdown()
        }
    }
}
