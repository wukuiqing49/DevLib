package com.wkq.iptc.feature.press.metadata

import androidx.exifinterface.media.ExifInterface
import com.wkq.dev.metadata.model.IptcDocumentMapper
import com.wkq.dev.metadata.model.IptcImageRegion
import com.wkq.dev.metadata.model.IptcImageRegionBoundary
import com.wkq.dev.metadata.model.MetadataRecord
import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.MetadataTemplate
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * XMP 元数据写入工具。
 *
 * 输出包含 Dublin Core、Photoshop、IPTC Core、IPTC Extension 以及应用内部 fieldcam 命名空间。
 */
object XmpWriter {

    /** ExifInterface 的字符串入口按 ASCII 编码；用 XML 实体保留中文及补充平面字符。 */
    internal fun asciiPacket(packet: String): String {
        val output = java.io.ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "US-ASCII")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        transformer.transform(DOMSource(parseXml(packet).documentElement), StreamResult(output))
        return output.toString("US-ASCII")
    }

    /** 附属文件不依赖 JPEG 容器；优先沿用已有 XMP 的拍摄时间和未知属性。 */
    fun buildSidecarPacket(
        template: MetadataTemplate,
        existingPacket: String = "",
        photoFile: File? = null
    ): String {
        if (existingPacket.isNotBlank()) {
            require(parseXml(existingPacket).firstRdfDescription() != null) {
                "Invalid existing XMP packet"
            }
        }
        val exif = photoFile?.let { runCatching { ExifInterface(it) }.getOrNull() }
        val dateCreated = readCaptureDate(exif) ?: readExistingXmpDate(existingPacket)
            ?: ""
        return mergePreservingUnknownProperties(
            existingPacket,
            buildTemplatePacket(template, dateCreated),
            replaceManagedProperties = !template.preserveUnspecifiedFields,
            strict = true
        )
    }

    fun buildTemplatePacket(template: MetadataTemplate): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        return buildTemplatePacket(template, now)
    }

    fun buildTemplatePacket(
        photoFile: File,
        template: MetadataTemplate,
        replaceManagedProperties: Boolean = false
    ): String {
        val exif = runCatching { ExifInterface(photoFile) }.getOrNull()
        val existing = MetadataXmpStore.read(photoFile)
        val dateCreated = readCaptureDate(exif)
            ?: readExistingXmpDate(existing)
            ?: ""
        val effectiveTemplate = if (com.wkq.util.exif.CleanJpegExporter.isJpeg(photoFile)) template
            else NativeIptcTemplate.forXmp(template)
        val generated = buildTemplatePacket(effectiveTemplate, dateCreated)
        return mergePreservingUnknownProperties(
            existingPacket = existing,
            generatedPacket = generated,
            replaceManagedProperties = replaceManagedProperties
        )
    }

    private fun buildTemplatePacket(template: MetadataTemplate, dateCreated: String): String {
        val author = template.iptcAuthor.ifBlank { template.creator }
        val enabledIptcFields = template.enabledIptcDocumentFields()
        val iptcDocument = IptcDocumentMapper.fromStandardFields(template.standardFields)
            .mergedWith(template.iptcDocument)
            .let { document ->
                document.copy(fields = document.fields.filterKeys { it in enabledIptcFields })
            }
        return buildXmpPacket(
            title = template.enabledValue("title", template.title),
            headline = template.enabledValue("headline", template.headline),
            description = template.enabledValue("caption", template.caption),
            creator = template.enabledValue("iptcAuthor", author),
            writer = if (template.shouldWriteStandardField(MetadataFieldDefinitions.IIM_WRITER))
                template.standardValue(MetadataFieldDefinitions.IIM_WRITER)
            else template.enabledValue("writer", template.writer),
            credit = template.enabledValue("credit", template.credit),
            source = template.enabledValue("source", template.source),
            rights = template.enabledValue("copyright", template.copyright),
            country = template.enabledValue("country", template.country),
            state = template.enabledValue("state", template.state),
            city = template.enabledValue("city", template.city),
            location = template.enabledValue("location", template.location),
            countryCode = template.enabledValue("isoCountryCode", template.isoCountryCode),
            keywords = if (template.isFieldEnabled("keywords")) template.keywords else emptyList(),
            dateCreated = dateCreated,
            rightsUsageTerms = template.enabledValue(
                "rightsUsageTerms",
                template.rightsUsageTerms
            ),
            copyrightMarked = template.enabledValue(
                "copyrightStatus",
                template.copyrightStatus
            ).toCopyrightMarked(),
            copyrightOwner = template.enabledValue(
                "copyrightOwner",
                template.copyrightOwner
            ),
            templateId = template.id,
            templateName = template.displayName,
            templateType = template.templateType,
            customFields = template.customFields,
            imageRegions = iptcDocument.imageRegions(),
            standardValue = { key, fallback -> resolvedStandardValue(template, key, fallback) },
            standardEnabled = { key -> template.shouldWriteStandardField(key) }
        )
    }

    internal fun resolvedStandardValue(
        template: MetadataTemplate,
        key: String,
        fallback: String
    ): String {
        if (!template.shouldWriteStandardField(key)) return fallback
        // 公共编辑字段有值时是该 XMP 属性的主值；高级字段仅在公共值为空时独立生效。
        if (fallback.isNotBlank()) return fallback
        val document = IptcDocumentMapper.fromStandardFields(template.standardFields)
            .mergedWith(template.iptcDocument)
        return document.text(key)
            .takeUnless { it.isQuestionMarkPlaceholder() }
            .orEmpty()
            .ifBlank { template.standardValue(key).ifBlank { fallback } }
    }

    fun writeRecordMetadata(photoFile: File, record: MetadataRecord): Result<Unit> {
        return runCatching {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            val author = record.iptcAuthor.ifBlank { record.creator }
            val packet = buildXmpPacket(
                title = record.title,
                headline = record.headline,
                description = record.caption,
                creator = author,
                writer = record.writer,
                credit = record.credit,
                source = record.source,
                rights = record.copyright,
                country = record.country,
                state = record.state,
                city = record.city,
                location = record.location,
                countryCode = record.isoCountryCode,
                keywords = record.keywords.split(",").map { it.trim() }.filter { it.isNotBlank() },
                dateCreated = now,
                rightsUsageTerms = record.rightsUsageTerms,
                copyrightMarked = record.copyrightStatus.toCopyrightMarked(),
                copyrightOwner = "",
                templateId = record.templateId,
                templateName = record.templateName,
                templateType = "",
                customFields = parseCustomFields(record.customFields),
                imageRegions = emptyList(),
                standardValue = { _, fallback -> fallback },
                standardEnabled = { false }
            )
            val mergedPacket = mergePreservingUnknownProperties(
                MetadataXmpStore.read(photoFile),
                packet
            )
            MetadataXmpStore.write(photoFile, mergedPacket).getOrThrow()
        }
    }

    /** ExifInterface 将 TAG_XMP 的 String 按 US-ASCII 编码，非 ASCII 字符需写成 XML 数字实体。 */
    internal fun toExifSafePacket(packet: String): String = buildString(packet.length) {
        var index = 0
        while (index < packet.length) {
            val codePoint = packet.codePointAt(index)
            if (codePoint <= 0x7F) {
                append(codePoint.toChar())
            } else {
                append("&#x")
                append(codePoint.toString(16).uppercase(Locale.ROOT))
                append(';')
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun buildXmpPacket(
        title: String,
        headline: String,
        description: String,
        creator: String,
        writer: String,
        credit: String,
        source: String,
        rights: String,
        country: String,
        state: String,
        city: String,
        location: String,
        countryCode: String,
        keywords: List<String>,
        dateCreated: String,
        rightsUsageTerms: String,
        copyrightMarked: Boolean?,
        copyrightOwner: String,
        templateId: String,
        templateName: String,
        templateType: String,
        customFields: Map<String, String>,
        imageRegions: List<IptcImageRegion>,
        standardValue: (String, String) -> String,
        standardEnabled: (String) -> Boolean
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>""")
        sb.appendLine("""<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="PressIPTC">""")
        sb.appendLine("""  <rdf:RDF""")
        sb.appendLine("""    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"""")
        sb.appendLine("""    xmlns:dc="http://purl.org/dc/elements/1.1/"""")
        sb.appendLine("""    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"""")
        sb.appendLine("""    xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/"""")
        sb.appendLine("""    xmlns:plus="http://ns.useplus.org/ldf/xmp/1.0/"""")
        sb.appendLine("""    xmlns:Iptc4xmpCore="http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"""")
        sb.appendLine("""    xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"""")
        sb.appendLine("""    xmlns:fieldcam="https://wkq.com/ns/fieldcam/1.0/">""")
        sb.appendLine("""    <rdf:Description rdf:about="">""")

        appendDublinCore(sb, title, description, creator, rights, source, keywords, dateCreated, standardValue, standardEnabled)
        appendPhotoshop(sb, headline, credit, source, city, state, country, writer, dateCreated, standardValue)
        appendRights(sb, rightsUsageTerms, copyrightMarked, copyrightOwner, standardValue)
        appendIptcCore(sb, location, countryCode, standardValue)
        appendIptcExtension(sb, countryCode, country, state, city, location, imageRegions, standardValue)

        if (templateId.isNotBlank()) sb.appendLine("""      <fieldcam:templateId>${escape(templateId)}</fieldcam:templateId>""")
        if (templateName.isNotBlank()) sb.appendLine("""      <fieldcam:templateName>${escape(templateName)}</fieldcam:templateName>""")
        if (templateType.isNotBlank()) sb.appendLine("""      <fieldcam:templateType>${escape(templateType)}</fieldcam:templateType>""")
        appendSceneFields(sb, customFields)

        sb.appendLine("""    </rdf:Description>""")
        sb.appendLine("""  </rdf:RDF>""")
        sb.appendLine("""</x:xmpmeta>""")
        sb.appendLine("""<?xpacket end="w"?>""")
        return sb.toString()
    }

    private fun appendDublinCore(
        sb: StringBuilder,
        title: String,
        description: String,
        creator: String,
        rights: String,
        source: String,
        keywords: List<String>,
        dateCreated: String,
        standardValue: (String, String) -> String,
        standardEnabled: (String) -> Boolean
    ) {
        val dcTitle = standardValue(MetadataFieldDefinitions.DC_TITLE, title)
        val dcDescription = standardValue(MetadataFieldDefinitions.DC_DESCRIPTION, description)
        val dcCreator = standardValue(MetadataFieldDefinitions.DC_CREATOR, creator)
        val dcRights = standardValue(MetadataFieldDefinitions.DC_RIGHTS, rights)
        val dcSource = standardValue(MetadataFieldDefinitions.DC_SOURCE, source)
        val dcDate = standardValue(MetadataFieldDefinitions.DC_DATE, dateCreated)
        val dcSubject = standardValue(MetadataFieldDefinitions.DC_SUBJECT, keywords.joinToString(","))
            .split(",", "\uFF0C", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        appendAlt(sb, "dc:title", dcTitle)
        appendAlt(sb, "dc:description", dcDescription)
        if (dcCreator.isNotBlank()) {
            appendSeq(sb, "dc:creator", listOf(dcCreator))
        }
        appendAlt(sb, "dc:rights", dcRights)
        if (dcSource.isNotBlank()) sb.appendLine("""      <dc:source>${escape(dcSource)}</dc:source>""")
        if (dcDate.isNotBlank()) sb.appendLine("""      <dc:date>${escape(dcDate)}</dc:date>""")
        appendBag(sb, "dc:subject", dcSubject)
    }

    private fun appendPhotoshop(
        sb: StringBuilder,
        headline: String,
        credit: String,
        source: String,
        city: String,
        state: String,
        country: String,
        writer: String,
        dateCreated: String,
        standardValue: (String, String) -> String
    ) {
        appendSimple(sb, "photoshop:Headline", standardValue(MetadataFieldDefinitions.PHOTOSHOP_HEADLINE, headline))
        appendSimple(sb, "photoshop:Credit", standardValue(MetadataFieldDefinitions.PHOTOSHOP_CREDIT, credit))
        appendSimple(sb, "photoshop:Source", standardValue(MetadataFieldDefinitions.PHOTOSHOP_SOURCE, source))
        appendSimple(sb, "photoshop:City", standardValue(MetadataFieldDefinitions.PHOTOSHOP_CITY, city))
        appendSimple(sb, "photoshop:State", standardValue(MetadataFieldDefinitions.PHOTOSHOP_STATE, state))
        appendSimple(sb, "photoshop:Country", standardValue(MetadataFieldDefinitions.PHOTOSHOP_COUNTRY, country))
        appendSimple(sb, "photoshop:CaptionWriter", writer)
        appendSimple(sb, "photoshop:AuthorsPosition", standardValue(MetadataFieldDefinitions.IIM_BYLINE_TITLE, ""))
        appendSimple(sb, "photoshop:Instructions", standardValue(MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS, ""))
        appendSimple(sb, "photoshop:DateCreated", standardValue(MetadataFieldDefinitions.PHOTOSHOP_DATE_CREATED, dateCreated))
        appendSimple(
            sb,
            "photoshop:TransmissionReference",
            standardValue(MetadataFieldDefinitions.PHOTOSHOP_TRANSMISSION_REFERENCE, "")
        )
        appendSimple(sb, "photoshop:Category", standardValue(MetadataFieldDefinitions.IIM_CATEGORY, ""))
        appendSimple(sb, "photoshop:Urgency", standardValue(MetadataFieldDefinitions.IIM_URGENCY, ""))
        appendBagFromText(sb, "photoshop:SupplementalCategories", standardValue(MetadataFieldDefinitions.IIM_SUPPLEMENTAL_CATEGORY, ""))
    }

    private fun appendRights(
        sb: StringBuilder,
        rightsUsageTerms: String,
        copyrightMarked: Boolean?,
        copyrightOwner: String,
        standardValue: (String, String) -> String
    ) {
        appendAlt(sb, "xmpRights:UsageTerms", rightsUsageTerms)
        copyrightMarked?.let {
            appendSimple(sb, "xmpRights:Marked", it.toString())
        }
        val ownerName = standardValue(MetadataFieldDefinitions.PLUS_COPYRIGHT_OWNER_NAME, copyrightOwner)
        if (ownerName.isNotBlank()) {
            sb.appendLine("""      <plus:CopyrightOwner><rdf:Seq><rdf:li rdf:parseType="Resource">""")
            appendSimple(
                sb,
                "plus:CopyrightOwnerName",
                ownerName,
                indent = "        "
            )
            sb.appendLine("""      </rdf:li></rdf:Seq></plus:CopyrightOwner>""")
        }
        val licensorValues = listOf(
            "plus:LicensorName" to standardValue(MetadataFieldDefinitions.PLUS_LICENSOR_NAME, ""),
            "plus:LicensorEmail" to standardValue(MetadataFieldDefinitions.PLUS_LICENSOR_EMAIL, ""),
            "plus:LicensorURL" to standardValue(MetadataFieldDefinitions.PLUS_LICENSOR_URL, ""),
            "plus:LicensorTelephone1" to standardValue(MetadataFieldDefinitions.PLUS_LICENSOR_PHONE, "")
        ).filter { it.second.isNotBlank() }
        if (licensorValues.isNotEmpty()) {
            sb.appendLine("""      <plus:Licensor><rdf:Seq><rdf:li rdf:parseType="Resource">""")
            licensorValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Seq></plus:Licensor>""")
        }
    }

    private fun appendIptcCore(
        sb: StringBuilder,
        location: String,
        countryCode: String,
        standardValue: (String, String) -> String
    ) {
        appendSimple(sb, "Iptc4xmpCore:Location", standardValue(MetadataFieldDefinitions.IPTC_CORE_LOCATION, location))
        appendSimple(sb, "Iptc4xmpCore:CountryCode", standardValue(MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE, countryCode))
        appendSimple(sb, "Iptc4xmpCore:IntellectualGenre", standardValue(MetadataFieldDefinitions.IPTC_CORE_INTELLECTUAL_GENRE, ""))
        appendBagFromText(sb, "Iptc4xmpCore:Scene", standardValue(MetadataFieldDefinitions.IPTC_CORE_SCENE, ""))
        appendBagFromText(sb, "Iptc4xmpCore:SubjectCode", standardValue(MetadataFieldDefinitions.IPTC_CORE_SUBJECT_CODE, ""))

        val contactValues = listOf(
            "Iptc4xmpCore:CiAdrCity" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY, ""),
            "Iptc4xmpCore:CiAdrRegion" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_REGION, ""),
            "Iptc4xmpCore:CiAdrCtry" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY, ""),
            "Iptc4xmpCore:CiAdrCtryCode" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY_CODE, ""),
            "Iptc4xmpCore:CiAdrExtadr" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_ADDRESS, ""),
            "Iptc4xmpCore:CiEmailWork" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_EMAIL, ""),
            "Iptc4xmpCore:CiTelWork" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_PHONE, ""),
            "Iptc4xmpCore:CiUrlWork" to standardValue(MetadataFieldDefinitions.IPTC_CORE_CREATOR_WEB_URL, "")
        ).filter { it.second.isNotBlank() }
        if (contactValues.isNotEmpty()) {
            sb.appendLine("""      <Iptc4xmpCore:CreatorContactInfo rdf:parseType="Resource">""")
            contactValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </Iptc4xmpCore:CreatorContactInfo>""")
        }
    }

    private fun appendIptcExtension(
        sb: StringBuilder,
        countryCode: String,
        country: String,
        state: String,
        city: String,
        location: String,
        imageRegions: List<IptcImageRegion>,
        standardValue: (String, String) -> String
    ) {
        appendSimple(sb, "Iptc4xmpExt:Event", standardValue(MetadataFieldDefinitions.IPTC_EXT_EVENT, ""))
        appendSimple(
            sb,
            "Iptc4xmpExt:DigitalSourceType",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_DIGITAL_SOURCE_TYPE, "")
        )
        appendSimple(sb, "plus:DataMining", standardValue(MetadataFieldDefinitions.IPTC_EXT_DATA_MINING, ""))
        appendSimple(sb, "Iptc4xmpExt:AISystemUsed", standardValue(MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_USED, ""))
        appendSimple(
            sb,
            "Iptc4xmpExt:AISystemVersionUsed",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED, "")
        )
        appendAlt(sb, "Iptc4xmpExt:AIPromptInformation", standardValue(MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT, ""))
        appendSimple(sb, "Iptc4xmpExt:AIPromptWriterName", standardValue(MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT_WRITER, ""))
        appendSimple(
            sb,
            "plus:ModelReleaseStatus",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_STATUS, "")
        )
        appendBagFromText(
            sb,
            "plus:ModelReleaseID",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_ID, "")
        )
        appendSimple(
            sb,
            "plus:PropertyReleaseStatus",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_STATUS, "")
        )
        appendBagFromText(
            sb,
            "plus:PropertyReleaseID",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_ID, "")
        )
        appendBagFromText(sb, "Iptc4xmpExt:PersonInImage", standardValue(MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE, ""))
        appendBagFromText(
            sb,
            "Iptc4xmpExt:OrganisationInImageName",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME, "")
        )
        appendBagFromText(
            sb,
            "Iptc4xmpExt:OrganisationInImageCode",
            standardValue(MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE, "")
        )

        val locationValues = listOf(
            "Iptc4xmpExt:CountryCode" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE, countryCode),
            "Iptc4xmpExt:CountryName" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME, country),
            "Iptc4xmpExt:ProvinceState" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_REGION, state),
            "Iptc4xmpExt:City" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_CITY, city),
            "Iptc4xmpExt:Sublocation" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_SUBLOCATION, location)
        ).filter { it.second.isNotBlank() }
        if (locationValues.isNotEmpty()) {
            sb.appendLine("""      <Iptc4xmpExt:LocationCreated><rdf:Bag><rdf:li rdf:parseType="Resource">""")
            locationValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Bag></Iptc4xmpExt:LocationCreated>""")
        }
        val locationShownValues = listOf(
            "Iptc4xmpExt:CountryCode" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE, ""),
            "Iptc4xmpExt:CountryName" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME, ""),
            "Iptc4xmpExt:ProvinceState" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_REGION, ""),
            "Iptc4xmpExt:City" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_CITY, ""),
            "Iptc4xmpExt:Sublocation" to standardValue(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION, "")
        ).filter { it.second.isNotBlank() }
        if (locationShownValues.isNotEmpty()) {
            sb.appendLine("""      <Iptc4xmpExt:LocationShown><rdf:Bag><rdf:li rdf:parseType="Resource">""")
            locationShownValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Bag></Iptc4xmpExt:LocationShown>""")
        }

        val imageCreatorValues = listOf(
            "plus:ImageCreatorName" to standardValue(MetadataFieldDefinitions.IPTC_EXT_IMAGE_CREATOR_NAME, "")
        ).filter { it.second.isNotBlank() }
        if (imageCreatorValues.isNotEmpty()) {
            sb.appendLine("""      <plus:ImageCreator><rdf:Seq><rdf:li rdf:parseType="Resource">""")
            imageCreatorValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Seq></plus:ImageCreator>""")
        }

        val imageSupplierValues = listOf(
            "plus:ImageSupplierName" to standardValue(MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_NAME, ""),
            "plus:ImageSupplierID" to standardValue(MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_ID, "")
        ).filter { it.second.isNotBlank() }
        if (imageSupplierValues.isNotEmpty()) {
            sb.appendLine("""      <plus:ImageSupplier><rdf:Seq><rdf:li rdf:parseType="Resource">""")
            imageSupplierValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Seq></plus:ImageSupplier>""")
        }

        val registryValues = listOf(
            "Iptc4xmpExt:RegEntryRole" to standardValue(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ENTRY_ROLE, ""),
            "Iptc4xmpExt:RegItemId" to standardValue(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ITEM_ID, ""),
            "Iptc4xmpExt:RegOrgId" to standardValue(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ORGANISATION_ID, "")
        ).filter { it.second.isNotBlank() }
        if (registryValues.isNotEmpty()) {
            sb.appendLine("""      <Iptc4xmpExt:RegistryId><rdf:Seq><rdf:li rdf:parseType="Resource">""")
            registryValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Seq></Iptc4xmpExt:RegistryId>""")
        }

        val artworkValues = listOf(
            "Iptc4xmpExt:AOTitle" to standardValue(MetadataFieldDefinitions.IPTC_EXT_ARTWORK_TITLE, ""),
            "Iptc4xmpExt:AOCreator" to standardValue(MetadataFieldDefinitions.IPTC_EXT_ARTWORK_CREATOR, ""),
            "Iptc4xmpExt:AOSource" to standardValue(MetadataFieldDefinitions.IPTC_EXT_ARTWORK_SOURCE, "")
        ).filter { it.second.isNotBlank() }
        if (artworkValues.isNotEmpty()) {
            sb.appendLine("""      <Iptc4xmpExt:ArtworkOrObject><rdf:Bag><rdf:li rdf:parseType="Resource">""")
            artworkValues.forEach { (tag, value) -> appendSimple(sb, tag, value, indent = "        ") }
            sb.appendLine("""      </rdf:li></rdf:Bag></Iptc4xmpExt:ArtworkOrObject>""")
        }

        appendImageRegions(sb, imageRegions)
    }

    private fun appendImageRegions(sb: StringBuilder, regions: List<IptcImageRegion>) {
        val cleanRegions = regions.filter { !it.isBlank() }
        if (cleanRegions.isEmpty()) return
        sb.appendLine("""      <Iptc4xmpExt:ImageRegion><rdf:Bag>""")
        cleanRegions.forEach { region ->
            sb.appendLine("""        <rdf:li rdf:parseType="Resource">""")
            appendSimple(sb, "Iptc4xmpExt:rId", region.id, indent = "          ")
            appendSimple(sb, "Iptc4xmpExt:Name", region.name, indent = "          ")
            appendBag(sb, "Iptc4xmpExt:rRole", region.roles, indent = "          ")
            appendBag(sb, "Iptc4xmpExt:rCtype", region.contentTypes, indent = "          ")
            appendBag(sb, "Iptc4xmpExt:PersonInImage", region.persons, indent = "          ")
            appendBag(
                sb,
                "Iptc4xmpExt:OrganisationInImageName",
                region.organisations,
                indent = "          "
            )
            appendRegionBoundary(sb, region.boundary)
            sb.appendLine("""        </rdf:li>""")
        }
        sb.appendLine("""      </rdf:Bag></Iptc4xmpExt:ImageRegion>""")
    }

    private fun appendRegionBoundary(sb: StringBuilder, boundary: IptcImageRegionBoundary) {
        if (boundary.isBlank()) return
        sb.appendLine("""          <Iptc4xmpExt:RegionBoundary rdf:parseType="Resource">""")
        appendSimple(sb, "Iptc4xmpExt:rbShape", boundary.shape, indent = "            ")
        appendSimple(sb, "Iptc4xmpExt:rbUnit", boundary.unit, indent = "            ")
        appendNumber(sb, "Iptc4xmpExt:rbX", boundary.x, indent = "            ")
        appendNumber(sb, "Iptc4xmpExt:rbY", boundary.y, indent = "            ")
        appendNumber(sb, "Iptc4xmpExt:rbW", boundary.width, indent = "            ")
        appendNumber(sb, "Iptc4xmpExt:rbH", boundary.height, indent = "            ")
        appendNumber(sb, "Iptc4xmpExt:rbRx", boundary.radius, indent = "            ")
        if (boundary.vertices.isNotEmpty()) {
            sb.appendLine("""            <Iptc4xmpExt:rbVertices><rdf:Seq>""")
            boundary.vertices.forEach { vertex ->
                sb.appendLine("""              <rdf:li rdf:parseType="Resource">""")
                appendNumber(sb, "Iptc4xmpExt:rbX", vertex.x, indent = "                ")
                appendNumber(sb, "Iptc4xmpExt:rbY", vertex.y, indent = "                ")
                sb.appendLine("""              </rdf:li>""")
            }
            sb.appendLine("""            </rdf:Seq></Iptc4xmpExt:rbVertices>""")
        }
        sb.appendLine("""          </Iptc4xmpExt:RegionBoundary>""")
    }

    private fun MetadataTemplate.enabledValue(field: String, value: String): String {
        return if (isFieldEnabled(field)) value else ""
    }

    private fun appendSceneFields(sb: StringBuilder, fields: Map<String, String>) {
        val cleanFields = fields
            .map { it.key.trim() to it.value.trim() }
            .filter { it.first.isNotBlank() }
        if (cleanFields.isEmpty()) return

        sb.appendLine("""      <fieldcam:sceneFields><rdf:Bag>""")
        cleanFields.forEach { (name, value) ->
            sb.appendLine("""        <rdf:li rdf:parseType="Resource">""")
            sb.appendLine("""          <fieldcam:name>${escape(name)}</fieldcam:name>""")
            if (value.isNotBlank()) {
                sb.appendLine("""          <fieldcam:value>${escape(value)}</fieldcam:value>""")
            }
            sb.appendLine("""        </rdf:li>""")
        }
        sb.appendLine("""      </rdf:Bag></fieldcam:sceneFields>""")
    }

    private fun parseCustomFields(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .mapNotNull { key ->
                    val value = json.optString(key, "")
                    if (key.isBlank()) null else key to value
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun appendSimple(sb: StringBuilder, tag: String, value: String, indent: String = "      ") {
        val cleanValue = value.takeUnless { it.isQuestionMarkPlaceholder() }.orEmpty()
        if (cleanValue.isNotBlank()) sb.appendLine("""$indent<$tag>${escape(cleanValue)}</$tag>""")
    }

    private fun appendAlt(sb: StringBuilder, tag: String, value: String) {
        val cleanValue = value.takeUnless { it.isQuestionMarkPlaceholder() }.orEmpty()
        if (cleanValue.isNotBlank()) {
            sb.appendLine("""      <$tag><rdf:Alt><rdf:li xml:lang="x-default">${escape(cleanValue)}</rdf:li></rdf:Alt></$tag>""")
        }
    }

    private fun appendSeq(sb: StringBuilder, tag: String, values: List<String>) {
        val clean = values.cleanTextValues()
        if (clean.isEmpty()) return
        sb.appendLine("""      <$tag><rdf:Seq>""")
        clean.forEach { sb.appendLine("""        <rdf:li>${escape(it)}</rdf:li>""") }
        sb.appendLine("""      </rdf:Seq></$tag>""")
    }

    private fun appendBagFromText(sb: StringBuilder, tag: String, value: String) {
        appendBag(
            sb,
            tag,
            value.split(",", "\uFF0C", ";", "\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        )
    }

    private fun appendBag(sb: StringBuilder, tag: String, values: List<String>) {
        appendBag(sb, tag, values, indent = "      ")
    }

    private fun appendBag(sb: StringBuilder, tag: String, values: List<String>, indent: String) {
        val clean = values.cleanTextValues()
        if (clean.isEmpty()) return
        sb.appendLine("""$indent<$tag><rdf:Bag>""")
        clean.forEach { sb.appendLine("""$indent  <rdf:li>${escape(it)}</rdf:li>""") }
        sb.appendLine("""$indent</rdf:Bag></$tag>""")
    }

    private fun appendNumber(sb: StringBuilder, tag: String, value: Double?, indent: String) {
        value ?: return
        appendSimple(sb, tag, value.toCleanString(), indent)
    }

    private fun Double.toCleanString(): String {
        val raw = toString()
        return if (raw.endsWith(".0")) raw.dropLast(2) else raw
    }

    private fun List<String>.cleanTextValues(): List<String> {
        return map(String::trim)
            .filter { it.isNotBlank() && !it.isQuestionMarkPlaceholder() }
            .distinct()
    }

    private fun String.isQuestionMarkPlaceholder(): Boolean {
        val compact = filterNot(Char::isWhitespace)
        return compact.isNotEmpty() && compact.all { it == '?' || it == '？' }
    }

    private fun escape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun readCaptureDate(exif: ExifInterface?): String? {
        val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?.trim()
            .orEmpty()
        val match = CAPTURE_TIME_REGEX.matchEntire(raw) ?: return null
        val offset = exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?.trim()
            ?.takeIf(OFFSET_REGEX::matches)
            .orEmpty()
        return buildString {
            append(match.groupValues[1])
            append('-')
            append(match.groupValues[2])
            append('-')
            append(match.groupValues[3])
            append('T')
            append(match.groupValues[4])
            append(':')
            append(match.groupValues[5])
            append(':')
            append(match.groupValues[6])
            append(offset)
        }
    }

    private fun readExistingXmpDate(packet: String): String? {
        if (packet.isBlank()) return null
        return runCatching {
            val document = parseXml(packet)
            listOf("photoshop:DateCreated", "dc:date")
                .firstNotNullOfOrNull { tag ->
                    document.getElementsByTagName(tag).item(0)
                        ?.textContent
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
        }.getOrNull()
    }

    private fun String.toCopyrightMarked(): Boolean? {
        if (isBlank()) return null
        return when (trim().lowercase(Locale.ROOT)) {
            "false", "no", "unmarked", "public domain",
            "公有领域", "公共领域", "公眾領域", "无版权", "無版權" -> false
            else -> true
        }
    }

    /**
     * 只覆盖本次明确生成的标准属性，保留原 XMP 中未识别的命名空间和未修改属性。
     * 若原 XMP 不是合法 XML，则安全退回新生成的数据包。
     */
    internal fun mergePreservingUnknownProperties(
        existingPacket: String,
        generatedPacket: String,
        replaceManagedProperties: Boolean = false,
        strict: Boolean = false
    ): String {
        if (existingPacket.isBlank()) return generatedPacket
        return runCatching {
            val existing = parseXml(existingPacket)
            val generated = parseXml(generatedPacket)
            val generatedDescription = generated.firstRdfDescription() ?: return@runCatching generatedPacket
            val existingDescriptions = existing.rdfDescriptions()
            if (existingDescriptions.isEmpty()) return@runCatching generatedPacket

            val generatedKeys = generatedDescription.directChildElements()
                .map { it.propertyKey() }
                .toMutableSet()
            existingDescriptions.forEach { existingDescription ->
                existingDescription.directChildElements().forEach { oldProperty ->
                    val shouldDiscard = replaceManagedProperties &&
                        oldProperty.isManagedXmpProperty(generatedKeys)
                    if (!shouldDiscard && oldProperty.propertyKey() !in generatedKeys) {
                        generatedDescription.appendChild(
                            generated.importNode(oldProperty, true)
                        )
                        generatedKeys += oldProperty.propertyKey()
                    }
                }

                for (index in 0 until existingDescription.attributes.length) {
                    val attribute = existingDescription.attributes.item(index)
                    val isNamespace = attribute.nodeName == "xmlns" ||
                        attribute.nodeName.startsWith("xmlns:")
                    val shouldDiscard = replaceManagedProperties &&
                        attribute.namespaceURI in MANAGED_XMP_NAMESPACES
                    val alreadyExists = if (attribute.namespaceURI != null) {
                        generatedDescription.hasAttributeNS(
                            attribute.namespaceURI,
                            attribute.localName
                        )
                    } else {
                        generatedDescription.hasAttribute(attribute.nodeName)
                    }
                    if (!isNamespace &&
                        !shouldDiscard &&
                        !alreadyExists &&
                        attribute.nodeName != "rdf:about"
                    ) {
                        generatedDescription.setAttributeNS(
                            attribute.namespaceURI,
                            attribute.nodeName,
                            attribute.nodeValue
                        )
                    }
                }
            }
            copyNamespaceDeclarations(existing.documentElement, generated.documentElement)
            existing.firstRdfRoot()?.let { source ->
                generated.firstRdfRoot()?.let { target ->
                    copyNamespaceDeclarations(source, target)
                }
            }
            serializeXml(generated)
        }.getOrElse { error ->
            if (strict) throw IllegalArgumentException("Unable to preserve existing XMP", error)
            generatedPacket
        }
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun Document.firstRdfDescription(): Element? {
        return getElementsByTagNameNS(RDF_NAMESPACE, "Description")
            .item(0) as? Element
    }

    private fun Document.rdfDescriptions(): List<Element> {
        val nodes = getElementsByTagNameNS(RDF_NAMESPACE, "Description")
        return buildList {
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? Element)?.let(::add)
            }
        }
    }

    private fun Document.firstRdfRoot(): Element? {
        return getElementsByTagNameNS(RDF_NAMESPACE, "RDF").item(0) as? Element
    }

    private fun Element.directChildElements(): List<Element> {
        return buildList {
            val children = childNodes
            for (index in 0 until children.length) {
                (children.item(index) as? Element)?.let(::add)
            }
        }
    }

    private fun Element.propertyKey(): String {
        return "${namespaceURI.orEmpty()}|${localName ?: nodeName}"
    }

    private fun Element.isManagedXmpProperty(generatedKeys: Set<String>): Boolean {
        val key = propertyKey()
        return key in generatedKeys || key in ALWAYS_CLEAR_MANAGED_XMP_PROPERTY_KEYS
    }

    private fun copyNamespaceDeclarations(source: Element, target: Element) {
        for (index in 0 until source.attributes.length) {
            val attribute = source.attributes.item(index)
            val isNamespace = attribute.nodeName == "xmlns" ||
                attribute.nodeName.startsWith("xmlns:")
            if (isNamespace && !target.hasAttribute(attribute.nodeName)) {
                target.setAttribute(attribute.nodeName, attribute.nodeValue)
            }
        }
    }

    private fun serializeXml(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        return StringWriter().also {
            transformer.transform(DOMSource(document), StreamResult(it))
        }.toString()
    }

    private val CAPTURE_TIME_REGEX =
        Regex("""(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")
    private val OFFSET_REGEX = Regex("""[+-]\d{2}:\d{2}""")
    private const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
    private const val PHOTOSHOP_NAMESPACE = "http://ns.adobe.com/photoshop/1.0/"
    private const val XMP_RIGHTS_NAMESPACE = "http://ns.adobe.com/xap/1.0/rights/"
    private const val PLUS_NAMESPACE = "http://ns.useplus.org/ldf/xmp/1.0/"
    private const val IPTC_CORE_NAMESPACE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    private const val IPTC_EXTENSION_NAMESPACE = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    private const val FIELDCAM_NAMESPACE = "https://wkq.com/ns/fieldcam/1.0/"

    private val MANAGED_XMP_NAMESPACES = setOf(
        DC_NAMESPACE,
        PHOTOSHOP_NAMESPACE,
        XMP_RIGHTS_NAMESPACE,
        IPTC_CORE_NAMESPACE,
        IPTC_EXTENSION_NAMESPACE,
        PLUS_NAMESPACE,
        FIELDCAM_NAMESPACE
    )

    private fun xmpKey(namespace: String, localName: String): String = "$namespace|$localName"

    private val ALWAYS_CLEAR_MANAGED_XMP_PROPERTY_KEYS = setOf(
        xmpKey(DC_NAMESPACE, "title"),
        xmpKey(DC_NAMESPACE, "description"),
        xmpKey(DC_NAMESPACE, "creator"),
        xmpKey(DC_NAMESPACE, "rights"),
        xmpKey(DC_NAMESPACE, "source"),
        xmpKey(DC_NAMESPACE, "date"),
        xmpKey(DC_NAMESPACE, "subject"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Headline"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Credit"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Source"),
        xmpKey(PHOTOSHOP_NAMESPACE, "City"),
        xmpKey(PHOTOSHOP_NAMESPACE, "State"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Country"),
        xmpKey(PHOTOSHOP_NAMESPACE, "CaptionWriter"),
        xmpKey(PHOTOSHOP_NAMESPACE, "DateCreated"),
        xmpKey(PHOTOSHOP_NAMESPACE, "TransmissionReference"),
        xmpKey(PHOTOSHOP_NAMESPACE, "AuthorsPosition"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Instructions"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Category"),
        xmpKey(PHOTOSHOP_NAMESPACE, "Urgency"),
        xmpKey(PHOTOSHOP_NAMESPACE, "SupplementalCategories"),
        xmpKey(XMP_RIGHTS_NAMESPACE, "UsageTerms"),
        xmpKey(XMP_RIGHTS_NAMESPACE, "Marked"),
        xmpKey(PLUS_NAMESPACE, "CopyrightOwner"),
        xmpKey(PLUS_NAMESPACE, "Licensor"),
        xmpKey(PLUS_NAMESPACE, "DataMining"),
        xmpKey(PLUS_NAMESPACE, "ModelReleaseStatus"),
        xmpKey(PLUS_NAMESPACE, "ModelReleaseID"),
        xmpKey(PLUS_NAMESPACE, "PropertyReleaseStatus"),
        xmpKey(PLUS_NAMESPACE, "PropertyReleaseID"),
        xmpKey(PLUS_NAMESPACE, "ImageCreator"),
        xmpKey(PLUS_NAMESPACE, "ImageSupplier"),
        xmpKey(IPTC_CORE_NAMESPACE, "Location"),
        xmpKey(IPTC_CORE_NAMESPACE, "CountryCode"),
        xmpKey(IPTC_CORE_NAMESPACE, "IntellectualGenre"),
        xmpKey(IPTC_CORE_NAMESPACE, "Scene"),
        xmpKey(IPTC_CORE_NAMESPACE, "SubjectCode"),
        xmpKey(IPTC_CORE_NAMESPACE, "CreatorContactInfo"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "Event"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "DigitalSourceType"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "DataMining"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AISystemUsed"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AISystemVersionUsed"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AIPrompt"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AIPromptWriter"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AIPromptInformation"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "AIPromptWriterName"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "ModelReleaseStatus"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "ModelReleaseID"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "PropertyReleaseStatus"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "PropertyReleaseID"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "PersonInImage"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "OrganisationInImageName"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "OrganisationInImageCode"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "LocationCreated"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "LocationShown"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "ImageCreator"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "ImageSupplier"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "RegistryEntry"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "RegistryId"),
        xmpKey(IPTC_EXTENSION_NAMESPACE, "ArtworkOrObject"),
        xmpKey(FIELDCAM_NAMESPACE, "templateId"),
        xmpKey(FIELDCAM_NAMESPACE, "templateName"),
        xmpKey(FIELDCAM_NAMESPACE, "templateType"),
        xmpKey(FIELDCAM_NAMESPACE, "sceneFields")
    )
}
