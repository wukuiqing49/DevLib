package com.wkq.iptc.feature.press.metadata

data class MetadataVerificationResult(
    val exifVerified: Boolean,
    val iptcVerified: Boolean,
    val xmpVerified: Boolean,
    val error: String?
) {
    /**
     * 商业交付仅接受 EXIF、IPTC、XMP 三部分均验证通过的文件。
     */
    val isFullyVerified: Boolean
        get() = exifVerified && iptcVerified && xmpVerified && error == null
}
