package com.beautysdk

import kotlin.math.*

enum class BeautyFilter { NONE, WARM, COOL }
data class BeautySettings @JvmOverloads constructor(
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val filter: BeautyFilter = BeautyFilter.NONE,
    val glasses: Boolean = false,
    val moustache: Boolean = false,
    val animateStickers: Boolean = false,
    /** 双眼锚点驱动的程序化礼帽；无有效双眼时隐藏。 */
    val hat: Boolean = false,
    /** 可选 PNG 解码后的素材包；与程序化效果独立，列表顺序即覆盖顺序。 */
    val stickerPack: StickerPack? = null,
) {
    init { require(smoothing in 0f..1f && whitening in 0f..1f) }
    val isIdentity: Boolean get() = smoothing == 0f && whitening == 0f && filter == BeautyFilter.NONE &&
        !glasses && !moustache && !hat && stickerPack?.layers.isNullOrEmpty()
}

/** 挂件局部坐标：中心为原点，单位长度为双眼/嘴角距离，角度沿画面 y 向下。 */
data class StickerPose(val center: FacePoint, val spanPixels: Float, val angleRadians: Float)
data class StickerPlacement(val glasses: StickerPose?, val moustache: StickerPose?, val hat: StickerPose? = null)
object StickerGeometry {
    fun place(face: FaceObservation?, info: FrameInfo): StickerPlacement {
        if (face == null) return StickerPlacement(null, null)
        val a = face.anchors
        fun pose(left: FacePoint?, right: FacePoint?, center: FacePoint?): StickerPose? {
            if (left == null || right == null || center == null) return null
            val dx = (right.x - left.x) * info.width
            val dy = (right.y - left.y) * info.height
            val span = hypot(dx, dy)
            return if (span < 2f) null else StickerPose(center, span, atan2(dy, dx))
        }
        val left = a[FaceAnchor.LEFT_EYE]; val right = a[FaceAnchor.RIGHT_EYE]
        val mid = if (left != null && right != null) FacePoint((left.x + right.x) / 2, (left.y + right.y) / 2) else null
        val eyes = pose(left, right, mid)
        // 原图经旋转再镜像；镜像会改变局部坐标手性，补半圈保持帽顶朝向额头。
        val hat = eyes?.let { it.copy(angleRadians = it.angleRadians + if (info.mirrored) PI.toFloat() else 0f) }
        return StickerPlacement(eyes, pose(a[FaceAnchor.LEFT_MOUTH],
            a[FaceAnchor.RIGHT_MOUTH], a[FaceAnchor.UPPER_LIP]), hat)
    }

    /** 礼帽相对双眼中点绘制：帽檐与帽身均为实色形状，超出画面自然裁切。 */
    internal fun coversHat(x: Float, y: Float, pose: StickerPose?, info: FrameInfo, scale: Float): Boolean {
        if (pose == null) return false
        val dx = (x - pose.center.x) * info.width / (pose.spanPixels * scale)
        val dy = (y - pose.center.y) * info.height / (pose.spanPixels * scale)
        val u = cos(pose.angleRadians) * dx + sin(pose.angleRadians) * dy
        val v = -sin(pose.angleRadians) * dx + cos(pose.angleRadians) * dy
        return (abs(u) < 1.05f && v > -.95f && v < -.81f) ||
            (abs(u) < .68f && v > -1.85f && v <= -.95f)
    }

    internal fun covers(x: Float, y: Float, pose: StickerPose?, info: FrameInfo, glasses: Boolean, scale: Float): Boolean {
        if (pose == null) return false
        val dx = (x - pose.center.x) * info.width / (pose.spanPixels * scale)
        val dy = (y - pose.center.y) * info.height / (pose.spanPixels * scale)
        val u = cos(pose.angleRadians) * dx + sin(pose.angleRadians) * dy
        val v = -sin(pose.angleRadians) * dx + cos(pose.angleRadians) * dy
        if (glasses) {
            val ring = abs(sqrt(((abs(u) - .5f) / .34f).pow(2) + (v / .27f).pow(2)) - 1) < .12f
            return ring || (abs(u) < .17f && abs(v) < .035f) || (abs(u) in .82f..1.04f && abs(v) < .035f)
        }
        return ((abs(u) - .22f) / .3f).pow(2) + ((v - .04f) / .12f).pow(2) < 1
    }
}

interface BeautyRenderer : AutoCloseable {
    fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame
    override fun close()
}
