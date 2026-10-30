package com.wkq.dev.metadata.model

/**
 * 面向 IPTC Photo Metadata 全量 schema 的通用数据容器。
 *
 * 旧版 standardFields 只适合简单文本字段；这里预留文本、多值、多语言、
 * 结构体和结构体列表，后续可以承载 LocationShown、Licensor、ImageRegion 等复杂字段。
 */
data class IptcDocument(
    val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    val fields: Map<String, IptcValue> = emptyMap()
) {
    fun isEmpty(): Boolean = fields.isEmpty()

    fun value(key: String): IptcValue? = fields[key]

    fun text(key: String): String = value(key)?.asText().orEmpty()

    fun list(key: String): List<String> = value(key)?.asList().orEmpty()

    fun imageRegions(): List<IptcImageRegion> =
        IptcImageRegion.fromValue(value(MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION))

    fun withText(key: String, value: String): IptcDocument {
        val clean = value.trim()
        return copy(
            fields = if (clean.isBlank()) {
                fields - key
            } else {
                fields + (key to IptcValue.text(clean))
            }
        )
    }

    fun mergedWith(other: IptcDocument): IptcDocument {
        if (other.fields.isEmpty()) return this
        if (fields.isEmpty()) return other
        return copy(
            schemaVersion = other.schemaVersion.ifBlank { schemaVersion },
            fields = fields + other.fields
        )
    }

    fun withImageRegions(regions: List<IptcImageRegion>): IptcDocument {
        val clean = regions.filter { !it.isBlank() }
        return copy(
            fields = if (clean.isEmpty()) {
                fields - MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION
            } else {
                fields + (
                    MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION to IptcValue.structList(
                        clean.map(IptcImageRegion::toValueMap)
                    )
                    )
            }
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = "2025.1"
        val EMPTY = IptcDocument()
    }
}

data class IptcValue(
    val kind: String = KIND_TEXT,
    val text: String = "",
    val values: List<String> = emptyList(),
    val langAlt: Map<String, String> = emptyMap(),
    val fields: Map<String, IptcValue> = emptyMap(),
    val items: List<Map<String, IptcValue>> = emptyList()
) {
    fun isBlank(): Boolean = when (kind) {
        KIND_TEXT -> text.isBlank()
        KIND_URI -> text.isBlank()
        KIND_BOOLEAN -> text.isBlank()
        KIND_TEXT_LIST -> values.none { it.isNotBlank() }
        KIND_LANG_ALT -> langAlt.values.none { it.isNotBlank() }
        KIND_STRUCT -> fields.values.all { it.isBlank() }
        KIND_STRUCT_LIST -> items.all { item -> item.values.all { it.isBlank() } }
        else -> text.isBlank() && values.none { it.isNotBlank() }
    }

    fun asText(): String = when (kind) {
        KIND_TEXT_LIST -> values.joinToString(", ")
        KIND_LANG_ALT -> langAlt["x-default"].orEmpty().ifBlank {
            langAlt.values.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        else -> text
    }

    fun asList(): List<String> = when (kind) {
        KIND_TEXT_LIST -> values.map(String::trim).filter(String::isNotBlank)
        KIND_LANG_ALT -> langAlt.values.map(String::trim).filter(String::isNotBlank)
        else -> text.split(",", "\uFF0C", ";", "\n")
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_URI = "uri"
        const val KIND_BOOLEAN = "boolean"
        const val KIND_TEXT_LIST = "textList"
        const val KIND_LANG_ALT = "langAlt"
        const val KIND_STRUCT = "struct"
        const val KIND_STRUCT_LIST = "structList"

        fun text(value: String): IptcValue = IptcValue(text = value.trim())

        fun uri(value: String): IptcValue = IptcValue(kind = KIND_URI, text = value.trim())

        fun textList(values: List<String>): IptcValue = IptcValue(
            kind = KIND_TEXT_LIST,
            values = values.map(String::trim).filter(String::isNotBlank).distinct()
        )

        fun langAlt(values: Map<String, String>): IptcValue = IptcValue(
            kind = KIND_LANG_ALT,
            langAlt = values.mapValues { it.value.trim() }.filterValues(String::isNotBlank)
        )

        fun struct(fields: Map<String, IptcValue>): IptcValue = IptcValue(
            kind = KIND_STRUCT,
            fields = fields.filterValues { !it.isBlank() }
        )

        fun structList(items: List<Map<String, IptcValue>>): IptcValue = IptcValue(
            kind = KIND_STRUCT_LIST,
            items = items.map { item -> item.filterValues { !it.isBlank() } }
                .filter { it.isNotEmpty() }
        )
    }
}

object IptcDocumentMapper {
    fun fromTemplate(template: MetadataTemplate): IptcDocument {
        return fromStandardFields(template.standardFields)
            .withCommonTemplateFields(template)
            .mergedWith(template.iptcDocument)
    }

    fun fromStandardFields(fields: Map<String, String>): IptcDocument {
        val values = fields.mapNotNull { (key, value) ->
            val clean = value.trim()
            if (clean.isBlank()) return@mapNotNull null
            val iptcValue = when (MetadataFieldDefinitions.valueType(key)) {
                MetadataFieldValueType.TEXT_LIST -> IptcValue.textList(clean.splitValues())
                MetadataFieldValueType.LANG_ALT -> IptcValue.langAlt(mapOf("x-default" to clean))
                MetadataFieldValueType.URI -> IptcValue.uri(clean)
                else -> IptcValue.text(clean)
            }
            key to iptcValue
        }.toMap()
        return IptcDocument(fields = values)
    }

    private fun IptcDocument.withCommonTemplateFields(template: MetadataTemplate): IptcDocument {
        var result = this
        result = result.putIfPresent(MetadataFieldDefinitions.DC_TITLE, template.title)
        result = result.putIfPresent(MetadataFieldDefinitions.IIM_HEADLINE, template.headline)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_HEADLINE, template.headline)
        result = result.putIfPresent(MetadataFieldDefinitions.DC_DESCRIPTION, template.caption)
        result = result.putIfPresent(MetadataFieldDefinitions.IIM_CAPTION, template.caption)
        if (template.keywords.isNotEmpty()) {
            result = result.copy(
                fields = result.fields + (
                    MetadataFieldDefinitions.DC_SUBJECT to IptcValue.textList(template.keywords)
                    )
            )
        }
        result = result.putIfPresent(MetadataFieldDefinitions.DC_CREATOR, template.iptcAuthor.ifBlank { template.creator })
        result = result.putIfPresent(MetadataFieldDefinitions.DC_RIGHTS, template.copyright)
        result = result.putIfPresent(MetadataFieldDefinitions.DC_SOURCE, template.source)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_CREDIT, template.credit)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_SOURCE, template.source)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_CITY, template.city)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_STATE, template.state)
        result = result.putIfPresent(MetadataFieldDefinitions.PHOTOSHOP_COUNTRY, template.country)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_CORE_LOCATION, template.location)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE, template.isoCountryCode)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_SUBLOCATION, template.location)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_CITY, template.city)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_REGION, template.state)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME, template.country)
        result = result.putIfPresent(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE, template.isoCountryCode)
        return result
    }

    private fun IptcDocument.putIfPresent(key: String, value: String): IptcDocument {
        if (value.isBlank() || fields.containsKey(key)) return this
        return withText(key, value)
    }

    private fun String.splitValues(): List<String> =
        split(",", "\uFF0C", ";", "\n").map(String::trim).filter(String::isNotBlank).distinct()
}

