package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.IptcDocument
import com.wkq.dev.metadata.model.IptcImageRegion
import com.wkq.dev.metadata.model.IptcImageRegionBoundary
import com.wkq.dev.metadata.model.IptcImageRegionVertex
import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.MetadataStandard
import com.wkq.dev.metadata.model.MetadataTemplate
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object MetadataTemplateImporter {

    data class ImportResult(
        val template: MetadataTemplate,
        val summary: ImportSummary
    )

    data class ImportSummary(
        val commonFieldCount: Int,
        val standardCounts: Map<MetadataStandard, Int>,
        val singlePhotoFieldCount: Int
    ) {
        val standardFieldCount: Int = standardCounts.values.sum()
        val totalFieldCount: Int = commonFieldCount + standardFieldCount
    }

    fun importFromPhoto(photoFile: File, displayName: String): Result<ImportResult> {
        return runCatching {
            val builder = TemplateBuilder(displayName)
            readIptc(photoFile, builder)
            readXmp(photoFile, builder)
            builder.build()
        }
    }

    /** 预览需要区分“确实没有字段”和“读取失败”，不沿用模板导入的降级策略。仅用于 JPEG。 */
    fun readJpegForPreview(photoFile: File): Result<MetadataTemplate> = runCatching {
        val builder = TemplateBuilder("")
        readIptc(photoFile, builder, strict = true)
        readXmp(photoFile, builder, strict = true)
        builder.build().template
    }

    private fun readIptc(photoFile: File, builder: TemplateBuilder, strict: Boolean = false) {
        val records = runCatching {
            val metadata = JpegImageParser().getPhotoshopMetadata(
                ByteSource.file(photoFile),
                JpegImagingParameters()
            )
            metadata?.photoshopApp13Data?.records.orEmpty()
        }
        (if (strict) records.getOrThrow() else records.getOrDefault(emptyList())).forEach { record ->
            val value = record.value.trim()
            if (value.isBlank()) return@forEach
            when (record.iptcType) {
                IptcTypes.RECORD_VERSION -> builder.standard(MetadataFieldDefinitions.IIM_RECORD_VERSION, value)
                IptcTypes.OBJECT_TYPE_REFERENCE -> builder.standard(MetadataFieldDefinitions.IIM_OBJECT_TYPE, value)
                IptcTypes.OBJECT_ATTRIBUTE_REFERENCE -> builder.standard(MetadataFieldDefinitions.IIM_OBJECT_ATTRIBUTE, value)
                IptcTypes.OBJECT_NAME -> {
                    builder.standard(MetadataFieldDefinitions.IIM_OBJECT_NAME, value)
                }
                IptcTypes.EDIT_STATUS -> builder.standard(MetadataFieldDefinitions.IIM_EDIT_STATUS, value)
                IptcTypes.URGENCY -> builder.standard(MetadataFieldDefinitions.IIM_URGENCY, value)
                IptcTypes.CATEGORY -> {
                    builder.common("category", value)
                    builder.standard(MetadataFieldDefinitions.IIM_CATEGORY, value)
                }
                IptcTypes.SUPPLEMENTAL_CATEGORY -> builder.appendStandard(MetadataFieldDefinitions.IIM_SUPPLEMENTAL_CATEGORY, value)
                IptcTypes.KEYWORDS -> {
                    builder.keyword(value)
                    builder.appendStandard(MetadataFieldDefinitions.IIM_KEYWORDS, value)
                }
                IptcTypes.SPECIAL_INSTRUCTIONS -> builder.standard(MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS, value)
                IptcTypes.DATE_CREATED -> builder.standard(MetadataFieldDefinitions.IIM_DATE_CREATED, value)
                IptcTypes.TIME_CREATED -> builder.standard(MetadataFieldDefinitions.IIM_TIME_CREATED, value)
                IptcTypes.BYLINE -> {
                    builder.common("iptcAuthor", value)
                    builder.common("creator", value)
                    builder.standard(MetadataFieldDefinitions.IIM_BYLINE, value)
                }
                IptcTypes.BYLINE_TITLE -> builder.standard(MetadataFieldDefinitions.IIM_BYLINE_TITLE, value)
                IptcTypes.CITY -> {
                    builder.common("city", value)
                    builder.standard(MetadataFieldDefinitions.IIM_CITY, value)
                }
                IptcTypes.SUBLOCATION -> builder.standard(MetadataFieldDefinitions.IIM_SUBLOCATION, value)
                IptcTypes.PROVINCE_STATE -> {
                    builder.common("state", value)
                    builder.standard(MetadataFieldDefinitions.IIM_STATE, value)
                }
                IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE -> {
                    builder.common("isoCountryCode", value)
                    builder.standard(MetadataFieldDefinitions.IIM_COUNTRY_CODE, value)
                }
                IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME -> {
                    builder.common("country", value)
                    builder.standard(MetadataFieldDefinitions.IIM_COUNTRY_NAME, value)
                }
                IptcTypes.HEADLINE -> {
                    builder.standard(MetadataFieldDefinitions.IIM_HEADLINE, value)
                }
                IptcTypes.CREDIT -> {
                    builder.common("credit", value)
                    builder.standard(MetadataFieldDefinitions.IIM_CREDIT, value)
                }
                IptcTypes.SOURCE -> {
                    builder.common("source", value)
                    builder.standard(MetadataFieldDefinitions.IIM_SOURCE, value)
                }
                IptcTypes.COPYRIGHT_NOTICE -> {
                    builder.common("copyright", value)
                    builder.standard(MetadataFieldDefinitions.IIM_COPYRIGHT, value)
                }
                IptcTypes.CONTACT -> builder.standard(MetadataFieldDefinitions.IIM_CONTACT, value)
                IptcTypes.CAPTION_ABSTRACT -> {
                    builder.standard(MetadataFieldDefinitions.IIM_CAPTION, value)
                }
                IptcTypes.WRITER_EDITOR -> {
                    builder.common("writer", value)
                    builder.standard(MetadataFieldDefinitions.IIM_WRITER, value)
                }
                else -> Unit
            }
        }
    }

    private fun readXmp(photoFile: File, builder: TemplateBuilder, strict: Boolean = false) {
        val packet = if (strict) {
            // 绕过 JpegXmpStore 的容错读取，同时保留历史 EXIF Tag700 的兼容性。
            JpegImageParser().getXmpXml(ByteSource.file(photoFile), JpegImagingParameters())
                ?.takeIf { it.isNotBlank() }
                ?: androidx.exifinterface.media.ExifInterface(photoFile)
                    .getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_XMP)
                    ?.toString(Charsets.UTF_8).orEmpty()
        } else runCatching { MetadataXmpStore.read(photoFile) }.getOrNull()
        val xmp = packet
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (strict) parseXml(xmp) // PullParser 在部分运行环境可能宽松接受未闭合 XML。
        val leaves = parseXmpLeaves(xmp)

        builder.xmpAlt(leaves, MetadataFieldDefinitions.DC_TITLE, "dc:title")
        builder.xmpSeqOrText(leaves, MetadataFieldDefinitions.DC_CREATOR, "dc:creator").also {
            builder.common("creator", it)
            builder.common("iptcAuthor", it)
        }
        builder.xmpAlt(leaves, MetadataFieldDefinitions.DC_DESCRIPTION, "dc:description")
        builder.xmpAlt(leaves, MetadataFieldDefinitions.DC_RIGHTS, "dc:rights").also { builder.common("copyright", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.DC_SOURCE, "dc:source").also { builder.common("source", it) }
        builder.xmpList(leaves, MetadataFieldDefinitions.DC_SUBJECT, "dc:subject")
            .forEach { builder.keyword(it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.DC_DATE, "dc:date")

        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_HEADLINE, "photoshop:Headline")
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_CREDIT, "photoshop:Credit").also { builder.common("credit", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_SOURCE, "photoshop:Source").also { builder.common("source", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_CITY, "photoshop:City").also { builder.common("city", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_STATE, "photoshop:State").also { builder.common("state", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_COUNTRY, "photoshop:Country").also { builder.common("country", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.PHOTOSHOP_DATE_CREATED, "photoshop:DateCreated")

        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_LOCATION, "Iptc4xmpCore:Location").also { builder.common("location", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE, "Iptc4xmpCore:CountryCode").also { builder.common("isoCountryCode", it) }
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_INTELLECTUAL_GENRE, "Iptc4xmpCore:IntellectualGenre")
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_CORE_SCENE, "Iptc4xmpCore:Scene")
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_CORE_SUBJECT_CODE, "Iptc4xmpCore:SubjectCode")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY, "Iptc4xmpCore:CiAdrCity")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_REGION, "Iptc4xmpCore:CiAdrRegion")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY, "Iptc4xmpCore:CiAdrCtry")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY_CODE, "Iptc4xmpCore:CiAdrCtryCode")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_ADDRESS, "Iptc4xmpCore:CiAdrExtadr")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_EMAIL, "Iptc4xmpCore:CiEmailWork")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_PHONE, "Iptc4xmpCore:CiTelWork")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_CORE_CREATOR_WEB_URL, "Iptc4xmpCore:CiUrlWork")

        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_EVENT, "Iptc4xmpExt:Event")
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE, "Iptc4xmpExt:PersonInImage")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE, "Iptc4xmpExt:CountryCode")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME, "Iptc4xmpExt:CountryName")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_REGION, "Iptc4xmpExt:ProvinceState")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_CITY, "Iptc4xmpExt:City")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_SUBLOCATION, "Iptc4xmpExt:Sublocation")
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE,
            "Iptc4xmpExt:LocationShown/rdf:Bag/rdf:li/Iptc4xmpExt:CountryCode",
            "Iptc4xmpExt:LocationShown/Bag/li/Iptc4xmpExt:CountryCode"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME,
            "Iptc4xmpExt:LocationShown/rdf:Bag/rdf:li/Iptc4xmpExt:CountryName",
            "Iptc4xmpExt:LocationShown/Bag/li/Iptc4xmpExt:CountryName"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_REGION,
            "Iptc4xmpExt:LocationShown/rdf:Bag/rdf:li/Iptc4xmpExt:ProvinceState",
            "Iptc4xmpExt:LocationShown/Bag/li/Iptc4xmpExt:ProvinceState"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_CITY,
            "Iptc4xmpExt:LocationShown/rdf:Bag/rdf:li/Iptc4xmpExt:City",
            "Iptc4xmpExt:LocationShown/Bag/li/Iptc4xmpExt:City"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION,
            "Iptc4xmpExt:LocationShown/rdf:Bag/rdf:li/Iptc4xmpExt:Sublocation",
            "Iptc4xmpExt:LocationShown/Bag/li/Iptc4xmpExt:Sublocation"
        )
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME, "Iptc4xmpExt:OrganisationInImageName")
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE, "Iptc4xmpExt:OrganisationInImageCode")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_ARTWORK_TITLE, "Iptc4xmpExt:AOTitle")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_ARTWORK_CREATOR, "Iptc4xmpExt:AOCreator")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_ARTWORK_SOURCE, "Iptc4xmpExt:AOSource")
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_IMAGE_CREATOR_NAME,
            "plus:ImageCreator/rdf:Seq/rdf:li/plus:ImageCreatorName",
            "Iptc4xmpExt:ImageCreator/rdf:Seq/rdf:li/Iptc4xmpExt:Name"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_NAME,
            "plus:ImageSupplier/rdf:Seq/rdf:li/plus:ImageSupplierName",
            "Iptc4xmpExt:ImageSupplier/rdf:Seq/rdf:li/Iptc4xmpExt:Name"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_ID,
            "plus:ImageSupplier/rdf:Seq/rdf:li/plus:ImageSupplierID",
            "Iptc4xmpExt:ImageSupplier/rdf:Seq/rdf:li/Iptc4xmpExt:Identifier"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ENTRY_ROLE,
            "Iptc4xmpExt:RegistryId/rdf:Seq/rdf:li/Iptc4xmpExt:RegEntryRole",
            "Iptc4xmpExt:RegistryEntry/rdf:Seq/rdf:li/Iptc4xmpExt:Role"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ITEM_ID,
            "Iptc4xmpExt:RegistryId/rdf:Seq/rdf:li/Iptc4xmpExt:RegItemId",
            "Iptc4xmpExt:RegistryEntry/rdf:Seq/rdf:li/Iptc4xmpExt:RegistryItemID"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ORGANISATION_ID,
            "Iptc4xmpExt:RegistryId/rdf:Seq/rdf:li/Iptc4xmpExt:RegOrgId",
            "Iptc4xmpExt:RegistryEntry/rdf:Seq/rdf:li/Iptc4xmpExt:RegistryOrganisationID"
        )
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_STATUS,
            "plus:ModelReleaseStatus",
            "Iptc4xmpExt:ModelReleaseStatus"
        )
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_ID, "plus:ModelReleaseID", "Iptc4xmpExt:ModelReleaseID")
        builder.xmpText(
            leaves,
            MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_STATUS,
            "plus:PropertyReleaseStatus",
            "Iptc4xmpExt:PropertyReleaseStatus"
        )
        builder.xmpList(leaves, MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_ID, "plus:PropertyReleaseID", "Iptc4xmpExt:PropertyReleaseID")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_DATA_MINING, "plus:DataMining", "Iptc4xmpExt:DataMining")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_USED, "Iptc4xmpExt:AISystemUsed")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED, "Iptc4xmpExt:AISystemVersionUsed")
        builder.xmpAlt(leaves, MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT, "Iptc4xmpExt:AIPromptInformation", "Iptc4xmpExt:AIPrompt")
        builder.xmpText(leaves, MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT_WRITER, "Iptc4xmpExt:AIPromptWriterName", "Iptc4xmpExt:AIPromptWriter")

        builder.xmpText(leaves, MetadataFieldDefinitions.PLUS_LICENSOR_NAME, "plus:Licensor/rdf:Seq/rdf:li/plus:LicensorName")
        builder.xmpText(leaves, MetadataFieldDefinitions.PLUS_LICENSOR_EMAIL, "plus:Licensor/rdf:Seq/rdf:li/plus:LicensorEmail")
        builder.xmpText(leaves, MetadataFieldDefinitions.PLUS_LICENSOR_URL, "plus:Licensor/rdf:Seq/rdf:li/plus:LicensorURL")
        builder.xmpText(leaves, MetadataFieldDefinitions.PLUS_LICENSOR_PHONE, "plus:Licensor/rdf:Seq/rdf:li/plus:LicensorTelephone1")
        builder.xmpText(leaves, MetadataFieldDefinitions.PLUS_COPYRIGHT_OWNER_NAME, "plus:CopyrightOwner/rdf:Seq/rdf:li/plus:CopyrightOwnerName")
        builder.imageRegions(parseXmpImageRegions(xmp))
    }

    private fun parseXmpLeaves(xmp: String): Map<String, List<String>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmp.reader())
        val path = mutableListOf<String>()
        val leaves = linkedMapOf<String, MutableList<String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> path += parser.qualifiedName()
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotBlank() && path.isNotEmpty()) {
                        leaves.getOrPut(path.joinToString("/")) { mutableListOf() } += text
                    }
                }
                XmlPullParser.END_TAG -> if (path.isNotEmpty()) path.removeAt(path.lastIndex)
            }
            event = parser.next()
        }
        return leaves
    }

    private fun XmlPullParser.qualifiedName(): String {
        val prefix = prefix
        return if (prefix.isNullOrBlank()) name else "$prefix:$name"
    }

    private fun parseXmpImageRegions(xmp: String): List<IptcImageRegion> {
        return runCatching {
            val root = parseXml(xmp)
            val containers = root.getElementsByTagName("Iptc4xmpExt:ImageRegion")
            buildList {
                for (index in 0 until containers.length) {
                    val container = containers.item(index) as? Element ?: continue
                    val bag = container.directChild("rdf:Bag") ?: container.directChild("Bag")
                    val items = bag?.directChildren("rdf:li").orEmpty() +
                        bag?.directChildren("li").orEmpty()
                    items.mapNotNullTo(this) { it.toImageRegion() }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.toImageRegion(): IptcImageRegion? {
        val region = IptcImageRegion(
            id = directText("Iptc4xmpExt:rId"),
            name = directText("Iptc4xmpExt:Name"),
            boundary = directChild("Iptc4xmpExt:RegionBoundary")
                ?.toImageRegionBoundary()
                ?: IptcImageRegionBoundary(),
            roles = directBagValues("Iptc4xmpExt:rRole"),
            contentTypes = directBagValues("Iptc4xmpExt:rCtype"),
            persons = directBagValues("Iptc4xmpExt:PersonInImage"),
            organisations = directBagValues("Iptc4xmpExt:OrganisationInImageName")
        )
        return region.takeUnless(IptcImageRegion::isBlank)
    }

    private fun Element.toImageRegionBoundary(): IptcImageRegionBoundary {
        return IptcImageRegionBoundary(
            shape = directText("Iptc4xmpExt:rbShape").ifBlank {
                IptcImageRegionBoundary.SHAPE_RECTANGLE
            },
            unit = directText("Iptc4xmpExt:rbUnit").ifBlank {
                IptcImageRegionBoundary.UNIT_RELATIVE
            },
            x = directText("Iptc4xmpExt:rbX").toDoubleOrNull(),
            y = directText("Iptc4xmpExt:rbY").toDoubleOrNull(),
            width = directText("Iptc4xmpExt:rbW").toDoubleOrNull(),
            height = directText("Iptc4xmpExt:rbH").toDoubleOrNull(),
            radius = directText("Iptc4xmpExt:rbRx").toDoubleOrNull(),
            vertices = directChild("Iptc4xmpExt:rbVertices")
                ?.parseVertices()
                .orEmpty()
        )
    }

    private fun Element.parseVertices(): List<IptcImageRegionVertex> {
        val seq = directChild("rdf:Seq") ?: directChild("Seq")
        val items = seq?.directChildren("rdf:li").orEmpty() + seq?.directChildren("li").orEmpty()
        return items.mapNotNull { item ->
            val x = item.directText("Iptc4xmpExt:rbX").toDoubleOrNull()
            val y = item.directText("Iptc4xmpExt:rbY").toDoubleOrNull()
            if (x == null || y == null) null else IptcImageRegionVertex(x, y)
        }
    }

    private fun Element.directBagValues(tag: String): List<String> {
        val container = directChild(tag) ?: return emptyList()
        val bag = container.directChild("rdf:Bag") ?: container.directChild("Bag")
        val seq = container.directChild("rdf:Seq") ?: container.directChild("Seq")
        val items = bag?.directChildren("rdf:li").orEmpty() +
            bag?.directChildren("li").orEmpty() +
            seq?.directChildren("rdf:li").orEmpty() +
            seq?.directChildren("li").orEmpty()
        return items.map { it.textContent.trim() }.filter(String::isNotBlank).distinct()
    }

    private fun Element.directText(tag: String): String =
        directChild(tag)?.textContent?.trim().orEmpty()

    private fun Element.directChild(tag: String): Element? =
        directChildren().firstOrNull { it.nodeName == tag || it.localName == tag.substringAfter(':') }

    private fun Element.directChildren(tag: String): List<Element> =
        directChildren().filter { it.nodeName == tag || it.localName == tag.substringAfter(':') }

    private fun Element.directChildren(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }

    private class TemplateBuilder(private val displayName: String) {
        private val common = linkedMapOf<String, String>()
        private val standard = linkedMapOf<String, String>()
        private val keywords = linkedSetOf<String>()
        private val enabled = linkedSetOf<String>()
        private val singlePhotoStandardFields = linkedSetOf<String>()
        private var iptcDocument = IptcDocument.EMPTY

        fun common(key: String, value: String) {
            if (value.isBlank() || common[key].isNullOrBlank().not()) return
            common[key] = value
            enabled += key
        }

        fun keyword(value: String) {
            if (value.isBlank()) return
            keywords += value
            enabled += "keywords"
        }

        fun standard(key: String, value: String) {
            if (value.isBlank() || standard[key].isNullOrBlank().not()) return
            standard[key] = value
            enabled += key
            if (key in SINGLE_PHOTO_STANDARD_KEYS) {
                singlePhotoStandardFields += key
            }
        }

        fun appendStandard(key: String, value: String) {
            if (value.isBlank()) return
            val old = standard[key].orEmpty()
            val parts = old.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            if (!parts.contains(value)) parts += value
            standard[key] = parts.joinToString(", ")
            enabled += key
            if (key in SINGLE_PHOTO_STANDARD_KEYS) {
                singlePhotoStandardFields += key
            }
        }

        fun xmpText(leaves: Map<String, List<String>>, key: String, vararg pathSuffixes: String): String {
            val value = leaves.findFirst(*pathSuffixes)
            standard(key, value)
            return value
        }

        fun xmpAlt(leaves: Map<String, List<String>>, key: String, vararg pathPrefixes: String): String {
            val suffixes = pathPrefixes.flatMap { pathPrefix ->
                listOf("$pathPrefix/rdf:Alt/rdf:li", "$pathPrefix/Alt/li", pathPrefix)
            }.toTypedArray()
            val value = leaves.findFirst(*suffixes)
            standard(key, value)
            return value
        }

        fun xmpSeqOrText(leaves: Map<String, List<String>>, key: String, pathPrefix: String): String {
            val value = leaves.findFirst("$pathPrefix/rdf:Seq/rdf:li", "$pathPrefix/Seq/li", pathPrefix)
            standard(key, value)
            return value
        }

        fun xmpList(leaves: Map<String, List<String>>, key: String, vararg pathPrefixes: String): List<String> {
            val suffixes = pathPrefixes.flatMap { pathPrefix ->
                listOf(
                    "$pathPrefix/rdf:Bag/rdf:li",
                    "$pathPrefix/rdf:Seq/rdf:li",
                    "$pathPrefix/Bag/li",
                    "$pathPrefix/Seq/li",
                    pathPrefix
                )
            }.toTypedArray()
            val values = leaves.findAll(*suffixes)
            values.forEach { appendStandard(key, it) }
            return values
        }

        fun imageRegions(regions: List<IptcImageRegion>) {
            if (regions.isEmpty()) return
            iptcDocument = iptcDocument.withImageRegions(regions)
            singlePhotoStandardFields += MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION
        }

        fun build(): ImportResult {
            val template = MetadataTemplate(
                id = "",
                displayName = displayName,
                templateType = "IPTC",
                creator = common["creator"].orEmpty(),
                title = common["title"].orEmpty(),
                iptcAuthor = common["iptcAuthor"].orEmpty(),
                writer = common["writer"].orEmpty(),
                category = common["category"].orEmpty(),
                isoCountryCode = common["isoCountryCode"].orEmpty(),
                credit = common["credit"].orEmpty(),
                source = common["source"].orEmpty(),
                copyright = common["copyright"].orEmpty(),
                country = common["country"].orEmpty(),
                city = common["city"].orEmpty(),
                state = common["state"].orEmpty(),
                location = common["location"].orEmpty(),
                headline = common["headline"].orEmpty(),
                caption = common["caption"].orEmpty(),
                keywords = keywords.toList(),
                enabledFields = enabled.toList(),
                standardFields = standard,
                iptcDocument = iptcDocument,
                visibleStandardFields = standard.keys.toList()
            )
            val standardCounts = standard.keys
                .mapNotNull { MetadataFieldDefinitions.byKey[it]?.standard }
                .groupingBy { it }
                .eachCount()
            return ImportResult(
                template = template,
                summary = ImportSummary(
                    commonFieldCount = common.size + if (keywords.isNotEmpty()) 1 else 0,
                    standardCounts = standardCounts,
                    singlePhotoFieldCount = singlePhotoStandardFields.size
                )
            )
        }
    }

    private val SINGLE_PHOTO_STANDARD_KEYS = setOf(
        MetadataFieldDefinitions.IIM_OBJECT_NAME,
        MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS,
        MetadataFieldDefinitions.IIM_DATE_CREATED,
        MetadataFieldDefinitions.IIM_TIME_CREATED,
        MetadataFieldDefinitions.IIM_HEADLINE,
        MetadataFieldDefinitions.IIM_CAPTION,
        MetadataFieldDefinitions.DC_TITLE,
        MetadataFieldDefinitions.DC_DESCRIPTION,
        MetadataFieldDefinitions.DC_DATE,
        MetadataFieldDefinitions.PHOTOSHOP_HEADLINE,
        MetadataFieldDefinitions.PHOTOSHOP_DATE_CREATED,
        MetadataFieldDefinitions.IPTC_EXT_EVENT,
        MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE,
        MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME,
        MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE,
        MetadataFieldDefinitions.IPTC_EXT_ARTWORK_TITLE,
        MetadataFieldDefinitions.IPTC_EXT_ARTWORK_CREATOR,
        MetadataFieldDefinitions.IPTC_EXT_ARTWORK_SOURCE,
        MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED,
        MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT
    )

    private fun Map<String, List<String>>.findFirst(vararg suffixes: String): String {
        return findAll(*suffixes).firstOrNull().orEmpty()
    }

    private fun Map<String, List<String>>.findAll(vararg suffixes: String): List<String> {
        return entries
            .filter { entry -> suffixes.any { entry.key.endsWith(it) } }
            .flatMap { it.value }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
