package com.wkq.dev.metadata.model

data class MetadataTemplate(
    val id: String,
    val displayName: String = "",
    val templateType: String = "",
    val creator: String = "",
    val title: String = "",
    val subtitle: String = "",
    val artist: String = "",
    val jobTitle: String = "",
    val state: String = "",
    val email: String = "",
    val iptcAuthor: String = "",
    val writer: String = "",          // IPTC Writer/Editor [2:122]：说明撰写人
    val category: String = "",
    val isoCountryCode: String = "",
    val credit: String = "",
    val source: String = "",
    val copyrightStatus: String = "",
    val rightsUsageTerms: String = "",
    val copyright: String = "",
    val copyrightOwner: String = "",
    val country: String = "",
    val city: String = "",
    val location: String = "",
    val headline: String = "",
    val caption: String = "",
    val keywords: List<String> = emptyList(),
    val customFields: Map<String, String> = emptyMap(),
    val enableAntiTamper: Boolean = false,
    val autoUpload: Boolean = true,
    val isLibraryPreset: Boolean = false,
    val displayNameRes: String? = null,
    val descriptionRes: String? = null,
    val headlineRes: String? = null,
    val captionRes: String? = null,
    val rightsUsageTermsRes: String? = null,
    val copyrightStatusRes: String? = null,
    val enabledFields: List<String> = emptyList(),
    val standardFields: Map<String, String> = emptyMap(),
    val iptcDocument: IptcDocument = IptcDocument.EMPTY,
    val visibleStandardFields: List<String> = emptyList(),
    val workflowPostCaptureAction: String = WORKFLOW_ACTION_QUICK_EDIT,
    val workflowVersion: Int = 1,
    val workflowRequiredFields: List<String> = emptyList(),
    val workflowBatchFields: List<String> = emptyList(),
    val workflowPerPhotoFields: List<String> = emptyList(),
    val workflowScrubPrivacy: Boolean = false,
    val workflowCompressForUpload: Boolean = false,
    val associatedBylineId: String? = null,
    val associatedServerId: String? = null,
    val workflowAutoLocation: Boolean = false,
    // 快捷编辑只更新本次填写内容，旧预设继续使用替换策略。
    val preserveUnspecifiedFields: Boolean = false
) {
    fun isFieldEnabled(field: String): Boolean {
        if (enabledFields.isEmpty()) return !MetadataFieldDefinitions.isStandardField(field)
        if (enabledFields.contains(field)) return true

        val parentField = when (field) {
            MetadataFieldDefinitions.IIM_HEADLINE,
            MetadataFieldDefinitions.PHOTOSHOP_HEADLINE -> "headline"

            MetadataFieldDefinitions.IIM_CAPTION,
            MetadataFieldDefinitions.DC_DESCRIPTION -> "caption"

            MetadataFieldDefinitions.IIM_KEYWORDS,
            MetadataFieldDefinitions.DC_SUBJECT -> "keywords"

            MetadataFieldDefinitions.IIM_BYLINE,
            MetadataFieldDefinitions.DC_CREATOR -> "iptcAuthor"

            MetadataFieldDefinitions.IIM_WRITER -> "writer"

            MetadataFieldDefinitions.IIM_CATEGORY -> "category"

            MetadataFieldDefinitions.IIM_CITY,
            MetadataFieldDefinitions.PHOTOSHOP_CITY,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY -> "city"

            MetadataFieldDefinitions.IIM_SUBLOCATION,
            MetadataFieldDefinitions.IPTC_CORE_LOCATION -> "location"

            MetadataFieldDefinitions.IIM_STATE,
            MetadataFieldDefinitions.PHOTOSHOP_STATE,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_REGION -> "state"

            MetadataFieldDefinitions.IIM_COUNTRY_NAME,
            MetadataFieldDefinitions.PHOTOSHOP_COUNTRY,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY -> "country"

            MetadataFieldDefinitions.IIM_COUNTRY_CODE,
            MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY_CODE -> "isoCountryCode"

            MetadataFieldDefinitions.IIM_CREDIT,
            MetadataFieldDefinitions.PHOTOSHOP_CREDIT -> "credit"

            MetadataFieldDefinitions.IIM_SOURCE,
            MetadataFieldDefinitions.PHOTOSHOP_SOURCE,
            MetadataFieldDefinitions.DC_SOURCE -> "source"

            MetadataFieldDefinitions.IIM_COPYRIGHT -> "copyright"

            MetadataFieldDefinitions.DC_RIGHTS -> "rightsUsageTerms"

            MetadataFieldDefinitions.PLUS_COPYRIGHT_OWNER_NAME -> "copyrightOwner"

            MetadataFieldDefinitions.IIM_CONTACT,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_EMAIL -> "email"

            MetadataFieldDefinitions.IIM_OBJECT_NAME,
            MetadataFieldDefinitions.DC_TITLE -> "title"

            else -> null
        }
        return parentField != null && enabledFields.contains(parentField)
    }

    fun standardValue(field: String): String {
        val directValue = when (field) {
            MetadataFieldDefinitions.IIM_HEADLINE,
            MetadataFieldDefinitions.PHOTOSHOP_HEADLINE -> headline

            MetadataFieldDefinitions.IIM_CAPTION,
            MetadataFieldDefinitions.DC_DESCRIPTION -> caption

            MetadataFieldDefinitions.IIM_KEYWORDS,
            MetadataFieldDefinitions.DC_SUBJECT -> keywords.joinToString(",")

            MetadataFieldDefinitions.IIM_BYLINE,
            MetadataFieldDefinitions.DC_CREATOR -> iptcAuthor.ifBlank { creator }

            MetadataFieldDefinitions.IIM_WRITER -> writer

            MetadataFieldDefinitions.IIM_CATEGORY -> category

            MetadataFieldDefinitions.IIM_CITY,
            MetadataFieldDefinitions.PHOTOSHOP_CITY,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY -> city

            MetadataFieldDefinitions.IIM_SUBLOCATION,
            MetadataFieldDefinitions.IPTC_CORE_LOCATION -> location

            MetadataFieldDefinitions.IIM_STATE,
            MetadataFieldDefinitions.PHOTOSHOP_STATE,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_REGION -> state

            MetadataFieldDefinitions.IIM_COUNTRY_NAME,
            MetadataFieldDefinitions.PHOTOSHOP_COUNTRY,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY -> country

            MetadataFieldDefinitions.IIM_COUNTRY_CODE,
            MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY_CODE -> isoCountryCode

            MetadataFieldDefinitions.IIM_CREDIT,
            MetadataFieldDefinitions.PHOTOSHOP_CREDIT -> credit

            MetadataFieldDefinitions.IIM_SOURCE,
            MetadataFieldDefinitions.PHOTOSHOP_SOURCE,
            MetadataFieldDefinitions.DC_SOURCE -> source

            MetadataFieldDefinitions.IIM_COPYRIGHT -> copyright

            // Dublin Core 的 rights 是版权声明；使用条款应只写入 xmpRights:UsageTerms。
            MetadataFieldDefinitions.DC_RIGHTS -> copyright

            MetadataFieldDefinitions.PLUS_COPYRIGHT_OWNER_NAME -> copyrightOwner

            MetadataFieldDefinitions.IIM_CONTACT,
            MetadataFieldDefinitions.IPTC_CORE_CREATOR_EMAIL -> email

            MetadataFieldDefinitions.IIM_OBJECT_NAME,
            MetadataFieldDefinitions.DC_TITLE -> title

            else -> null
        }
        val standardFieldValue = standardFields[field].orEmpty()
            .takeUnless(String::isQuestionMarkPlaceholder)
            .orEmpty()
        return standardFieldValue.takeIf { it.isNotBlank() }
            ?: directValue
                ?.takeUnless(String::isQuestionMarkPlaceholder)
                ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun shouldWriteStandardField(field: String): Boolean {
        return MetadataFieldDefinitions.isWriteSupported(field) && isFieldEnabled(field)
    }

    fun enabledIptcDocumentFields(): Set<String> {
        return (standardFields.keys + iptcDocument.fields.keys)
            .filter(::shouldWriteStandardField)
            .toSet()
    }

    fun shouldOpenEditorAfterCapture(): Boolean {
        return workflowPostCaptureAction != WORKFLOW_ACTION_DIRECT_PROCESS ||
            resolvedPerPhotoFields().any(workflowRequiredFields::contains)
    }

    fun resolvedBatchFields(): Set<String> {
        if (workflowVersion >= 2) return workflowBatchFields.toSet()
        return workflowBatchFields.toSet().ifEmpty {
            setOf("event", "jobIdentifier", "keywords", "category", "intellectualGenre")
        }
    }

    fun resolvedPerPhotoFields(): Set<String> {
        if (workflowVersion >= 2) return workflowPerPhotoFields.toSet()
        return workflowPerPhotoFields.toSet().ifEmpty {
            workflowRequiredFields.filter { it in PER_PHOTO_FIELDS }.toSet()
                .ifEmpty { setOf("headline", "caption", "persons", "locationShown") }
        }
    }

    companion object {
        const val WORKFLOW_ACTION_QUICK_EDIT = "quick_edit"
        const val WORKFLOW_ACTION_DIRECT_PROCESS = "direct_process"
        private val PER_PHOTO_FIELDS =
            setOf("headline", "caption", "persons", "locationShown", "keywords")
    }
}

private fun String.isQuestionMarkPlaceholder(): Boolean {
    val compact = filterNot(Char::isWhitespace)
    return compact.isNotEmpty() && compact.all { it == '?' || it == '？' }
}
