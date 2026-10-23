package com.beautysdk

import java.util.Collections
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.roundToInt

/** 所有坐标均为原始画面左上角原点、x 向右/y 向下的归一化坐标，尚未应用旋转/镜像元数据。 */
data class FacePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}
data class FaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top)
    }
}
/** LEFT/RIGHT 指转正后的画面左右，而非人物解剖学左右；坐标仍在原始空间。 */
enum class FaceAnchor { LEFT_EYE, RIGHT_EYE, NOSE, LEFT_MOUTH, RIGHT_MOUTH, UPPER_LIP }
enum class FaceCapability { BOUNDS, EYES, MOUTH_CORNERS, UPPER_LIP, DENSE_LANDMARKS }

class FaceObservation(
    val bounds: FaceBounds,
    anchors: Map<FaceAnchor, FacePoint> = emptyMap(),
    landmarks: List<FacePoint> = emptyList(),
) {
    val anchors: Map<FaceAnchor, FacePoint> = Collections.unmodifiableMap(
        EnumMap<FaceAnchor, FacePoint>(FaceAnchor::class.java).apply { putAll(anchors) }
    )
    val landmarks: List<FacePoint> = Collections.unmodifiableList(landmarks.toList())
}

/** SDK 独占的紧凑 RGB 副本。仅在 analyze 调用期间有效，后端不得保存或修改 pixels。 */
class AnalysisImage(val info: FrameInfo, val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height * 3) }
    companion object {
        fun copyOf(frame: RgbaFrame, maxSide: Int = 320): AnalysisImage {
            require(maxSide in 16..2048)
            val scale = minOf(1.0, maxSide.toDouble() / max(frame.info.width, frame.info.height))
            val w = max(1, (frame.info.width * scale).roundToInt())
            val h = max(1, (frame.info.height * scale).roundToInt())
            val rgb = ByteArray(w * h * 3)
            val source = frame.readOnlyPixels()
            for (y in 0 until h) for (x in 0 until w) {
                val sx = (x.toLong() * frame.info.width / w).toInt()
                val sy = (y.toLong() * frame.info.height / h).toInt()
                val src = sy * frame.rowStrideBytes + sx * 4
                val dst = (y * w + x) * 3
                for (c in 0..2) rgb[dst + c] = source.get(src + c)
            }
            return AnalysisImage(frame.info, w, h, rgb)
        }
    }
}

/** 由 worker 创建、分析、释放；单次同步分析，SDK 已在外层实现异步背压。 */
interface FaceAnalyzer : AutoCloseable {
    val id: String
    val capabilities: Set<FaceCapability>
    fun analyze(image: AnalysisImage): List<FaceObservation>
    override fun close()
}
fun interface FaceAnalyzerFactory { fun create(): FaceAnalyzer }

class FaceResult(val info: FrameInfo, faces: List<FaceObservation>) {
    val faces: List<FaceObservation> = Collections.unmodifiableList(faces.toList())
}

internal fun FrameInfo.sameGeometry(other: FrameInfo) = width == other.width && height == other.height &&
    rotationDegrees == other.rotationDegrees && mirrored == other.mirrored
