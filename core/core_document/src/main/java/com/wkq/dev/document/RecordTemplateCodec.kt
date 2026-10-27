package com.wkq.dev.document

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** 有版本的模板交换协议，图片嵌入 JSON，导入不接受任意本地路径；不包含公司或签名隐私。 */
object RecordTemplateCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    const val MAX_BYTES = 4 * 1024 * 1024

    /** 编码模板与可选水印图片，名称、顺序、样式及标签完整保留。 */
    fun encode(template: RecordTemplate): String {
        template.validate()
        val root = JsonObject().apply {
            addProperty("schemaVersion", template.schemaVersion)
            addProperty("id", template.id); addProperty("revision", template.revision); addProperty("name", template.name)
            add("blocks", gson.toJsonTree(template.blocks.map { it.name }))
            add("labels", gson.toJsonTree(template.labels.mapKeys { it.key.name }))
            add("fieldLabels", gson.toJsonTree(template.fieldLabels))
            add("detailColumns", gson.toJsonTree(template.detailColumns)); add("fieldNames", gson.toJsonTree(template.fieldNames))
            addProperty("imageColumns", template.imageColumns)
            add("style", gson.toJsonTree(template.style))
            add("watermark", JsonObject().apply {
                val w = template.watermark
                addProperty("kind", w.kind.name); addProperty("text", w.text)
                addProperty("opacityPercent", w.opacityPercent); addProperty("rotationDegrees", w.rotationDegrees)
                addProperty("widthPercent", w.widthPercent); addProperty("xPercent", w.xPercent); addProperty("yPercent", w.yPercent)
                addProperty("everyPage", w.everyPage)
                if (w.kind == WatermarkKind.IMAGE) {
                    val file = checkNotNull(w.image)
                    addProperty("imageExtension", file.extension.lowercase(java.util.Locale.ROOT))
                    addProperty("imageData", Base64.getEncoder().encodeToString(file.readBytes()))
                }
            })
        }
        return gson.toJson(root).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    /** 严格解析模板，水印按内容散列落到宿主目录；限制大小并拒绝未知枚举与版本。后台线程调用。 */
    fun decode(json: String, assetsDirectory: File): RecordTemplate {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schemaVersion").exactInt() == 1)
        val style = root.getAsJsonObject("style")
        val w = root.getAsJsonObject("watermark")
        require(!w.has("image")) { "Local paths are not accepted" }
        val kind = WatermarkKind.valueOf(w.get("kind").asString)
        var image: File? = null
        if (kind == WatermarkKind.IMAGE) {
            val ext = w.get("imageExtension").asString
            require(ext in setOf("png", "jpg", "jpeg"))
            val bytes = Base64.getDecoder().decode(w.get("imageData").asString)
            require(bytes.size in 1..2 * 1024 * 1024)
            check(assetsDirectory.isDirectory || assetsDirectory.mkdirs())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            image = File(assetsDirectory, "$hash.$ext")
            if (!image.exists()) com.wkq.photo.core.utils.AtomicFileOutput.write(image) { it.writeBytes(bytes) }
        }
        val result = RecordTemplate(
            id = root.get("id").asString, revision = root.get("revision").exactInt(), name = root.get("name").asString,
            blocks = root.getAsJsonArray("blocks").map { RecordBlock.valueOf(it.asString) },
            labels = root.getAsJsonObject("labels").entrySet().associate { RecordBlock.valueOf(it.key) to it.value.asString },
            fieldLabels = root.getAsJsonObject("fieldLabels").entrySet().associate { it.key to it.value.asString },
            detailColumns = root.getAsJsonArray("detailColumns").map { it.exactInt() },
            fieldNames = root.getAsJsonArray("fieldNames").map { it.asString }, imageColumns = root.get("imageColumns").exactInt(),
            style = RecordStyle(style.get("primaryColor").asString, style.get("headerFill").asString,
                style.get("bodySize").exactInt(), style.get("titleSize").exactInt(), style.get("marginPoints").exactInt(),
                style.get("landscape").asBoolean, style.get("header").asString, style.get("footer").asString,
                style.get("showPageNumbers").asBoolean),
            watermark = RecordWatermark(kind, w.get("text").asString, image, w.get("opacityPercent").exactInt(),
                w.get("rotationDegrees").exactInt(), w.get("widthPercent").exactInt(), w.get("xPercent").exactInt(),
                w.get("yPercent").exactInt(), w.get("everyPage").asBoolean),
        )
        result.validate()
        return result
    }
    /** 只接受精确整数字面量，拒绝小数截断造成版本或尺寸配置误判。 */
    private fun JsonElement.exactInt(): Int {
        val value = asString
        require(value.matches(Regex("-?(0|[1-9][0-9]*)")))
        return value.toInt()
    }

}
