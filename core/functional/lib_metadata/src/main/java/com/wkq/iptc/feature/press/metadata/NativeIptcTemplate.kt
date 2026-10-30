package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataTemplate
import com.wkq.dev.metadata.model.MetadataFieldDefinitions as F

/** 将有标准对应关系的旧式IIM字段映射到XMP；无对应的要求不能静默丢弃。 */
internal object NativeIptcTemplate {
    private val aliases = mapOf(
        F.IIM_OBJECT_NAME to F.DC_TITLE, F.IIM_BYLINE to F.DC_CREATOR,
        F.IIM_HEADLINE to F.PHOTOSHOP_HEADLINE, F.IIM_CAPTION to F.DC_DESCRIPTION,
        F.IIM_KEYWORDS to F.DC_SUBJECT, F.IIM_COPYRIGHT to F.DC_RIGHTS,
        F.IIM_CITY to F.PHOTOSHOP_CITY, F.IIM_STATE to F.PHOTOSHOP_STATE,
        F.IIM_COUNTRY_NAME to F.PHOTOSHOP_COUNTRY,
        F.IIM_COUNTRY_CODE to F.IPTC_CORE_COUNTRY_CODE,
        F.IIM_SUBLOCATION to F.IPTC_CORE_LOCATION,
        F.IIM_CREDIT to F.PHOTOSHOP_CREDIT, F.IIM_SOURCE to F.PHOTOSHOP_SOURCE,
        F.IIM_JOB_IDENTIFIER to F.PHOTOSHOP_TRANSMISSION_REFERENCE
    )
    private val direct = setOf(F.IIM_CATEGORY, F.IIM_URGENCY, F.IIM_SUPPLEMENTAL_CATEGORY,
        F.IIM_WRITER, F.IIM_BYLINE_TITLE, F.IIM_SPECIAL_INSTRUCTIONS)

    fun forXmp(template: MetadataTemplate): MetadataTemplate {
        val active = (template.standardFields.keys + template.iptcDocument.fields.keys)
            .filter { it.startsWith("iim.") && template.shouldWriteStandardField(it) }
            // 署名合并可能留下空联系字段；空占位不构成无法表达的写入请求。
            .filter { template.standardValue(it).isNotBlank() ||
                template.iptcDocument.value(it)?.isBlank() == false }
        require(active.all { it in aliases || it in direct }) {
            "Selected legacy IPTC fields cannot be represented in this format"
        }
        val fields = template.standardFields.toMutableMap()
        val enabled = template.enabledFields.toMutableSet()
        active.forEach { source ->
            aliases[source]?.let { target ->
                if (fields[target].isNullOrBlank()) {
                    fields[target] = template.standardValue(source).ifBlank { template.iptcDocument.text(source) }
                }
                enabled += target
            }
        }
        return template.copy(standardFields = fields, enabledFields = enabled.toList())
    }
}
