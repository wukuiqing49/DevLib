package com.beautysdk.rfb

import ai.onnxruntime.*
import android.content.Context
import com.beautysdk.*
import java.nio.FloatBuffer

/** 可选关键点后端；由同一 worker 调用、释放。返回原始归一化坐标，不能伪造上唇等能力。 */
interface FaceLandmarkRefiner : AutoCloseable {
    val capabilities: Set<FaceCapability>
    fun refine(image: AnalysisImage, bounds: FaceBounds): Map<FaceAnchor, FacePoint>
    override fun close()
}

/** 对应上游 version-RFB-320.onnx：RGB NCHW [1,3,240,320]，(pixel-127)/128。
 * 单人模式取最高分框；模型输出必须是已解码 boxes 和 scores，拒绝其他同名 RFB 变体。
 * 没有 refiner 时只声明 BOUNDS，不会绘制眼镜/胡子。
 */
class RfbFaceAnalyzer @JvmOverloads constructor(
    model: ByteArray,
    private val refiner: FaceLandmarkRefiner? = null,
    private val threshold: Float = .7f,
) : FaceAnalyzer {
    override val id = "rfb-320"
    override val capabilities = setOf(FaceCapability.BOUNDS) + (refiner?.capabilities ?: emptySet())
    private val lifetime = AnalyzerLifetime()
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input: String
    private val boxesOutput: String
    private val scoresOutput: String
    init {
        require(threshold in 0f..1f)
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(model, options)
        }
        try {
            require(created.inputInfo.size == 1)
            input = created.inputNames.single()
            val inputInfo = created.inputInfo.getValue(input).info as TensorInfo
            require(inputInfo.type == OnnxJavaType.FLOAT) { "Expected float32 RFB input" }
            val shape = inputInfo.shape
            require(shape.contentEquals(longArrayOf(1, 3, 240, 320))) { "Expected RFB RGB [1,3,240,320]" }
            fun output(last: Long): String = created.outputInfo.entries.single { (_, node) ->
                val tensor = node.info as? TensorInfo
                tensor != null && tensor.type == OnnxJavaType.FLOAT && tensor.shape.size == 3 && tensor.shape[0] == 1L && tensor.shape[2] == last
            }.key
            boxesOutput = output(4); scoresOutput = output(2)
            session = created
        } catch (error: Exception) { created.close(); throw error }
    }
    override fun analyze(source: AnalysisImage): List<FaceObservation> {
        lifetime.requireOpen()
        val orientation = AnalysisOrientation.upright(source)
        val image = orientation.image
        val data = FloatArray(3 * 320 * 240)
        for (y in 0 until 240) for (x in 0 until 320) {
            val sx = x * image.width / 320; val sy = y * image.height / 240
            val p = (sy * image.width + sx) * 3
            for (c in 0..2) data[c * 320 * 240 + y * 320 + x] = ((image.pixels[p + c].toInt() and 255) - 127) / 128f
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, 240, 320)).use { tensor ->
            session.run(mapOf(input to tensor)).use { output ->
                val scores = (output.get(scoresOutput).get().value as Array<*>)[0] as Array<*>
                val boxes = (output.get(boxesOutput).get().value as Array<*>)[0] as Array<*>
                val bounds = RfbPostprocessor.best(scores.map { (it as FloatArray)[1] }.toFloatArray(),
                    boxes.map { it as FloatArray }, threshold) ?: return emptyList()
                return listOf(orientation.toRaw(FaceObservation(bounds, refiner?.refine(image, bounds) ?: emptyMap())))
            }
        }
    }
    /** 会话和可选关键点模型均归本实例管理；共享 OrtEnvironment 不在此关闭。 */
    override fun close() {
        if (lifetime.beginClose()) try { session.close() } finally { refiner?.close() }
    }
    companion object {
        @JvmStatic @JvmOverloads
        fun factory(context: Context, modelAsset: String = "version-RFB-320.onnx",
                    refinerFactory: (() -> FaceLandmarkRefiner)? = null): FaceAnalyzerFactory {
            val app = context.applicationContext
            return FaceAnalyzerFactory {
                val model = app.assets.open(modelAsset).use { it.readBytes() }
                val refiner = refinerFactory?.invoke()
                try { RfbFaceAnalyzer(model, refiner) } catch (error: Exception) { refiner?.close(); throw error }
            }
        }
    }
}

internal object RfbPostprocessor {
    fun best(scores: FloatArray, boxes: List<FloatArray>, threshold: Float): FaceBounds? {
        require(scores.size == boxes.size)
        return scores.indices.asSequence().filter { scores[it].isFinite() && scores[it] >= threshold }
            .sortedByDescending { scores[it] }.mapNotNull { index ->
                val box = boxes[index]
                require(box.size == 4)
                if (!box.all { it.isFinite() }) return@mapNotNull null
                val l = box[0].coerceIn(0f, 1f); val t = box[1].coerceIn(0f, 1f)
                val r = box[2].coerceIn(0f, 1f); val b = box[3].coerceIn(0f, 1f)
                if (r <= l || b <= t) null else FaceBounds(l, t, r, b)
            }.firstOrNull()
    }
}
