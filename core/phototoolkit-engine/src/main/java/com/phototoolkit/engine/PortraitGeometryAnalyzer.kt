package com.phototoolkit.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlin.math.hypot
import kotlin.math.max

data class PortraitShoulderGeometry(
    val leftX: Double,
    val leftY: Double,
    val rightX: Double,
    val rightY: Double,
    val confidence: Float,
) {
    val centerX: Double get() = (leftX + rightX) * 0.5
    val centerY: Double get() = (leftY + rightY) * 0.5
    val width: Double get() = hypot(rightX - leftX, rightY - leftY)
}

data class PortraitGeometryAnalysis(
    val imageWidth: Int,
    val imageHeight: Int,
    val face: PortraitFace,
    val head: PortraitHeadGeometry,
    val shoulders: PortraitShoulderGeometry?,
    val mask: PortraitMatting.AlphaMask,
    val roi: PortraitRoi,
)

internal data class PortraitCoordinateTransform(
    val roi: PortraitRoi,
    val decodedWidth: Int,
    val decodedHeight: Int,
) {
    init {
        require(decodedWidth > 0 && decodedHeight > 0)
    }

    val scaleX: Double get() = roi.width.toDouble() / decodedWidth
    val scaleY: Double get() = roi.height.toDouble() / decodedHeight

    fun mapX(value: Float): Float = (roi.left + value * scaleX).toFloat()

    fun mapY(value: Float): Float = (roi.top + value * scaleY).toFloat()
}

/**
 * 商用证件照的统一几何入口。全图仅用于人数和姿态检测，精细模型始终处理头肩 ROI。
 */
object PortraitGeometryAnalyzer {
    private const val ANALYSIS_MIN_FACE_SIZE = 0.02f
    private const val MAX_ANALYSIS_ROI_SIDE = 2048
    private const val MIN_SHOULDER_CONFIDENCE = 0.55f

    fun analyze(context: Context, imagePath: String, spec: IdPhotoSpec): PortraitGeometryAnalysis {
        val detection = PortraitFaceDetector.detect(
            context = context,
            imagePath = imagePath,
            minFaceSize = minOf(spec.minFaceWidthRatio, ANALYSIS_MIN_FACE_SIZE),
        )
        val subjectFaces = detection.subjectFaces()
        require(subjectFaces.isNotEmpty()) { "NO_FACE" }
        require(subjectFaces.size == 1) { "MULTIPLE_FACES" }
        val detectedFace = subjectFaces.single()
        require(detectedFace.isForwardFacing(spec.maxHeadPoseDegrees)) { "HEAD_POSE_UNSUPPORTED" }

        val roi = PortraitRoiPlanner.plan(detectedFace, detection.imageWidth, detection.imageHeight)
        val decoded = decodeRegion(imagePath, roi)
        val transform = PortraitCoordinateTransform(roi, decoded.width, decoded.height)
        val image = InputImage.fromBitmap(decoded, 0)
        try {
            val refinedFace = refineFaceWithMesh(detectedFace, transform, image)
            require(refinedFace.hasBothEyes && refinedFace.chinY != null) { "FACE_MESH_FAILED" }
            val shoulders = detectShoulders(transform, image)
            if (shoulders == null && spec.shoulderLineConstraint == IdPhotoConstraintLevel.HARD) {
                throw IllegalArgumentException("SHOULDERS_NOT_DETECTED")
            }
            val mask = PortraitMatting.createMask(
                context.applicationContext,
                imagePath,
                refinedFace,
                detection.imageWidth,
                detection.imageHeight,
            )
            val head = PortraitHeadGeometryEstimator.estimate(
                face = refinedFace,
                mask = mask,
                imageWidth = detection.imageWidth,
                imageHeight = detection.imageHeight,
                measurement = spec.headMeasurement,
            )
            require(head.isReliable) { "HEAD_GEOMETRY_UNRELIABLE" }
            shoulders?.let { detectedShoulders ->
                require(detectedShoulders.centerY > head.top + head.height * 0.70) {
                    "SHOULDER_GEOMETRY_INVALID"
                }
                require(detectedShoulders.width > refinedFace.width * 0.85) {
                    "SHOULDER_GEOMETRY_INVALID"
                }
            }
            return PortraitGeometryAnalysis(
                imageWidth = detection.imageWidth,
                imageHeight = detection.imageHeight,
                face = refinedFace,
                head = head,
                shoulders = shoulders,
                mask = mask,
                roi = roi,
            )
        } finally {
            decoded.recycle()
        }
    }

