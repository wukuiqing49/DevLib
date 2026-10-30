package com.wkq.util.exif

import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegUtils
import org.apache.commons.imaging.formats.jpeg.xmp.JpegRewriter
import java.io.File
import java.io.OutputStream

/** 库的普通 XMP 删除只识别标准 APP1；补充扩展 XMP 与个人注释载体。 */
internal object JpegPrivacySegments : JpegRewriter() {
    private val extension = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)
    private fun extended(marker: Int, data: ByteArray) = marker == 0xffe1 &&
        data.size >= extension.size && extension.indices.all { data[it] == extension[it] }

    fun counts(file: File): Pair<Int, Int> {
        var xmp = 0
        var comments = 0
        JpegUtils().traverseJfif(ByteSource.file(file), object : JpegUtils.Visitor {
            override fun beginSos() = false
            override fun visitSos(marker: Int, markerBytes: ByteArray, imageData: ByteArray) = Unit
            override fun visitSegment(marker: Int, markerBytes: ByteArray, segmentLength: Int,
                                      segmentLengthBytes: ByteArray, segmentData: ByteArray): Boolean {
                if (extended(marker, segmentData)) xmp++
                if (marker == 0xfffe) comments++
                return true
            }
        })
        return xmp to comments
    }

    fun remove(file: File, output: OutputStream, personal: Boolean) {
        val pieces = analyzeJfif(ByteSource.file(file)).pieces.filterNot {
            it is JFIFPieceSegment && (it.isXmpSegment || extended(it.marker, it.segmentData) ||
                (personal && it.marker == 0xfffe))
        }
        writeSegments(output, pieces)
    }
}
