package com.beautysdk

import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

/** 不可变的非预乘 ARGB 素材副本；不引用 Bitmap，不依赖宿主资源生命周期。 */
class StickerImage(val width: Int, val height: Int, argb: IntArray) {
    private val colors = argb.copyOf()
    init {
        require(width in 1..2048 && height in 1..2048)
        require(colors.size == width * height)
    }
    internal fun pixel(x: Int, y: Int) = colors[y * width + x]
}

enum class StickerAnchor { EYES, UPPER_LIP }

/**
 * 图片左上原点、x 向右 y 向下；pivot 可在图片外，例如帽子的 pivotY=1.45。
 * widthInAnchorSpans 为整张图片宽度/双眼或嘴角距离，按显示画面的眼线或嘴线旋转。
 */
data class ImageSticker @JvmOverloads constructor(
    val image: StickerImage,
    val anchor: StickerAnchor,
    val widthInAnchorSpans: Float = 2.5f,
    val pivotX: Float = .5f,
    val pivotY: Float = .5f,
) {
    init {
        require(widthInAnchorSpans.isFinite() && widthInAnchorSpans in .1f..10f)
        require(pivotX.isFinite() && pivotY.isFinite() && pivotX in -4f..4f && pivotY in -4f..4f)
    }
    internal fun pose(placement: StickerPlacement): StickerPose? = when (anchor) {
        StickerAnchor.EYES -> placement.glasses
        StickerAnchor.UPPER_LIP -> placement.moustache
    }
}

/** 每帧最多三层，从列表首项向末项依次覆盖；构造时复制列表，可跨线程整体替换。 */
class StickerPack(layers: List<ImageSticker>) {
    val layers: List<ImageSticker> = Collections.unmodifiableList(layers.toList())
    init { require(layers.size <= 3) { "At most three image sticker layers" } }
}

/** 预先计算仿射逆变换，避免 CPU 对每个像素重复计算三角函数。 */
internal class PlacedImageSticker(
    private val layer: ImageSticker, pose: StickerPose, private val info: FrameInfo, scale: Float,
) {
    private val cx = pose.center.x * info.width
    private val cy = pose.center.y * info.height
    private val c = cos(pose.angleRadians)
    private val s = sin(pose.angleRadians)
    private val span = pose.spanPixels * scale * layer.widthInAnchorSpans
    fun sample(x: Float, y: Float): Int {
        val dx = x - cx; val dy = y - cy
        val u = (c * dx + s * dy) / span + layer.pivotX
        // 左右镜像改变坐标手性，只反转局部 y，不反转图片上的文字或不对称细节。
        val v = (-s * dx + c * dy) * (if (info.mirrored) -1f else 1f) / span *
            layer.image.width / layer.image.height + layer.pivotY
        if (u < 0f || u >= 1f || v < 0f || v >= 1f) return 0
        // 在源像素边界统一向右/下取样，抵消 CPU/GPU 浮点运算顺序的微小误差。
        return layer.image.pixel((u * layer.image.width + .001f).toInt().coerceAtMost(layer.image.width-1),
            (v * layer.image.height + .001f).toInt().coerceAtMost(layer.image.height-1))
    }
}
