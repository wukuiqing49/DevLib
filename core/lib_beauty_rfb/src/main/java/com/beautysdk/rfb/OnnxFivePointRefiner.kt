package com.beautysdk.rfb

import ai.onnxruntime.*
import com.beautysdk.*
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** 显式模型契约，而非对任意五点模型猜测预处理。
 * 支持 float RGB NCHW [1,3,inputSize,inputSize]，(pixel-mean)/std，输出 [1,10]，
 * 顺序为眼1/眼2/鼻尖/嘴角1/嘴角2，各点 xy，取值 [0,1] 或 [-1,1]。
 * 不包含模型；使用者需提供满足契约且许可适用的模型。SeetaFace .csta 不能交给 ONNX Runtime。
 */
class OnnxFivePointRefiner @JvmOverloads constructor(
    model: ByteArray,
    private val inputSize: Int = 112,
    private val mean: Float = 127.5f,
    private val std: Float = 128f,
    private val signedCoordinates: Boolean = false,
) : FaceLandmarkRefiner {
    override val capabilities = setOf(FaceCapability.EYES, FaceCapability.MOUTH_CORNERS)
    private val lifetime = AnalyzerLifetime()
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input: String
    init {
        require(inputSize in 16..1024 && mean.isFinite() && std.isFinite() && std > 0)
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1); env.createSession(model, options)
        }
        try {
            input = created.inputNames.single()
            val inputInfo = created.inputInfo.getValue(input).info as TensorInfo
            require(inputInfo.type == OnnxJavaType.FLOAT && inputInfo.shape
                .contentEquals(longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())))
            require(created.outputInfo.size == 1)
            val outputInfo = created.outputInfo.values.single().info as TensorInfo
            require(outputInfo.type == OnnxJavaType.FLOAT && outputInfo.shape.contentEquals(longArrayOf(1, 10))) { "Expected five xy points in [1,10]" }
            session = created
        } catch (error: Exception) { created.close(); throw error }
    }
    override fun refine(image: AnalysisImage, bounds: FaceBounds): Map<FaceAnchor, FacePoint> {
        lifetime.requireOpen()
        // 使用真实检测框裁剪，推理后映射回整张正向分析图，再由检测器还原原始方向。
        val left = floor(bounds.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = floor(bounds.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = ceil(bounds.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = ceil(bounds.bottom * image.height).toInt().coerceIn(top + 1, image.height)
        val data = FloatArray(inputSize * inputSize * 3)
        for (y in 0 until inputSize) for (x in 0 until inputSize) {
            val sx = left + x * (right - left) / inputSize
            val sy = top + y * (bottom - top) / inputSize
            for (c in 0..2) data[c * inputSize * inputSize + y * inputSize + x] =
                ((image.pixels[(sy * image.width + sx) * 3 + c].toInt() and 255) - mean) / std
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1,3,inputSize.toLong(),inputSize.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val values = (result[0].value as Array<*>)[0] as FloatArray
                require(values.size == 10 && values.all { it.isFinite() })
                val points = (0..4).map { i ->
                    fun normalized(v: Float) = if (signedCoordinates) (v + 1) / 2 else v
                    val x = normalized(values[i * 2]); val y = normalized(values[i * 2 + 1])
                    require(x in 0f..1f && y in 0f..1f) { "Landmark output outside declared range" }
                    FacePoint((left + x * (right - left)) / image.width, (top + y * (bottom - top)) / image.height)
                }
                val eyes = points.take(2).sortedBy { it.x }; val mouth = points.takeLast(2).sortedBy { it.x }
                return mapOf(FaceAnchor.LEFT_EYE to eyes[0], FaceAnchor.RIGHT_EYE to eyes[1],
                    FaceAnchor.NOSE to points[2], FaceAnchor.LEFT_MOUTH to mouth[0], FaceAnchor.RIGHT_MOUTH to mouth[1])
            }
        }
    }
    override fun close() { if (lifetime.beginClose()) session.close() }
}
