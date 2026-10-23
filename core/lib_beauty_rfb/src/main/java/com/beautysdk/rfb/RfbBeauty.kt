package com.beautysdk.rfb

import android.content.Context
import com.beautysdk.*

/** RFB 方案入口：仅负责组装共享美颜管线，不创建相机、不编码或推流。 */
object RfbBeauty {
    /**
     * 创建后在视频处理线程调用 start/process/stop/close。
     * 默认 CPU 渲染；纹理输入请传入 GlBeautyRenderer，并通过 submitAnalysis 提供分析副本。
     * refinerFactory 在分析线程创建，每次会话代次变化后可能重建，不能返回已关闭的共享实例。
     * 未提供关键点模型时仍支持基础美颜，但不会显示依赖眼睛/嘴唇的挂件。
     * onError 可能来自渲染线程或分析线程，更新界面时由宿主切换至主线程。
     */
    @JvmStatic
    @JvmOverloads
    fun createSession(
        context: Context,
        renderer: BeautyRenderer = RgbaBeautyRenderer(),
        modelAsset: String = "version-RFB-320.onnx",
        refinerFactory: (() -> FaceLandmarkRefiner)? = null,
        analysisIntervalNs: Long = 100_000_000,
        maxResultAgeNs: Long = 200_000_000,
        onError: (Exception) -> Unit = {},
    ): BeautySession = BeautySession(
        factory = RfbFaceAnalyzer.factory(context, modelAsset, refinerFactory),
        renderer = renderer,
        maxResultAgeNs = maxResultAgeNs,
        onError = onError,
        analysisIntervalNs = analysisIntervalNs,
    )
}
