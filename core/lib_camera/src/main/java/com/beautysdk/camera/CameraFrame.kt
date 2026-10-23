package com.beautysdk.camera

import java.nio.ByteBuffer

enum class CameraLens { FRONT, BACK }
enum class CameraStatus { STARTING, BOUND, STOPPED, ERROR }

data class CameraConfig @JvmOverloads constructor(
    val lens: CameraLens = CameraLens.FRONT,
    val width: Int = 640,
    val height: Int = 480,
    val maxAnalysisFps: Int = 10,
) {
    init { require(width in 16..4096 && height in 16..4096 && maxAnalysisFps in 1..30) }
}

/** 独占 RGBA8888 副本，无须 release，可在回调后继续使用。
 * 原点左上，像素未转正/镜像；显示顺序为顺时针 rotationDegrees 再水平 mirrored。
 * timestampNs 来自相机的单调纳秒时间线，不能直接与 System.nanoTime 比较；跨 session 可重置。
 */
class CameraFrame internal constructor(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int,
    val lens: CameraLens,
    val sessionId: Long,
    private val rgba: ByteArray,
) {
    val mirrored: Boolean get() = lens == CameraLens.FRONT
    val rowStrideBytes: Int get() = width * 4
    fun readOnlyPixels(): ByteBuffer = ByteBuffer.wrap(rgba).asReadOnlyBuffer()
}

interface CameraFrameListener {
    /** 单一分析线程同步调用。可复制/保存内存，但模块不自行写入磁盘。慢消费者会丢帧。 */
    fun onFrame(frame: CameraFrame)
    /** 与 onFrame 同线程，在已开始的帧回调结束后调用，供处理器释放线程相关资源。 */
    fun onStreamStopped() = Unit
}

/** 单平面 RGBA 打包；支持 position、行/像素 stride 及 crop。保持输入 position 不变。 */
internal object RgbaPacking {
    fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
             left: Int, top: Int, width: Int, height: Int): ByteArray {
        require(left >= 0 && top >= 0 && width > 0 && height > 0 && pixelStride >= 4)
        val rowEnd = (left.toLong() + width - 1) * pixelStride + 4
        require(rowStride >= rowEnd)
        val input = buffer.slice()
        val end = (top.toLong() + height - 1) * rowStride + rowEnd
        require(end <= input.remaining()) { "RGBA plane is too small" }
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
        for (y in 0 until height) for (x in 0 until width) {
            val src = (top+y)*rowStride+(left+x)*pixelStride
            val dst = (y*width+x)*4
            for (c in 0..3) output[dst+c] = input.get(src+c)
        }
        return output
    }
}
