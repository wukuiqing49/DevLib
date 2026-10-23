package com.wkq.iptc.feature.press.metadata

import android.content.Context
import android.location.Location
import com.wkq.dev.metadata.model.MetadataTemplate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import com.wkq.util.exif.MetadataPrivacyRules

/** ExifTool 支持的原格式容器；无法证明图像数据保持不变时拒绝提交。 */
object NativeContainerMetadata {
    private val writable = setOf("HEIC", "HEIF", "AVIF", "TIFF", "DNG", "CR2", "CR3", "NEF", "NRW", "ARW", "ORF", "RW2", "RAF", "MRW", "X3F", "CRW", "PEF", "SRW")
    private val personal = setOf("Artist", "Copyright", "ImageDescription", "UserComment", "CameraOwnerName",
        "OwnerName", "BodySerialNumber", "SerialNumber", "LensSerialNumber", "InternalSerialNumber", "ImageUniqueID",
        "Make", "Model", "Software", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
        "DateTimeOriginal", "CreateDate", "ModifyDate", "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized")
    private val technical = setOf("ExposureTime", "FNumber", "ISO", "ApertureValue", "ShutterSpeedValue", "ExposureCompensation",
        "MaxApertureValue", "FocalLength", "FocalLengthIn35mmFormat", "DigitalZoomRatio", "Flash", "WhiteBalance",
        "MeteringMode", "ExposureProgram", "SceneType", "SceneCaptureType", "LightSource", "SubjectDistance")

    fun isCandidate(file: File): Boolean {
        val h = ByteArray(32)
        val n = file.inputStream().use { it.read(h) }
        if (n < 16) return false
        val text = h.toString(Charsets.ISO_8859_1)
        return text.substring(4, 8) == "ftyp" || text.startsWith("II") || text.startsWith("MM") ||
            text.startsWith("FUJIFILMCCD-RAW ") || text.startsWith("FOVb") || text.startsWith("\u0000MR")
    }

    private suspend fun read(context: Context, file: File, tags: List<String>): JSONObject {
        val bytes = NativeMetadataRuntime.execute(context, listOf("-j", "-n", "-G1", "-a", "-api", "ImageHashType=SHA256") + tags + file.absolutePath)
        val objectValue = JSONArray(bytes.toString(Charsets.UTF_8)).getJSONObject(0)
        check(objectValue.keys().asSequence().none { it.substringAfterLast(':') == "Error" }) { "Invalid native photo metadata" }
        // 警告是诊断信息，不参与字段值或图像完整性快照；实际缺失的必要哈希仍会被拒绝。
        objectValue.keys().asSequence().filter { it.substringAfterLast(':') == "Warning" }.toList().forEach(objectValue::remove)
        return objectValue
    }

    private fun JSONObject.value(tag: String): String = keys().asSequence().firstOrNull { it.substringAfter(':') == tag }
        ?.let { optString(it) }.orEmpty()

