package com.wkq.dev.document

import com.wkq.roof.export.validXmlText
import java.io.File

/** 通用记录区块；排列顺序同时作用于 PDF、Word 正文与 Excel 工作表。 */
enum class RecordBlock { TITLE, COMPANY, PERSON, FIELDS, NOTES, DETAILS, IMAGES, SIGNATURES }

/** 水印模式，图片由模板包携带，不依赖外部产品资源。 */
enum class WatermarkKind { NONE, TEXT, IMAGE }

/** 水印布局；位置为页面或工作表打印区域的相对比例，透明度和旋转在图片层统一应用。 */
data class RecordWatermark(
    val kind: WatermarkKind = WatermarkKind.NONE,
    val text: String = "",
    val image: File? = null,
    val opacityPercent: Int = 12,
    val rotationDegrees: Int = -35,
    val widthPercent: Int = 60,
    val xPercent: Int = 50,
    val yPercent: Int = 50,
    val everyPage: Boolean = true,
)

/** 导出视觉配置，尺寸单位为 pt；三种格式按各自布局模型映射。 */
data class RecordStyle(
    val primaryColor: String = "2458C6",
    val headerFill: String = "E8F0FC",
    val bodySize: Int = 11,
    val titleSize: Int = 20,
    val marginPoints: Int = 36,
    val landscape: Boolean = false,
    val header: String = "",
    val footer: String = "",
    val showPageNumbers: Boolean = true,
)

/** 可保存和交换的模板；省略区块表示隐藏，列索引允许筛选及重排明细，标签由宿主本地化。 */
data class RecordTemplate(
    val schemaVersion: Int = 1,
    val id: String = "standard",
    val revision: Int = 1,
    val name: String = "Standard",
    val blocks: List<RecordBlock> = RecordBlock.entries,
    val labels: Map<RecordBlock, String> = emptyMap(),
    val fieldLabels: Map<String, String> = emptyMap(),
    val detailColumns: List<Int> = emptyList(),
    val fieldNames: List<String> = emptyList(),
    val imageColumns: Int = 1,
    val style: RecordStyle = RecordStyle(),
    val watermark: RecordWatermark = RecordWatermark(),
) {
    /** 校验模板版本、配置范围和图片，导入及导出共享同一契约。 */
    fun validate() {
        require(schemaVersion == 1 && revision in 1..1_000_000)
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && name.isNotBlank() && name.length <= 100)
        require(blocks.isNotEmpty() && blocks.distinct().size == blocks.size)
        require(detailColumns.size <= 8 && detailColumns.distinct().size == detailColumns.size && detailColumns.all { it in 0..7 })
        require(fieldNames.size <= 100 && fieldNames.distinct().size == fieldNames.size)
        require(imageColumns in 1..3)
        require(style.primaryColor.matches(Regex("[0-9A-Fa-f]{6}")) && style.headerFill.matches(Regex("[0-9A-Fa-f]{6}")))
        require(style.bodySize in 8..18 && style.titleSize in 16..32 && style.marginPoints in 18..72)
        require(watermark.opacityPercent in 1..40 && watermark.rotationDegrees in -90..90 && watermark.widthPercent in 10..90)
        require(watermark.xPercent in 10..90 && watermark.yPercent in 10..90)
        require(watermark.kind != WatermarkKind.TEXT || watermark.text.isNotBlank())
        if (watermark.kind == WatermarkKind.IMAGE) requireImage(watermark.image)
        require(fieldLabels.size <= 40 && fieldLabels.keys.all { it.matches(Regex("[a-z_]{1,40}")) })
        (listOf(name, style.header, style.footer, watermark.text) + labels.values + fieldLabels.values + fieldNames).forEach {
            require(it.length <= 200 && it.validXmlText() == it)
        }
    }
}

/** 公司资料快照，不负责账号、数据库或品牌授权。 */
data class RecordCompany(
    val name: String = "", val address: String = "", val phone: String = "", val email: String = "",
    val website: String = "", val registrationNumber: String = "", val logo: File? = null,
)

/** 个人资料快照，可包含头像，角色与联系方式由宿主提供。 */
data class RecordPerson(
    val name: String = "", val role: String = "", val organization: String = "",
    val phone: String = "", val email: String = "", val avatar: File? = null,
)

/** 签署记录，支持多人签署与手写签名图片，不表示证书数字签名。 */
data class RecordSignature(
    val name: String, val role: String = "", val signedAt: String = "", val statement: String = "", val image: File? = null,
)

/** 检查通用图片文件范围，实际解码有效性在导出预检再次验证。 */
internal fun requireImage(file: File?) {
    require(file != null && file.isFile && file.length() in 1..(2L * 1024 * 1024) &&
        file.extension.lowercase(java.util.Locale.ROOT) in setOf("png", "jpg", "jpeg"))
}
