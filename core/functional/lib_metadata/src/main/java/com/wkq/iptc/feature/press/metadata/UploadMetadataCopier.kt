package com.wkq.iptc.feature.press.metadata

import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.File
import java.io.OutputStream

/** 压缩只改变像素，业务元数据从最终照片复制，不能重新套用可变的预设。 */
object UploadMetadataCopier {
    fun copy(source: File, target: File, width: Int, height: Int) {
        require(source.canonicalFile != target.canonicalFile)
        require(width in 1..65535 && height in 1..65535)
        val parser = JpegImageParser()
        val params = JpegImagingParameters()
        val metadata = parser.getMetadata(ByteSource.file(source), params) as? JpegImageMetadata
        // EXIF 本身是可选数据；无 EXIF 的正常照片只补充输出几何信息。
        val exif = metadata?.exif?.outputSet ?: TiffOutputSet()
        // 解码器已应用旋转；旧缩略图和旧尺寸不能沿用到新像素。
        exif.directories.forEach { it.setJpegImageData(null) }
        exif.removeField(TiffTagConstants.TIFF_TAG_ORIENTATION)
        exif.orCreateRootDirectory.add(TiffTagConstants.TIFF_TAG_ORIENTATION, 1.toShort())
        exif.removeField(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH)
        exif.removeField(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH)
        exif.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH)
        exif.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH)
        exif.orCreateExifDirectory.add(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH, width.toShort())
        exif.orCreateExifDirectory.add(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH, height.toShort())
        rewrite(target) { input, output -> ExifRewriter().updateExifMetadataLossless(input, output, exif) }

        metadata?.photoshop?.photoshopApp13Data?.let { iptc ->
            rewrite(target) { input, output -> JpegIptcRewriter().writeIptc(ByteSource.file(input), output, iptc) }
        }
        val xmp = parser.getXmpXml(ByteSource.file(source), params)
        if (!xmp.isNullOrBlank()) {
            rewrite(target) { input, output -> JpegXmpRewriter().updateXmpXml(ByteSource.file(input), output, xmp) }
        }
        verifyContent(source, target)
    }

    /** 重新签名等后处理完成后，再确认逐张编辑内容没有改变。 */
    fun verifyContent(source: File, target: File) {
        val parser = JpegImageParser()
        val params = JpegImagingParameters()
        val original = parser.getPhotoshopMetadata(ByteSource.file(source), params)
        val copied = parser.getPhotoshopMetadata(ByteSource.file(target), params)
        fun records(file: org.apache.commons.imaging.formats.jpeg.JpegPhotoshopMetadata?) =
            file?.photoshopApp13Data?.records.orEmpty().map { it.iptcType.type to it.value }.sortedBy { it.first }
        check(records(original) == records(copied)) { "Compressed IPTC differs from source" }
        check(parser.getXmpXml(ByteSource.file(target), params).orEmpty() ==
            parser.getXmpXml(ByteSource.file(source), params).orEmpty()) {
            "Compressed XMP differs from source"
        }
    }

    private fun rewrite(target: File, writer: (File, OutputStream) -> Unit) {
        val staged = File.createTempFile("upload_metadata_", ".jpg", target.parentFile)
        try {
            staged.outputStream().buffered().use { writer(target, it) }
            staged.copyTo(target, overwrite = true)
        } finally {
            staged.delete()
        }
    }
}
