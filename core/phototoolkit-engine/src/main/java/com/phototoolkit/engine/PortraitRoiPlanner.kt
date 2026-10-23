package com.phototoolkit.engine

import kotlin.math.ceil
import kotlin.math.floor

data class PortraitRoi(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(left >= 0 && top >= 0)
        require(right > left && bottom > top)
    }
}

/** 根据全分辨率人脸框规划头肩区域，避免整身照被整体压缩后再做人像分割。 */
object PortraitRoiPlanner {
    private const val HORIZONTAL_FACE_PADDING = 1.35
    private const val TOP_FACE_PADDING = 1.10
    private const val BOTTOM_FACE_PADDING = 3.20

    fun plan(face: PortraitFace, imageWidth: Int, imageHeight: Int): PortraitRoi {
        require(imageWidth > 0 && imageHeight > 0)
        val centerX = face.left + face.width / 2.0
        val left = floor(centerX - face.width * (0.5 + HORIZONTAL_FACE_PADDING)).toInt()
            .coerceIn(0, imageWidth - 1)
        val right = ceil(centerX + face.width * (0.5 + HORIZONTAL_FACE_PADDING)).toInt()
            .coerceIn(left + 1, imageWidth)
        val top = floor(face.top - face.height * TOP_FACE_PADDING).toInt()
            .coerceIn(0, imageHeight - 1)
        val bottom = ceil(face.top + face.height * (1.0 + BOTTOM_FACE_PADDING)).toInt()
            .coerceIn(top + 1, imageHeight)
        return PortraitRoi(left, top, right, bottom)
    }
}