    private fun refineFaceWithMesh(
        detectedFace: PortraitFace,
        transform: PortraitCoordinateTransform,
        image: InputImage,
    ): PortraitFace {
        val options = FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
        val detector = FaceMeshDetection.getClient(options)
        val meshes = try {
            Tasks.await(detector.process(image))
        } finally {
            detector.close()
        }
        val mesh = meshes.minByOrNull { value ->
            val mappedCenterX = transform.mapX(value.boundingBox.exactCenterX())
            val mappedCenterY = transform.mapY(value.boundingBox.exactCenterY())
            hypot(mappedCenterX - detectedFace.centerX, mappedCenterY - detectedFace.centerY)
        } ?: return detectedFace
        val leftEye = mesh.getPoints(FaceMesh.LEFT_EYE).averagePosition() ?: return detectedFace
        val rightEye = mesh.getPoints(FaceMesh.RIGHT_EYE).averagePosition() ?: return detectedFace
        val chin = mesh.getPoints(FaceMesh.FACE_OVAL).maxByOrNull { it.position.y } ?: return detectedFace
        return detectedFace.copy(
            leftEyeX = transform.mapX(leftEye.first),
            leftEyeY = transform.mapY(leftEye.second),
            rightEyeX = transform.mapX(rightEye.first),
            rightEyeY = transform.mapY(rightEye.second),
            chinY = transform.mapY(chin.position.y),
        )
    }

    private fun detectShoulders(
        transform: PortraitCoordinateTransform,
        image: InputImage,
    ): PortraitShoulderGeometry? {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptionsBase.SINGLE_IMAGE_MODE)
            .build()
        val detector = PoseDetection.getClient(options)
        val pose = try {
            Tasks.await(detector.process(image))
        } finally {
            detector.close()
        }
        val left = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: return null
        val right = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER) ?: return null
        val confidence = minOf(left.inFrameLikelihood, right.inFrameLikelihood)
        if (confidence < MIN_SHOULDER_CONFIDENCE) return null
        return PortraitShoulderGeometry(
            leftX = transform.mapX(left.position.x).toDouble(),
            leftY = transform.mapY(left.position.y).toDouble(),
            rightX = transform.mapX(right.position.x).toDouble(),
            rightY = transform.mapY(right.position.y).toDouble(),
            confidence = confidence,
        )
    }

    private fun decodeRegion(path: String, roi: PortraitRoi): Bitmap {
        var sample = 1
        while (max(roi.width, roi.height) / sample > MAX_ANALYSIS_ROI_SIDE) sample *= 2
        val decoder = BitmapRegionDecoder.newInstance(path, false)
            ?: throw IllegalArgumentException("无法读取头肩区域")
        return try {
            decoder.decodeRegion(
                Rect(roi.left, roi.top, roi.right, roi.bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: throw IllegalArgumentException("无法解码头肩区域")
        } finally {
            decoder.recycle()
        }
    }

    private fun List<FaceMeshPoint>.averagePosition(): Pair<Float, Float>? {
        if (isEmpty()) return null
        return map { it.position.x }.average().toFloat() to map { it.position.y }.average().toFloat()
    }

}

internal fun PortraitFace.isForwardFacing(maxPoseDegrees: Float): Boolean =
    kotlin.math.abs(pitchDegrees) <= maxPoseDegrees &&
        kotlin.math.abs(yawDegrees) <= maxPoseDegrees &&
        kotlin.math.abs(rollDegrees) <= maxPoseDegrees
