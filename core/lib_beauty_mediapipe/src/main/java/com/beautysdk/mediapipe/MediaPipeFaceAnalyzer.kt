package com.beautysdk.mediapipe

import android.content.Context
import android.graphics.Bitmap
import com.beautysdk.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/** CPU VIDEO 模式在 SDK 的有界 worker 上同步调用；不阻塞引擎线程，不叠加第二套异步队列。
 * 分析图按元数据转正，再把结果逆变换回原始像素空间。模型在 assets 中本地加载。
 */
class MediaPipeFaceAnalyzer(context: Context, modelAsset: String = "face_landmarker.task") : FaceAnalyzer {
    override val id = "mediapipe-face-landmarker"
    override val capabilities = setOf(FaceCapability.BOUNDS, FaceCapability.EYES,
        FaceCapability.MOUTH_CORNERS, FaceCapability.UPPER_LIP, FaceCapability.DENSE_LANDMARKS)
    private val detector = FaceLandmarker.createFromOptions(context.applicationContext,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
            .setOutputFaceBlendshapes(false).setOutputFacialTransformationMatrixes(false).build())
    private var previousMs = -1L
    override fun analyze(source: AnalysisImage): List<FaceObservation> {
        val orientation = AnalysisOrientation.upright(source)
        val image = orientation.image
        val pixels = IntArray(image.width * image.height) { i ->
            val p = i * 3
            (255 shl 24) or ((image.pixels[p].toInt() and 255) shl 16) or
                ((image.pixels[p + 1].toInt() and 255) shl 8) or (image.pixels[p + 2].toInt() and 255)
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            try {
                val timestampMs = maxOf(previousMs + 1, image.info.timestampNs / 1_000_000)
                previousMs = timestampMs
                return detector.detectForVideo(mpImage, timestampMs).faceLandmarks().mapNotNull { landmarks ->
                    val points = landmarks.map { FacePoint(it.x(), it.y()) }
                    if (points.size < 478) return@mapNotNull null
                    val left = points.minOf { it.x }.coerceIn(0f, 1f)
                    val top = points.minOf { it.y }.coerceIn(0f, 1f)
                    val right = points.maxOf { it.x }.coerceIn(0f, 1f)
                    val bottom = points.maxOf { it.y }.coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) return@mapNotNull null
                    val eyes = listOf(points[468], points[473]).sortedBy { it.x }
                    val mouth = listOf(points[61], points[291]).sortedBy { it.x }
                    FaceObservation(FaceBounds(left, top, right, bottom), mapOf(
                        FaceAnchor.LEFT_EYE to eyes[0], FaceAnchor.RIGHT_EYE to eyes[1],
                        FaceAnchor.NOSE to points[1], FaceAnchor.LEFT_MOUTH to mouth[0],
                        FaceAnchor.RIGHT_MOUTH to mouth[1], FaceAnchor.UPPER_LIP to points[0]), points)
                }.map { orientation.toRaw(it) }
            } finally { mpImage.close() }
        } finally { bitmap.recycle() }
    }
    override fun close() = detector.close()
    companion object {
        @JvmStatic @JvmOverloads
        fun factory(context: Context, modelAsset: String = "face_landmarker.task"): FaceAnalyzerFactory {
            val app = context.applicationContext
            return FaceAnalyzerFactory { MediaPipeFaceAnalyzer(app, modelAsset) }
        }
    }
}
