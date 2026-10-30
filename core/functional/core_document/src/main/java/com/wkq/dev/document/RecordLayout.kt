package com.wkq.dev.document

/** 三格式共享的语义内容，排版器自行映射页面、段落或单元格。 */
internal sealed interface RecordContent {
    /** 单段文字，标题与普通文字样式分别处理。 */
    data class Text(val value: String, val title: Boolean = false) : RecordContent
    /** 矩形表格，可标记首行表头。 */
    data class Table(val rows: List<List<RecordCell>>, val header: Boolean = false) : RecordContent
    /** 保留图片用途，签名与 Logo 不参与照片网格。 */
    data class Picture(val image: RecordImage, val gallery: Boolean = false) : RecordContent
}

/** 一个可排序、隐藏与重命名的记录区块。 */
internal data class RecordSection(val block: RecordBlock, val label: String, val content: List<RecordContent>)

/** 将记录与模板编译为三格式共用内容，统一执行字段与列选择。 */
internal object RecordLayout {
    /** 生成模板指定顺序的区块，不让任一格式独自遗漏资料或签名。 */
    fun sections(record: ExportRecord): List<RecordSection> = record.template.blocks.mapNotNull { block ->
        val contents = buildList<RecordContent> {
            when (block) {
                RecordBlock.TITLE -> {
                    add(RecordContent.Text(record.title, true))
                    if (record.recordedAt.isNotBlank()) add(RecordContent.Text(record.recordedAt))
                }
                RecordBlock.COMPANY -> {
                    record.company.logo?.let { add(RecordContent.Picture(RecordImage(it, record.company.name))) }
                    val c = record.company
                    profile(listOf("company_name" to c.name, "address" to c.address, "phone" to c.phone,
                        "email" to c.email, "website" to c.website, "registration_number" to c.registrationNumber), record)?.let(::add)
                }
                RecordBlock.PERSON -> {
                    record.person.avatar?.let { add(RecordContent.Picture(RecordImage(it, record.person.name))) }
                    val p = record.person
                    profile(listOf("person_name" to p.name, "role" to p.role, "organization" to p.organization,
                        "phone" to p.phone, "email" to p.email), record)?.let(::add)
                }
                RecordBlock.FIELDS -> {
                    val fields = record.fields.filter { record.template.fieldNames.isEmpty() || it.name in record.template.fieldNames }
                    if (fields.isNotEmpty()) add(RecordContent.Table(fields.map { listOf(RecordCell(it.name), RecordCell(it.value)) }))
                }
                RecordBlock.NOTES -> if (record.notes.isNotBlank()) add(RecordContent.Text(record.notes))
                RecordBlock.DETAILS -> if (record.rows.isNotEmpty()) {
                    val columns = record.template.detailColumns.ifEmpty { record.rows.first().indices.toList() }
                    add(RecordContent.Table(record.rows.map { row -> columns.map { row[it] } }, true))
                }
                RecordBlock.IMAGES -> record.images.forEach { add(RecordContent.Picture(it, true)) }
                RecordBlock.SIGNATURES -> record.signatures.forEach {
                    profile(listOf("signer" to it.name, "role" to it.role, "signed_at" to it.signedAt,
                        "statement" to it.statement), record)?.let(::add)
                    it.image?.let { file -> add(RecordContent.Picture(RecordImage(file, it.name))) }
                }
            }
        }
        if (contents.isEmpty()) null else RecordSection(block, record.template.labels[block].orEmpty(), contents)
    }

    /** 过滤空资料项并使用宿主提供的字段标签，没有标签时使用稳定英文键。 */
    private fun profile(values: List<Pair<String, String>>, record: ExportRecord): RecordContent.Table? {
        val rows = values.filter { it.second.isNotBlank() }.map {
            listOf(RecordCell(record.template.fieldLabels[it.first] ?: it.first.replace('_', ' ')), RecordCell(it.second))
        }
        return rows.takeIf { it.isNotEmpty() }?.let { RecordContent.Table(it) }
    }
}
