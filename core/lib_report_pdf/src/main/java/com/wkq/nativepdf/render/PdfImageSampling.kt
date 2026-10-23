package com.wkq.nativepdf.render

internal fun pdfImageSampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (width.toLong() / (sample.toLong() * 2) >= requestedWidth.coerceAtLeast(1) &&
        height.toLong() / (sample.toLong() * 2) >= requestedHeight.coerceAtLeast(1)
    ) {
        sample *= 2
    }
    // 保留常规输出清晰度；限制极端长图的解码内存，ARGB 每张最多约 16 MiB。
    while (true) {
        val decodedWidth = (width.toLong() + sample - 1) / sample
        val decodedHeight = (height.toLong() + sample - 1) / sample
        if (decodedWidth * decodedHeight <= 4L * 1024 * 1024 &&
            decodedWidth <= 8192 && decodedHeight <= 8192
        ) return sample
        sample *= 2
    }
}
