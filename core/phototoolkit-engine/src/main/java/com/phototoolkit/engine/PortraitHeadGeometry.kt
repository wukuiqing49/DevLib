package com.phototoolkit.engine

import kotlin.math.max

data class PortraitHeadGeometry(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val eyeCenterY: Double?,
    val isReliable: Boolean = true,
) {
    init {
        require(width > 0.0 && height > 0.0)
    }

    companion object {
        fun fromFace(face: PortraitFace): PortraitHeadGeometry = PortraitHeadGeometry(
            left = face.left.toDouble(),
            top = face.top.toDouble(),
            width = face.width.toDouble(),
            height = face.height.toDouble(),
            eyeCenterY = face.eyeCenterY?.toDouble(),
            isReliable = false,
        )
    }
}

object PortraitHeadGeometryEstimator {
    private const val ALPHA_THRESHOLD = 48
    /*
     * 人脸框通常已经覆盖额头到下巴。蒙版把背景或墙面误当作头发时，
     * 会把 crownY 拉得过高，进而在合成时把脸缩小并推向画面下方。
     * 因此只接受与人脸框相邻的头顶候选，超出范围改用稳定的人脸框。
     */
    private const val MIN_HEAD_TO_FACE_RATIO = 0.85
    private const val MAX_HEAD_TO_FACE_RATIO = 1.35
    private const val MAX_CROWN_ABOVE_FACE_RATIO = 0.45
    private const val MAX_CROWN_BELOW_FACE_RATIO = 0.20

    fun estimate(
        face: PortraitFace,
        mask: PortraitMatting.AlphaMask,
        imageWidth: Int,
        imageHeight: Int,
        measurement: IdPhotoHeadMeasurement,
    ): PortraitHeadGeometry {
        val detectedGeometry = PortraitHeadGeometry.fromFace(face)
        if (imageWidth < 1 || imageHeight < 1) return detectedGeometry
        val fallback = requireNotNull(detectedGeometry.clippedToImage(imageWidth, imageHeight)) {
            "Face rectangle does not intersect the image"
        }
        if (measurement == IdPhotoHeadMeasurement.FACE_BOX) return fallback.copy(isReliable = true)

        val stableFallback = fallback.copy(isReliable = true)
        val crownY = findCrownY(face, mask, imageWidth, imageHeight) ?: return stableFallback
        val chinY = face.chinY?.toDouble() ?: return stableFallback
        val headHeight = chinY - crownY
        val headToFaceRatio = headHeight / face.height
        val crownOffsetRatio = (face.top - crownY) / face.height
        if (headToFaceRatio !in MIN_HEAD_TO_FACE_RATIO..MAX_HEAD_TO_FACE_RATIO ||
            crownOffsetRatio !in -MAX_CROWN_BELOW_FACE_RATIO..MAX_CROWN_ABOVE_FACE_RATIO
        ) {
            return stableFallback
        }
        if (crownY < 0.0 || chinY > imageHeight || face.eyeCenterY?.toDouble()?.let { it !in crownY..chinY } == true) {
            return stableFallback
        }
        return PortraitHeadGeometry(
            left = face.left.toDouble(),
            top = crownY,
            width = face.width.toDouble(),
            height = headHeight,
            eyeCenterY = face.eyeCenterY?.toDouble(),
            isReliable = true,
        ).clippedToImage(imageWidth, imageHeight) ?: stableFallback
    }

    private fun findCrownY(
        face: PortraitFace,
        mask: PortraitMatting.AlphaMask,
        imageWidth: Int,
        imageHeight: Int,
    ): Double? {
        val scaleX = mask.width.toDouble() / imageWidth
        val scaleY = mask.height.toDouble() / imageHeight
        val searchLeft = ((face.left - face.width * 0.25) * scaleX).toInt().coerceIn(0, mask.width - 1)
        val searchRight = ((face.left + face.width * 1.25) * scaleX).toInt().coerceIn(searchLeft, mask.width - 1)
        val searchBottom = ((face.top + face.height * 0.35) * scaleY).toInt().coerceIn(0, mask.height - 1)
        val minimumPixels = max(2, ((searchRight - searchLeft + 1) * 0.08).toInt())

        for (y in 0..searchBottom) {
            var foregroundPixels = 0
            val rowOffset = y * mask.width
            for (x in searchLeft..searchRight) {
                if ((mask.bytes[rowOffset + x].toInt() and 0xFF) >= ALPHA_THRESHOLD) {
                    foregroundPixels++
                }
            }
            if (foregroundPixels >= minimumPixels) return y / scaleY
        }
        return null
    }
}

private fun PortraitHeadGeometry.clippedToImage(imageWidth: Int, imageHeight: Int): PortraitHeadGeometry? {
    val clippedLeft = left.coerceIn(0.0, imageWidth.toDouble())
    val clippedTop = top.coerceIn(0.0, imageHeight.toDouble())
    val clippedRight = (left + width).coerceIn(0.0, imageWidth.toDouble())
    val clippedBottom = (top + height).coerceIn(0.0, imageHeight.toDouble())
    if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
    return PortraitHeadGeometry(
        left = clippedLeft,
        top = clippedTop,
        width = clippedRight - clippedLeft,
        height = clippedBottom - clippedTop,
        eyeCenterY = eyeCenterY?.takeIf { it in clippedTop..clippedBottom },
        isReliable = isReliable,
    )
}