    private suspend fun snapshot(context: Context, file: File): JSONObject {
        val result = read(context, file,
            listOf("-FileType", "-ImageDataHash", "-ImageWidth", "-ImageHeight", "-Orientation", "-Rotation", "-Mirror", "-ICC_Profile:All",
                "-BlackLevel", "-WhiteLevel", "-ColorMatrix1", "-ColorMatrix2", "-CameraCalibration1", "-CameraCalibration2",
                "-AnalogBalance", "-AsShotNeutral", "-AsShotWhiteXY", "-CFAPattern", "-CFARepeatPatternDim",
                "-ActiveArea", "-DefaultCropOrigin", "-DefaultCropSize", "-ColorPrimaries", "-TransferCharacteristics", "-MatrixCoefficients",
                "-WB_RGGBLevels", "-WB_RGBLevels", "-WB_RBLevels", "-WB_RGGBLevelsAsShot", "-ColorTemperature",
                "-LensID", "-LensModel", "-LensInfo", "-LensType"))
        for (tag in listOf("ICC_Profile", "PreviewImage", "ThumbnailImage", "JpgFromRaw")) {
            val bytes = NativeMetadataRuntime.execute(context, listOf("-b", "-$tag", file.absolutePath))
            result.put("Protected:${tag}Hash", if (tag != "ICC_Profile" && bytes.size >= 2 &&
                bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) previewHash(context, file, bytes)
                else sha256(bytes))
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun previewHash(context: Context, source: File, bytes: ByteArray): String {
        // RAW 的 JPEG 预览也承载 EXIF；允许清理元数据，同时验证编码像素、方向和色彩不变。
        val preview = File.createTempFile("native_preview_", ".jpg", source.parentFile)
        try {
            preview.writeBytes(bytes)
            val fields = read(context, preview, listOf("-ImageDataHash", "-ImageWidth", "-ImageHeight", "-Orientation", "-ICC_Profile:All"))
            check(fields.value("ImageDataHash").matches(Regex("[a-fA-F0-9]{64}"))) { "Cannot verify native preview image" }
            val profile = NativeMetadataRuntime.execute(context, listOf("-b", "-ICC_Profile", preview.absolutePath))
            val canonical = fields.keys().asSequence().filter { it != "SourceFile" }.sorted()
                .joinToString("\n") { "$it=${fields.optString(it)}" }
            return sha256((canonical + "\nICC=" + sha256(profile)).toByteArray(Charsets.UTF_8))
        } finally { preview.delete() }
    }

    private fun assertSnapshot(value: JSONObject) {
        check(value.value("FileType") in writable) { "Native writing unsupported for this camera format" }
        val hash = value.value("ImageDataHash")
        check(hash.matches(Regex("[a-fA-F0-9]{64}")) && hash != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") {
            "Cannot verify encoded native image data"
        }
        check(value.value("ImageWidth").toLongOrNull()?.let { it > 0 } == true &&
            value.value("ImageHeight").toLongOrNull()?.let { it > 0 } == true) { "Cannot verify native image dimensions" }
    }

    private fun equalSnapshot(before: JSONObject, after: JSONObject) {
        assertSnapshot(after)
        val keys = before.keys().asSequence().filter { it.substringAfter(':') != "SourceFile" }.toSet()
        check(keys == after.keys().asSequence().filter { it.substringAfter(':') != "SourceFile" }.toSet() &&
            keys.all { before.optString(it) == after.optString(it) }) { "Native image data or rendering properties changed" }
    }

    private suspend fun change(context: Context, file: File, edit: suspend (File, String) -> Unit) =
        MetadataExecutionService.protect(context) { _ ->
        val before = snapshot(context, file)
        assertSnapshot(before)
        // 导入工作文件可能统一使用 .jpg 后缀，引擎写入副本必须使用实际容器后缀。
        val suffix = when (val type = before.value("FileType")) {
            "HEIF" -> "heic"
            "TIFF" -> "tif"
            else -> type.lowercase(Locale.US)
        }
        val temp = File.createTempFile("native_metadata_", ".$suffix", file.parentFile)
        try {
            file.inputStream().use { input -> temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            } }
            edit(temp, before.value("FileType"))
            try {
                equalSnapshot(before, snapshot(context, temp))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw MetadataIntegrityException("Native image integrity verification failed", failure)
            }
            currentCoroutineContext().ensureActive()
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            if (!temp.renameTo(file)) throw java.io.IOException("Cannot commit native metadata copy")
        } finally { temp.delete() }
    }

    suspend fun readXmp(context: Context, file: File): String = NativeMetadataRuntime.execute(context,
        listOf("-b", "-XMP", file.absolutePath)).also { check(it.size <= 8 * 1024 * 1024) }.toString(Charsets.UTF_8)

    suspend fun fileType(context: Context, file: File): String =
        read(context, file, listOf("-FileType")).value("FileType")

    suspend fun canWrite(context: Context, file: File): Boolean {
        if (fileType(context, file) !in writable) return false
        val source = snapshot(context, file)
        // CRW 只能改写已有的 CIFF 日期槽；缺少槽时交给原有 JPEG 兜底策略。
        if (source.value("FileType") == "CRW" &&
            !read(context, file, listOf("-${CrwMetadataFields.CAPTURE_DATE}")).has(CrwMetadataFields.CAPTURE_DATE)) return false
        // 无法建立原格式完整性基线属于能力不足；读取、权限等异常继续向上传递。
        val hash = source.value("ImageDataHash")
        return hash.matches(Regex("[a-fA-F0-9]{64}")) &&
            hash != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" &&
            source.value("ImageWidth").toLongOrNull()?.let { it > 0 } == true &&
            source.value("ImageHeight").toLongOrNull()?.let { it > 0 } == true
    }

    private fun captureDateTag(type: String) = if (type == "CRW") CrwMetadataFields.CAPTURE_DATE else "EXIF:DateTimeOriginal"
    private fun commentTag(type: String) = if (type == "CRW") CrwMetadataFields.COMMENT else "EXIF:UserComment"

    suspend fun readSignatureComment(context: Context, file: File): String =
        read(context, file, listOf("-${commentTag(fileType(context, file))}")).value("UserComment")

    // 与现有签名一样用于完整性检查，不提供密钥认证或作者身份保证。
    private suspend fun integrityHash(context: Context, file: File, binding: String): String {
        val image = snapshot(context, file).also(::assertSnapshot)
        // 容器重排会改变 ThumbnailOffset 等地址，摘要只覆盖语义字段。
        val metadata = read(context, file, (personal + technical).filter { it != "UserComment" }
            .sorted().flatMap {
                if (image.value("FileType") == "CRW") listOf("-EXIF:$it", "-MakerNotes:$it") else listOf("-EXIF:$it")
            } + "-GPS:All")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        append("PRESSIPTC_NATIVE_V1")
        append(binding)
        for ((name, fields) in listOf("image" to image, "exif" to metadata)) {
            append(name)
            fields.keys().asSequence().filter {
                it.substringAfter(':') !in setOf("SourceFile", "UserComment")
            }.sorted().forEach { append(it); append(fields.optString(it)) }
        }
        append(readXmp(context, file))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun sign(context: Context, file: File, creator: String, locationSource: String?) {
        change(context, file) { copy, type ->
            val gps = readLocation(context, copy)
            val date = read(context, copy, listOf("-${captureDateTag(type)}")).value("DateTimeOriginal")
            // 无拍摄时间时，签名绑定本次签署时刻，不伪造 EXIF 拍摄时间。
            val timestamp = NativeCaptureDate.parse(date)?.time ?: System.currentTimeMillis()
            val binding = "${gps?.latitude ?: 0.0},${gps?.longitude ?: 0.0}|$timestamp|b64:" +
                android.util.Base64.encodeToString(creator.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) +
                "|loc:${locationSource.orEmpty()}"
            val hash = integrityHash(context, copy, binding)
            val comment = "PRESSIPTC_SIGN:NATIVE_V1:$hash|$binding"
            require(type != "CRW" || comment.toByteArray(Charsets.UTF_8).size < 256) { "CRW integrity marker exceeds CIFF comment capacity" }
            NativeMetadataRuntime.execute(context, listOf("-overwrite_original", "-${commentTag(type)}=$comment", copy.absolutePath))
            check(readSignatureComment(context, copy) == comment) { "Native integrity marker readback failed" }
            verifySignature(context, copy)
        }
    }

    suspend fun verifySignature(context: Context, file: File) {
        val comment = readSignatureComment(context, file)
        check(comment.startsWith("PRESSIPTC_SIGN:NATIVE_V1:")) { "Missing native integrity marker" }
        val hash = comment.removePrefix("PRESSIPTC_SIGN:NATIVE_V1:").substringBefore('|')
        check(hash.matches(Regex("[a-f0-9]{64}")) && hash == integrityHash(context, file, comment.substringAfter('|'))) {
            "Native image or metadata integrity check failed"
        }
    }

    suspend fun readLocation(context: Context, file: File): Location? {
        val group = if (fileType(context, file) == "CRW") CrwMetadataFields.GPS_GROUP else "EXIF"
        val fields = read(context, file, listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
            "GPSAltitude", "GPSAltitudeRef", "GPSImgDirection").map { "-$group:$it" })
        val lat = fields.value("GPSLatitude").toDoubleOrNull() ?: return null
        val lon = fields.value("GPSLongitude").toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Location("photo_exif").apply {
            latitude = if (fields.value("GPSLatitudeRef") == "S") -kotlin.math.abs(lat) else lat
            longitude = if (fields.value("GPSLongitudeRef") == "W") -kotlin.math.abs(lon) else lon
            fields.value("GPSAltitude").toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                altitude = if (fields.value("GPSAltitudeRef") == "1") -kotlin.math.abs(it) else it
            }
            fields.value("GPSImgDirection").toFloatOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 360 }?.let { bearing = it }
        }
    }

    suspend fun write(context: Context, file: File, template: MetadataTemplate, location: Location?): MetadataVerificationResult {
        // 能力检查先于临时复制及原生写入，不能静默丢弃无法表达的旧 IIM 字段。
        val effectiveTemplate = NativeIptcTemplate.forXmp(template)
        location?.let {
            require(it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                (!it.hasAltitude() || it.altitude.isFinite()) &&
                (!it.hasBearing() || (it.bearing.isFinite() && it.bearing >= 0 && it.bearing < 360))) {
                "Invalid native photo GPS"
            }
        }
        change(context, file) { copy, type ->
            val packet = XmpWriter.buildSidecarPacket(effectiveTemplate, readXmp(context, copy), copy)
            val xmp = File.createTempFile("native_xmp_", ".xmp", copy.parentFile)
            try {
                xmp.writeText(packet, Charsets.UTF_8)
                val dateTag = captureDateTag(type)
                val signatureTag = commentTag(type)
                val previousExif = read(context, copy, listOf("-$dateTag", "-$signatureTag"))
                val date = previousExif.value("DateTimeOriginal")
                val args = mutableListOf("-overwrite_original", "-XMP<=${xmp.absolutePath}")
                val priorComment = previousExif.value("UserComment")
                if (priorComment.startsWith("PRESSIPTC_SIGN:")) args += if (type == "CRW") "-$signatureTag^=" else "-$signatureTag="
                location?.let {
                    if (type != "CRW") {
                        args += listOf("-EXIF:GPSLatitude=${kotlin.math.abs(it.latitude)}", "-EXIF:GPSLatitudeRef=${if (it.latitude < 0) "S" else "N"}",
                            "-EXIF:GPSLongitude=${kotlin.math.abs(it.longitude)}", "-EXIF:GPSLongitudeRef=${if (it.longitude < 0) "W" else "E"}")
                        if (it.hasAltitude()) args += listOf("-EXIF:GPSAltitude=${kotlin.math.abs(it.altitude)}",
                            "-EXIF:GPSAltitudeRef#=${if (it.altitude < 0) 1 else 0}")
                        if (it.hasBearing()) args += listOf("-EXIF:GPSImgDirection=${it.bearing}", "-EXIF:GPSImgDirectionRef=T")
                    }
                }
                NativeMetadataRuntime.execute(context, args + copy.absolutePath)
                if (type == "CRW" && location != null) {
                    // 整块导入 XMP 会覆盖同次命令中的 XMP 单字段设置，GPS 必须在导入后写入。
                    NativeMetadataRuntime.execute(context,
                        listOf("-overwrite_original") + CrwMetadataFields.gpsArguments(location) + copy.absolutePath)
                }
                val readback = File.createTempFile("native_verify_", ".xmp", copy.parentFile)
                try {
                    readback.writeText(readXmp(context, copy), Charsets.UTF_8)
                    check(MetadataVerifier.verifySidecar(readback, effectiveTemplate)) { "Native XMP verification failed" }
                } finally { readback.delete() }
                val exif = read(context, copy, listOf("-$dateTag"))
                check(exif.value("DateTimeOriginal") == date) { "Native capture date changed" }
                if (location != null) {
                    val actual = requireNotNull(readLocation(context, copy)) { "Native GPS readback failed" }
                    check(kotlin.math.abs(actual.latitude - location.latitude) < 0.000001 &&
                        kotlin.math.abs(actual.longitude - location.longitude) < 0.000001) { "Native GPS coordinate verification failed" }
                    if (location.hasAltitude()) check(actual.hasAltitude() &&
                        kotlin.math.abs(actual.altitude - location.altitude) < 0.001) { "Native altitude verification failed" }
                    if (location.hasBearing()) check(actual.hasBearing() &&
                        kotlin.math.abs(actual.bearing - location.bearing) < 0.001f) { "Native direction verification failed" }
                }
            } finally { xmp.delete() }
        }
        return MetadataVerificationResult(true, false, true, null)
    }

    /** 所有写入（含签名）结束后再读取最终交付物。 */
    suspend fun verifyWritten(context: Context, file: File, template: MetadataTemplate, location: Location?) {
        val scratch = File.createTempFile("native_final_verify_", ".xmp", file.parentFile)
        try {
            scratch.writeText(readXmp(context, file), Charsets.UTF_8)
            check(MetadataVerifier.verifySidecar(scratch, NativeIptcTemplate.forXmp(template))) {
                "Final native metadata verification failed"
            }
        } finally { scratch.delete() }
        location?.let { expected ->
            val actual = requireNotNull(readLocation(context, file)) { "Final native GPS missing" }
            check(kotlin.math.abs(actual.latitude - expected.latitude) < 0.000001 &&
                kotlin.math.abs(actual.longitude - expected.longitude) < 0.000001 &&
                (!expected.hasAltitude() || (actual.hasAltitude() && kotlin.math.abs(actual.altitude - expected.altitude) < 0.001))) {
                "Final native GPS mismatch"
            }
        }
    }

    private fun selected(key: String, iptc: Boolean, exif: Boolean, person: Boolean): Boolean {
        val groups = key.substringBeforeLast(':').split(':')
        val tag = key.substringAfterLast(':')
        return (iptc && groups.any { it.startsWith("IPTC") || it.startsWith("XMP") }) ||
            (groups.any { it.startsWith("XMP") || it.startsWith("IPTC") } &&
                MetadataPrivacyRules.selectedName(tag, exif, person)) ||
            (person && (tag in personal || isSerial(tag))) ||
            (exif && ("GPS" in groups || tag.startsWith("GPS") ||
                (groups.any { it in setOf("ExifIFD", "IFD0", "IFD1") } && tag in technical)))
    }

    suspend fun countSelected(context: Context, file: File, iptc: Boolean, exif: Boolean, person: Boolean): Int {
        val values = read(context, file, listOf("-ee", "-G1:3", "-EXIF:All", "-XMP:All", "-IPTC:All", "-MakerNotes:All"))
        return values.keys().asSequence().count { selected(it, iptc, exif, person) && !anonymous(values, it) }
    }

    private fun anonymous(values: JSONObject, key: String): Boolean {
        val value = values.optString(key).trim()
        val tag = key.substringAfterLast(':')
        return value.isEmpty() || CrwMetadataFields.isEmptyCaptureDate(key, value) ||
            ((isSerial(tag) || tag == "ImageUniqueID") && value.matches(Regex("0+(?:\\s+0+)*"))) ||
            (tag == "SceneCaptureType" && key.substringBeforeLast(':').split(':').any { it.startsWith("Doc") } && value == "0")
    }

    private fun isSerial(tag: String): Boolean = tag.matches(Regex(".*SerialNumber[0-9]*"))

    suspend fun scrub(context: Context, file: File, iptc: Boolean = true, exif: Boolean = true, person: Boolean = true) {
        change(context, file) { copy, type ->
            val args = mutableListOf("-overwrite_original")
            if (iptc) args += "-IPTC:All="
            if (iptc) args += "-XMP:All="
            if (exif) args += "-GPS:All="
            if (exif && type == "CRW") args += "-${CrwMetadataFields.GPS_GROUP}:GPS*="
            val existing = read(context, copy, listOf("-EXIF:All", "-MakerNotes:All", "-XMP:All", "-IPTC:All"))
            for (key in existing.keys().asSequence()) {
                if (selected(key, false, exif, person) && !(iptc && (key.startsWith("XMP") || key.startsWith("IPTC"))) && !anonymous(existing, key) && key.matches(Regex("[A-Za-z0-9_-]+:[A-Za-z0-9_-]+"))) {
                    val tag = key.substringAfter(':')
                    val fixedField = if (type == "CRW") CrwMetadataFields.clearFixedField(key) else null
                    // 固定布局的相机序列号无法删除，匿名化后仍核对解密白平衡和显影参数。
                    args += when {
                        fixedField != null -> fixedField
                        key == "Sony:InternalSerialNumber" -> {
                            val parts = existing.optString(key).trim().split(Regex("\\s+"))
                            check(parts.size in 4..6 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
                                "Unsupported Sony internal serial layout"
                            }
                            "-$key#=" + parts.joinToString(" ") { "0" }
                        }
                        isSerial(tag) -> "-$key=0"
                        tag == "ImageUniqueID" -> "-$key=${"0".repeat(32)}"
                        key.substringBefore(':') !in setOf("ExifIFD", "IFD0", "IFD1", "GPS") &&
                            tag in setOf("Artist", "Copyright", "ImageDescription", "UserComment", "CameraOwnerName", "OwnerName") -> "-$key^="
                        else -> "-$key="
                    }
                }
            }
            if (args.size > 1) NativeMetadataRuntime.execute(context, args + copy.absolutePath)
            check(countSelected(context, copy, iptc, exif, person) == 0) {
                "Native privacy scrub incomplete: protected or non-writable camera fields remain"
            }
        }
    }
}
