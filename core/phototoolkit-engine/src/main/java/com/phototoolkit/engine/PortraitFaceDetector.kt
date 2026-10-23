package com.phototoolkit.engine

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File

/** On-device face detection. Call from a worker thread. */
object PortraitFaceDetector {

    fun detect(
        context: Context,
        imagePath: String,
        minFaceSize: Float = DEFAULT_MIN_FACE_SIZE,
    ): PortraitFaceDetection {
        require(minFaceSize in 0.01f..1f) { "minFaceSize must be between 0.01 and 1" }
        val imageFile = File(imagePath)
        require(imageFile.isFile) { "Image file does not exist: $imagePath" }
        val image = InputImage.fromFilePath(context, Uri.parse(imageFile.toURI().toString()))
        val accurateFaces = detectFaces(
            image = image,
            minFaceSize = minFaceSize,
            performanceMode = FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE,
        )
        // 部分设备在高精度文件检测上会返回空列表，而相机实时快速检测可以识别同一张脸。
        // 仅在空结果时使用相同的快速模型兜底，后续证件照姿态、眼睛和尺寸校验仍然生效。
        val faces = accurateFaces.ifEmpty {
            detectFaces(
                image = image,
                minFaceSize = minFaceSize,
                performanceMode = FaceDetectorOptions.PERFORMANCE_MODE_FAST,
            )
        }
        return PortraitFaceDetection(
            imageWidth = image.width,
            imageHeight = image.height,
            faces = faces.map { it.toPortraitFace() },
        )
    }

    private fun detectFaces(
        image: InputImage,
        minFaceSize: Float,
        performanceMode: Int,
    ): List<Face> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(performanceMode)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(minFaceSize)
            .build()
        val detector = FaceDetection.getClient(options)
        return try {
            Tasks.await(detector.process(image))
        } finally {
            detector.close()
        }
    }

    private fun Face.toPortraitFace(): PortraitFace {
        val box = boundingBox
        val leftEye = getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val chinY = getContour(FaceContour.FACE)?.points?.maxOfOrNull { it.y }
        return PortraitFace(
            left = box.left,
            top = box.top,
            width = box.width(),
            height = box.height(),
            leftEyeX = leftEye?.x,
            leftEyeY = leftEye?.y,
            rightEyeX = rightEye?.x,
            rightEyeY = rightEye?.y,
            leftEyeOpenProbability = leftEyeOpenProbability,
            rightEyeOpenProbability = rightEyeOpenProbability,
            pitchDegrees = headEulerAngleX,
            yawDegrees = headEulerAngleY,
            rollDegrees = headEulerAngleZ,
            chinY = chinY,
        )
    }

    private const val DEFAULT_MIN_FACE_SIZE = 0.05f
}

data class PortraitFaceDetection(
    val imageWidth: Int,
    val imageHeight: Int,
    val faces: List<PortraitFace>,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
    }

    fun largestFace(): PortraitFace? = faces.maxByOrNull { it.width.toLong() * it.height }

    /**
     * 证件照以主肖像为准：忽略远小于主脸的海报、屏幕反射或误检框；
     * 两张面积接近的人脸仍会被保留，后续继续按多人脸拒绝。
     */
    fun subjectFaces(): List<PortraitFace> {
        val largestArea = largestFace()?.let { it.width.toLong() * it.height } ?: return emptyList()
        return faces.filter { face ->
            face.width.toLong() * face.height >= largestArea * MIN_SUBJECT_FACE_AREA_RATIO
        }
    }

    private companion object {
        const val MIN_SUBJECT_FACE_AREA_RATIO = 0.35
    }
}

data class PortraitFace(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val leftEyeX: Float?,
    val leftEyeY: Float?,
    val rightEyeX: Float?,
    val rightEyeY: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val pitchDegrees: Float,
    val yawDegrees: Float,
    val rollDegrees: Float,
    val chinY: Float? = null,
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val hasBothEyes: Boolean
        get() = leftEyeX != null && leftEyeY != null && rightEyeX != null && rightEyeY != null
    val eyeCenterY: Float?
        get() = if (leftEyeY != null && rightEyeY != null) (leftEyeY + rightEyeY) / 2f else null
}
