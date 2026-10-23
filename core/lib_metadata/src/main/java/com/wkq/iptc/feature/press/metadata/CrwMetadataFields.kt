package com.wkq.iptc.feature.press.metadata

import android.location.Location

/** CRW 使用 CIFF 固定槽和内嵌 XMP，不能套用 TIFF/EXIF 的写入、删除规则。 */
internal object CrwMetadataFields {
    const val CAPTURE_DATE = "CanonRaw:DateTimeOriginal"
    const val COMMENT = "CanonRaw:UserComment"
    const val GPS_GROUP = "XMP-exif"

    fun gpsArguments(location: Location): List<String> = buildList {
        // XMP 坐标本身携带南/西符号，没有独立的 LatitudeRef/LongitudeRef 标签。
        add("-$GPS_GROUP:GPSLatitude=${location.latitude}")
        add("-$GPS_GROUP:GPSLongitude=${location.longitude}")
        if (location.hasAltitude()) {
            add("-$GPS_GROUP:GPSAltitude=${kotlin.math.abs(location.altitude)}")
            add("-$GPS_GROUP:GPSAltitudeRef#=${if (location.altitude < 0) 1 else 0}")
        }
        if (location.hasBearing()) {
            add("-$GPS_GROUP:GPSImgDirection=${location.bearing}")
            add("-$GPS_GROUP:GPSImgDirectionRef=T")
        }
    }

    // CIFF 时间戳不能删除。写 Unix 零值后，引擎必须回读为全零日期，不能跳过验证。
    const val CLEAR_CAPTURE_DATE = "-$CAPTURE_DATE=1970:01:01 00:00:00"

    fun clearFixedField(key: String): String? = when (key) {
        CAPTURE_DATE -> CLEAR_CAPTURE_DATE
        // EOS D30 的显示序列号为十六进制+十进制，必须绕过显示格式转换，写入数值零。
        "CanonRaw:SerialNumber" -> "-CanonRaw:SerialNumber#=0"
        else -> null
    }

    fun isEmptyCaptureDate(key: String, value: String): Boolean =
        key.substringBeforeLast(':').split(':').contains("CanonRaw") &&
            key.substringAfterLast(':') == "DateTimeOriginal" && value == "0000:00:00 00:00:00"
}
