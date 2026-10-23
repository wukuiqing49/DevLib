package com.wkq.dev.metadata.model

/** 通用元数据值快照，不包含照片数据库主键、账号、上传状态或处理队列字段。 */
data class MetadataRecord(
    val headline: String = "",
    val caption: String = "",
    val title: String = "",
    val keywords: String = "",
    val creator: String = "",
    val iptcAuthor: String = "",
    val writer: String = "",
    val category: String = "",
    val state: String = "",
    val isoCountryCode: String = "",
    val credit: String = "",
    val source: String = "",
    val copyrightStatus: String = "",
    val rightsUsageTerms: String = "",
    val copyright: String = "",
    val country: String = "",
    val city: String = "",
    val location: String = "",
    val customFields: String = "",
    val templateId: String = "",
    val templateName: String = "",
)
