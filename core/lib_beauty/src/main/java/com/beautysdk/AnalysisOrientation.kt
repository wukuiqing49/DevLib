package com.beautysdk

/** 模型分析前按元数据转正，结果再逆变换回原始像素空间；不改变输出视频尺寸或像素。 */
class AnalysisOrientation private constructor(val image: AnalysisImage, private val original: FrameInfo) {
    fun toRaw(point: FacePoint): FacePoint {
        val x = if (original.mirrored) 1f - point.x else point.x
        val y = point.y
        return when (original.rotationDegrees) {
            90 -> FacePoint(y, 1f - x)
            180 -> FacePoint(1f - x, 1f - y)
            270 -> FacePoint(1f - y, x)
            else -> FacePoint(x, y)
        }
    }
    fun toRaw(face: FaceObservation): FaceObservation {
        val b = face.bounds
        val corners = listOf(FacePoint(b.left,b.top),FacePoint(b.right,b.top),
            FacePoint(b.left,b.bottom),FacePoint(b.right,b.bottom)).map(::toRaw)
        return FaceObservation(FaceBounds(corners.minOf { it.x },corners.minOf { it.y },
            corners.maxOf { it.x },corners.maxOf { it.y }), face.anchors.mapValues { toRaw(it.value) },
            face.landmarks.map(::toRaw))
    }
    companion object {
        @JvmStatic fun upright(source: AnalysisImage): AnalysisOrientation {
            val info = source.info
            if (info.rotationDegrees == 0 && !info.mirrored) return AnalysisOrientation(source,info)
            val swap = info.rotationDegrees == 90 || info.rotationDegrees == 270
            val w = if (swap) source.height else source.width
            val h = if (swap) source.width else source.height
            val data = ByteArray(w*h*3)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val rotated = when (info.rotationDegrees) {
                    90 -> Pair(source.height-1-y,x)
                    180 -> Pair(source.width-1-x,source.height-1-y)
                    270 -> Pair(y,source.width-1-x)
                    else -> Pair(x,y)
                }
                val dx = if (info.mirrored) w-1-rotated.first else rotated.first
                val src = (y*source.width+x)*3; val dst = (rotated.second*w+dx)*3
                data[dst]=source.pixels[src];data[dst+1]=source.pixels[src+1];data[dst+2]=source.pixels[src+2]
            }
            return AnalysisOrientation(AnalysisImage(FrameInfo(w,h,info.timestampNs),w,h,data),info)
        }
    }
}