data class IptcImageRegion(
    val id: String = "",
    val name: String = "",
    val boundary: IptcImageRegionBoundary = IptcImageRegionBoundary(),
    val roles: List<String> = emptyList(),
    val contentTypes: List<String> = emptyList(),
    val persons: List<String> = emptyList(),
    val organisations: List<String> = emptyList()
) {
    fun isBlank(): Boolean =
        id.isBlank() &&
            name.isBlank() &&
            boundary.isBlank() &&
            roles.none(String::isNotBlank) &&
            contentTypes.none(String::isNotBlank) &&
            persons.none(String::isNotBlank) &&
            organisations.none(String::isNotBlank)

    fun toValueMap(): Map<String, IptcValue> = buildMap {
        putText(FIELD_ID, id)
        putText(FIELD_NAME, name)
        if (!boundary.isBlank()) put(FIELD_BOUNDARY, IptcValue.struct(boundary.toValueMap()))
        putList(FIELD_ROLES, roles)
        putList(FIELD_CONTENT_TYPES, contentTypes)
        putList(FIELD_PERSONS, persons)
        putList(FIELD_ORGANISATIONS, organisations)
    }

    companion object {
        const val FIELD_ID = "rId"
        const val FIELD_NAME = "name"
        const val FIELD_BOUNDARY = "regionBoundary"
        const val FIELD_ROLES = "rRole"
        const val FIELD_CONTENT_TYPES = "rCtype"
        const val FIELD_PERSONS = "personInImage"
        const val FIELD_ORGANISATIONS = "organisationInImageName"

        fun fromValue(value: IptcValue?): List<IptcImageRegion> {
            if (value == null || value.kind != IptcValue.KIND_STRUCT_LIST) return emptyList()
            return value.items.mapNotNull { item ->
                val boundary = IptcImageRegionBoundary.fromValue(item[FIELD_BOUNDARY])
                IptcImageRegion(
                    id = item[FIELD_ID].textValue(),
                    name = item[FIELD_NAME].textValue(),
                    boundary = boundary,
                    roles = item[FIELD_ROLES].listValue(),
                    contentTypes = item[FIELD_CONTENT_TYPES].listValue(),
                    persons = item[FIELD_PERSONS].listValue(),
                    organisations = item[FIELD_ORGANISATIONS].listValue()
                ).takeUnless(IptcImageRegion::isBlank)
            }
        }
    }
}

