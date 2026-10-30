package com.wkq.dev.document

import java.io.File
import java.math.BigDecimal
import com.wkq.roof.export.validXmlText

/** 与具体业务无关的记录快照；时间由宿主格式化，表格首行作为表头，数字类型由宿主显式选择。 */
data class ExportRecord(
    val title: String,
    val recordedAt: String = "",
    val notes: String = "",
    val fields: List<RecordField> = emptyList(),
    val rows: List<List<RecordCell>> = emptyList(),
    val images: List<RecordImage> = emptyList(),
    val company: RecordCompany = RecordCompany(),
    val person: RecordPerson = RecordPerson(),
    val signatures: List<RecordSignature> = emptyList(),
    val template: RecordTemplate = RecordTemplate(),
) {
    /** 校验可导出范围，拒绝超限而不静默截断内容。 */
    fun validate() {
        require(title.isNotBlank() && title.length <= 200)
        template.validate()
        require(signatures.size <= 20)
        require(signatures.all { it.name.isNotBlank() && it.name.length <= 200 })
        require(fields.size <= 100 && rows.size <= 1_000 && images.size <= 20)
        if (rows.isNotEmpty()) require(rows.first().size in 1..8 && rows.all { it.size == rows.first().size })
        val texts = listOf(title, recordedAt, notes) + fields.flatMap { listOf(it.name, it.value) } +
            rows.flatten().map { it.value } + images.map { it.caption } +
            listOf(company.name, company.address, company.phone, company.email, company.website, company.registrationNumber,
                person.name, person.role, person.organization, person.phone, person.email) +
            signatures.flatMap { listOf(it.name, it.role, it.signedAt, it.statement) }
        require(texts.all { it.length <= 32_767 } && texts.sumOf { it.length.toLong() } <= 1_000_000)
        require(texts.all { it.validXmlText() == it }) { "Record contains unsupported XML characters" }
        rows.flatten().filter { it.numeric }.forEach {
            require(it.value.toBigDecimalOrNull() != null && it.value.toDoubleOrNull()?.isFinite() == true)
            require(BigDecimal(it.value).precision() <= 15) { "Use text for high precision identifiers" }
        }
        images.forEach { require(it.file.isFile && it.file.extension.lowercase(java.util.Locale.ROOT) in setOf("jpg", "jpeg", "png")) }
        listOfNotNull(company.logo, person.avatar).plus(signatures.mapNotNull { it.image }).forEach(::requireImage)
        if (rows.isNotEmpty()) require(template.detailColumns.all { it < rows.first().size })
    }
}

/** 记录基本信息项，名称与值均由宿主提供。 */
data class RecordField(val name: String, val value: String)

/** 表格单元格；默认文本以保留编号和前导零，不自动猜测数字。 */
data class RecordCell(val value: String, val numeric: Boolean = false)

/** 记录图片附件；当前支持 JPEG、PNG，说明随图片写入三种格式。 */
data class RecordImage(val file: File, val caption: String = "")

/** 记录导出的三种文件格式。 */
enum class RecordFormat(val extension: String) { PDF("pdf"), EXCEL("xlsx"), WORD("docx") }