data class IptcImageRegionBoundary(
    val shape: String = SHAPE_RECTANGLE,
    val unit: String = UNIT_RELATIVE,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val radius: Double? = null,
    val vertices: List<IptcImageRegionVertex> = emptyList()
) {
    fun isBlank(): Boolean =
        shape.isBlank() &&
            unit.isBlank() &&
            x == null &&
            y == null &&
            width == null &&
            height == null &&
            radius == null &&
            vertices.isEmpty()

    fun toValueMap(): Map<String, IptcValue> = buildMap {
        putText(FIELD_SHAPE, shape)
        putText(FIELD_UNIT, unit)
        putNumber(FIELD_X, x)
        putNumber(FIELD_Y, y)
        putNumber(FIELD_WIDTH, width)
        putNumber(FIELD_HEIGHT, height)
        putNumber(FIELD_RADIUS, radius)
        if (vertices.isNotEmpty()) {
            put(FIELD_VERTICES, IptcValue.structList(vertices.map(IptcImageRegionVertex::toValueMap)))
        }
    }

    companion object {
        const val SHAPE_RECTANGLE = "rectangle"
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_POLYGON = "polygon"
        const val UNIT_RELATIVE = "relative"
        const val UNIT_PIXEL = "pixel"

        const val FIELD_SHAPE = "rbShape"
        const val FIELD_UNIT = "rbUnit"
        const val FIELD_X = "rbX"
        const val FIELD_Y = "rbY"
        const val FIELD_WIDTH = "rbW"
        const val FIELD_HEIGHT = "rbH"
        const val FIELD_RADIUS = "rbRx"
        const val FIELD_VERTICES = "rbVertices"

        fun fromValue(value: IptcValue?): IptcImageRegionBoundary {
            if (value == null || value.kind != IptcValue.KIND_STRUCT) return IptcImageRegionBoundary()
            val fields = value.fields
            return IptcImageRegionBoundary(
                shape = fields[FIELD_SHAPE].textValue().ifBlank { SHAPE_RECTANGLE },
                unit = fields[FIELD_UNIT].textValue().ifBlank { UNIT_RELATIVE },
                x = fields[FIELD_X].doubleValue(),
                y = fields[FIELD_Y].doubleValue(),
                width = fields[FIELD_WIDTH].doubleValue(),
                height = fields[FIELD_HEIGHT].doubleValue(),
                radius = fields[FIELD_RADIUS].doubleValue(),
                vertices = IptcImageRegionVertex.fromValue(fields[FIELD_VERTICES])
            )
        }
    }
}

data class IptcImageRegionVertex(
    val x: Double,
    val y: Double
) {
    fun toValueMap(): Map<String, IptcValue> = buildMap {
        putNumber(IptcImageRegionBoundary.FIELD_X, x)
        putNumber(IptcImageRegionBoundary.FIELD_Y, y)
    }

    companion object {
        fun fromValue(value: IptcValue?): List<IptcImageRegionVertex> {
            if (value == null || value.kind != IptcValue.KIND_STRUCT_LIST) return emptyList()
            return value.items.mapNotNull { item ->
                val x = item[IptcImageRegionBoundary.FIELD_X].doubleValue()
                val y = item[IptcImageRegionBoundary.FIELD_Y].doubleValue()
                if (x == null || y == null) null else IptcImageRegionVertex(x, y)
            }
        }
    }
}

private fun MutableMap<String, IptcValue>.putText(key: String, value: String) {
    if (value.isNotBlank()) put(key, IptcValue.text(value))
}

private fun MutableMap<String, IptcValue>.putList(key: String, values: List<String>) {
    val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
    if (clean.isNotEmpty()) put(key, IptcValue.textList(clean))
}

private fun MutableMap<String, IptcValue>.putNumber(key: String, value: Double?) {
    value ?: return
    put(key, IptcValue.text(value.toCleanString()))
}

private fun Double.toCleanString(): String {
    val raw = toString()
    return if (raw.endsWith(".0")) raw.dropLast(2) else raw
}

private fun IptcValue?.textValue(): String = this?.asText().orEmpty()

private fun IptcValue?.listValue(): List<String> = this?.asList().orEmpty()

private fun IptcValue?.doubleValue(): Double? = this?.asText()?.toDoubleOrNull()
