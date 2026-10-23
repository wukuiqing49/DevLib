package com.wkq.iptc.feature.press.metadata

import androidx.exifinterface.media.ExifInterface
import com.wkq.dev.metadata.model.IptcImageRegion
import com.wkq.dev.metadata.model.IptcImageRegionBoundary
import com.wkq.dev.metadata.model.IptcImageRegionVertex
import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.MetadataTemplate
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcType
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object MetadataVerifier {

    /** 仅要求已接受的部分仍正确；不把被跳过的部分改写成验证成功。 */
    fun verifyAccepted(file: File, template: MetadataTemplate, expectGps: Boolean,
                       accepted: MetadataVerificationResult): MetadataVerificationResult {
        val actual = verify(file, template, expectGps)
        check((!accepted.exifVerified || actual.exifVerified) &&
            (!accepted.iptcVerified || actual.iptcVerified) && (!accepted.xmpVerified || actual.xmpVerified)) {
            "Final metadata verification failed"
        }
        return accepted
    }

    fun verifyEmbeddedXmp(file: File, template: MetadataTemplate, expectGps: Boolean): MetadataVerificationResult {
        val exif = ExifInterface(file)
        val exifVerified = !expectGps || exif.latLong != null
        val xmpVerified = verifyXmp(MetadataXmpStore.read(file), NativeIptcTemplate.forXmp(template))
        return MetadataVerificationResult(exifVerified, false, xmpVerified,
            if (exifVerified && xmpVerified) null else "Embedded metadata verification failed")
    }

    private const val TAG = "MetadataVerifier"

    /** 仅验证附属 XMP，不代表照片已经写入 EXIF 或 IPTC。 */
    fun verifySidecar(sidecarFile: File, template: MetadataTemplate): Boolean {
        return runCatching { verifyXmp(sidecarFile.readText(Charsets.UTF_8), template) }
            .getOrDefault(false)
    }

    fun verify(photoFile: File, template: MetadataTemplate, expectGps: Boolean): MetadataVerificationResult {
        val exif = runCatching { ExifInterface(photoFile) }.getOrNull()
        val xmp = MetadataXmpStore.read(photoFile)
        val exifVerified = exif != null &&
            (!expectGps || exif.latLong != null)
        val xmpVerified = verifyXmp(xmp, if (com.wkq.util.exif.CleanJpegExporter.isJpeg(photoFile)) template
            else NativeIptcTemplate.forXmp(template))
        // 非JPEG的IPTC字段存在于XMP，不能要求它具有APP13。
        val iptcVerified = if (com.wkq.util.exif.CleanJpegExporter.isJpeg(photoFile)) {
            verifyIptc(photoFile, template)
        } else xmpVerified
        val failedParts = buildList {
            if (!exifVerified) add("EXIF")
            if (!iptcVerified) add("IPTC")
            if (!xmpVerified) add("XMP")
        }
        val error = failedParts.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Metadata verification failed: ")
        return MetadataVerificationResult(exifVerified, iptcVerified, xmpVerified, error)
    }

    private fun verifyIptc(photoFile: File, template: MetadataTemplate): Boolean {
        return runCatching {
            val byteSource = ByteSource.file(photoFile)
            val metadata = JpegImageParser().getPhotoshopMetadata(byteSource, JpegImagingParameters())
            val records = metadata?.photoshopApp13Data?.records.orEmpty()

            fun hasRecord(type: IptcType, expectedValue: String): Boolean {
                val cleanValue = expectedValue.trim()
                if (cleanValue.isBlank()) return true
                return records.any { it.iptcType.type == type.type && it.value.trim() == cleanValue }
            }

            val coreChecks = listOf(
                IptcTypes.OBJECT_NAME to enabledValue(template, "title", template.title),
                IptcTypes.HEADLINE to enabledValue(template, "headline", template.headline),
                IptcTypes.CAPTION_ABSTRACT to enabledValue(template, "caption", template.caption),
                IptcTypes.BYLINE to enabledValue(template, "iptcAuthor", template.iptcAuthor.ifBlank { template.creator }),
                IptcTypes.WRITER_EDITOR to enabledValue(template, "writer", template.writer),
                IptcTypes.CATEGORY to enabledValue(template, "category", template.category),
                IptcTypes.CITY to enabledValue(template, "city", template.city),
                IptcTypes.PROVINCE_STATE to enabledValue(template, "state", template.state),
                IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME to enabledValue(template, "country", template.country),
                IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE to enabledValue(template, "isoCountryCode", template.isoCountryCode),
                IptcTypes.CREDIT to enabledValue(template, "credit", template.credit),
                IptcTypes.SOURCE to enabledValue(template, "source", template.source),
                IptcTypes.COPYRIGHT_NOTICE to enabledValue(template, "copyright", template.copyright)
            )

            val standardChecks = template.standardFields
                .filterKeys(template::shouldWriteStandardField)
                .mapNotNull { (field, _) ->
                    IIM_FIELD_TYPES[field]?.let { it to template.standardValue(field) }
                }

            val keywordsVerified = if (template.isFieldEnabled("keywords") && template.keywords.isNotEmpty()) {
                template.keywords.all { keyword -> hasRecord(IptcTypes.KEYWORDS, keyword) }
            } else {
                true
            }
            val standardKeywordsVerified = if (template.shouldWriteStandardField(MetadataFieldDefinitions.IIM_KEYWORDS)) {
                template.standardValue(MetadataFieldDefinitions.IIM_KEYWORDS)
                    .split(",", "\uFF0C", ";", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .all { keyword -> hasRecord(IptcTypes.KEYWORDS, keyword) }
            } else {
                true
            }

            keywordsVerified &&
                standardKeywordsVerified &&
                coreChecks.all { (type, value) -> hasRecord(type, value) } &&
                standardChecks.all { (type, value) -> hasRecord(type, value) }
        }.getOrElse { e ->
            android.util.Log.w(TAG, "verifyIptc exception: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun verifyXmp(xmp: String, template: MetadataTemplate): Boolean {
        if (xmp.isBlank()) {
            android.util.Log.w(TAG, "verifyXmp notice: xmp packet is blank")
            return false
        }
        return runCatching {
            val root = parseXml(xmp)
            // 旧模型中这些值没有对应写入实现，不能把未写入的数据标为已验证。
            if ((template.isFieldEnabled("subtitle") && template.subtitle.isNotBlank()) ||
                (template.isFieldEnabled("artist") && template.artist.isNotBlank()) ||
                (template.isFieldEnabled("jobTitle") && template.jobTitle.isNotBlank())) return@runCatching false
            val sceneItems = findNodes(root, "fieldcam:sceneFields").flatMap { findNodes(it, "rdf:li") }
            if (template.customFields.any { (name, value) -> name.isNotBlank() && sceneItems.none { item ->
                    hasXmpValue(item, "fieldcam:name", name) &&
                        (value.isBlank() || hasXmpValue(item, "fieldcam:value", value))
                } }) return@runCatching false
            if (template.isFieldEnabled("keywords") &&
                template.keywords.any { !hasXmpValue(root, "dc:subject", it) }) return@runCatching false
            val coreChecks = listOf(
                "dc:title" to enabledValue(template, "title", template.title),
                "photoshop:Headline" to enabledValue(template, "headline", template.headline),
                "dc:description" to enabledValue(template, "caption", template.caption),
                "dc:creator" to enabledValue(template, "iptcAuthor", template.iptcAuthor.ifBlank { template.creator }),
                "photoshop:CaptionWriter" to enabledValue(template, "writer", template.writer),
                "photoshop:Credit" to enabledValue(template, "credit", template.credit),
                "photoshop:Source" to enabledValue(template, "source", template.source),
                "dc:rights" to enabledValue(template, "copyright", template.copyright),
                "xmpRights:UsageTerms" to enabledValue(
                    template,
                    "rightsUsageTerms",
                    template.rightsUsageTerms
                ),
                "plus:CopyrightOwnerName" to enabledValue(
                    template,
                    "copyrightOwner",
                    template.copyrightOwner
                ),
                "photoshop:City" to enabledValue(template, "city", template.city),
                "photoshop:State" to enabledValue(template, "state", template.state),
                "photoshop:Country" to enabledValue(template, "country", template.country),
                "Iptc4xmpCore:Location" to enabledValue(template, "location", template.location),
                "Iptc4xmpCore:CountryCode" to enabledValue(template, "isoCountryCode", template.isoCountryCode),
                "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiEmailWork" to enabledValue(template, "email", template.email)
            )
            for ((tag, value) in coreChecks) {
                if (value.isNotBlank() && !hasXmpValue(root, tag, value)) {
                    android.util.Log.w(TAG, "verifyXmp core check notice: tag=$tag")
                    return@runCatching false
                }
            }
            val standardChecks = template.standardFields
                .filterKeys(template::shouldWriteStandardField)
                .mapNotNull { (field, _) ->
                    XMP_FIELD_TAGS[field]?.let {
                        it to XmpWriter.resolvedStandardValue(template, field, "")
                    }
                }
                .filterNot { (tag, _) -> coreChecks.any { it.first == tag && it.second.isNotBlank() } }
            for ((tag, value) in standardChecks) {
                if (value.isNotBlank() && !hasXmpValue(root, tag, value)) {
                    android.util.Log.w(TAG, "verifyXmp standard check notice: tag=$tag")
                    return@runCatching false
                }
            }
            val expectedRegions = if (template.shouldWriteStandardField(MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION)) {
                template.iptcDocument.imageRegions()
            } else {
                emptyList()
            }
            val regionsVerified = verifyImageRegions(root, expectedRegions)
            if (!regionsVerified) {
                android.util.Log.w(TAG, "verifyXmp image regions notice")
                return@runCatching false
            }

            true
        }.getOrElse { e ->
            android.util.Log.w(TAG, "verifyXmp exception: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun enabledValue(template: MetadataTemplate, field: String, value: String): String {
        return if (template.isFieldEnabled(field)) value.trim() else ""
    }

    private fun hasXmpValue(root: Element, tag: String, expectedValue: String): Boolean {
        val cleanValue = expectedValue.trim()
        if (cleanValue.isBlank()) return true

        val matchedNodes = findNodes(root, tag)
        return matchedNodes.any { node ->
            val allTexts = extractAllTexts(node)
            if (cleanValue in allTexts) return@any true
            val expectedParts = cleanValue.split(",", "\uFF0C", ";", "\n")
                .map(String::trim)
                .filter(String::isNotBlank)
            expectedParts.isNotEmpty() && expectedParts.all { it in allTexts }
        }
    }

    /**
     * 支持 XMP 的嵌套属性路径，例如 CreatorContactInfo/CiAdrExtadr。
     * RDF 容器可能位于两段标签之间，因此后续段按父节点的所有后代查找。
     */
    private fun findNodes(root: Element, fullTag: String): List<Element> {
        val path = fullTag.split('/').filter(String::isNotBlank)
        if (path.isEmpty()) return emptyList()

        var matches = findDescendants(root, path.first())
        path.drop(1).forEach { segment ->
            matches = matches.flatMap { parent -> findDescendants(parent, segment) }
        }
        return matches
    }

    private fun findDescendants(root: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        fun walk(element: Element) {
            if (element.matchesXmpTag(tag)) {
                result.add(element)
            }
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                walk(child)
            }
        }
        walk(root)
        return result
    }

    private fun Element.matchesXmpTag(tag: String): Boolean {
        val localName = tag.substringAfterLast(':')
        val name = nodeName
        val local = this.localName ?: name.substringAfter(':')
        return name.equals(tag, ignoreCase = true) ||
            local.equals(localName, ignoreCase = true) ||
            name.endsWith(":$localName", ignoreCase = true)
    }

    private fun extractAllTexts(node: Element): Set<String> {
        val texts = mutableSetOf<String>()
        val direct = node.textContent?.trim().orEmpty()
        if (direct.isNotBlank()) {
            texts.add(direct)
            texts.addAll(
                direct.split(",", "\uFF0C", ";", "\n")
                    .map(String::trim)
                    .filter(String::isNotBlank)
            )
        }
        val children = node.getElementsByTagName("*")
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val t = child.textContent?.trim().orEmpty()
            if (t.isNotBlank()) {
                texts.add(t)
            }
        }
        return texts
    }

    private fun verifyImageRegions(root: Element, expectedRegions: List<IptcImageRegion>): Boolean {
        if (expectedRegions.isEmpty()) return true
        val actualRegions = parseImageRegions(root)
        return expectedRegions.all { expected ->
            actualRegions.any { actual -> actual.matches(expected) }
        }
    }

    private fun parseImageRegions(root: Element): List<IptcImageRegion> {
        val containers = root.getElementsByTagName("Iptc4xmpExt:ImageRegion")
        return buildList {
            for (index in 0 until containers.length) {
                val container = containers.item(index) as? Element ?: continue
                val bag = container.directChild("rdf:Bag") ?: container.directChild("Bag")
                val items = bag?.directChildren("rdf:li").orEmpty() +
                    bag?.directChildren("li").orEmpty()
                items.mapNotNullTo(this) { it.toImageRegion() }
            }
        }
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
            vertices = directChild("Iptc4xmpExt:rbVertices")
                ?.let(::parseVertices)
                .orEmpty(),
            radius = directText("Iptc4xmpExt:rbRadius").toDoubleOrNull(),
            x = directText("Iptc4xmpExt:rbX").toDoubleOrNull(),
            y = directText("Iptc4xmpExt:rbY").toDoubleOrNull(),
            width = directText("Iptc4xmpExt:rbW").toDoubleOrNull(),
            height = directText("Iptc4xmpExt:rbH").toDoubleOrNull()
        )
    }

    private fun parseVertices(element: Element): List<IptcImageRegionVertex> {
        val seq = element.directChild("rdf:Seq") ?: element.directChild("Seq")
        val items = seq?.directChildren("rdf:li").orEmpty() + seq?.directChildren("li").orEmpty()
        return items.mapNotNull { item ->
            val x = item.directText("Iptc4xmpExt:rbX").toDoubleOrNull()
            val y = item.directText("Iptc4xmpExt:rbY").toDoubleOrNull()
            if (x != null && y != null) IptcImageRegionVertex(x, y) else null
        }
    }

    private fun Element.directBagValues(tag: String): List<String> {
        val child = directChild(tag) ?: return emptyList()
        val bag = child.directChild("rdf:Bag") ?: child.directChild("Bag")
        val items = bag?.directChildren("rdf:li").orEmpty() + bag?.directChildren("li").orEmpty()
        return items.mapNotNull { it.textContent?.trim() }.filter(String::isNotBlank)
    }

    private fun IptcImageRegion.matches(other: IptcImageRegion): Boolean {
        if (!matchesText(id, other.id)) return false
        if (!matchesText(name, other.name)) return false
        if (!roles.containsAll(other.roles)) return false
        if (!contentTypes.containsAll(other.contentTypes)) return false
        if (!persons.containsAll(other.persons)) return false
        if (!organisations.containsAll(other.organisations)) return false
        return boundary.matches(other.boundary)
    }

    private fun IptcImageRegionBoundary.matches(other: IptcImageRegionBoundary): Boolean {
        if (!matchesText(shape, other.shape)) return false
        if (!matchesText(unit, other.unit)) return false
        if (!matchesNumber(radius, other.radius)) return false
        if (!matchesNumber(x, other.x)) return false
        if (!matchesNumber(y, other.y)) return false
        if (!matchesNumber(width, other.width)) return false
        if (!matchesNumber(height, other.height)) return false
        if (vertices.size != other.vertices.size) return false
        return vertices.zip(other.vertices).all { (v1, v2) ->
            matchesNumber(v1.x, v2.x) && matchesNumber(v1.y, v2.y)
        }
    }

    private fun matchesText(actual: String, expected: String): Boolean =
        expected.isBlank() || actual.trim() == expected.trim()

    private fun matchesNumber(actual: Double?, expected: Double?): Boolean {
        expected ?: return true
        actual ?: return false
        return kotlin.math.abs(actual - expected) < 0.000001
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

    private fun parseXml(xml: String): Element {
        val cleanXml = xml.trim().let { raw ->
            val start = raw.indexOf("<x:xmpmeta")
            val end = raw.lastIndexOf("</x:xmpmeta>")
            if (start >= 0 && end > start) {
                raw.substring(start, end + "</x:xmpmeta>".length)
            } else if (raw.contains("<rdf:RDF")) {
                val rStart = raw.indexOf("<rdf:RDF")
                val rEnd = raw.lastIndexOf("</rdf:RDF>")
                if (rStart >= 0 && rEnd > rStart) {
                    raw.substring(rStart, rEnd + "</rdf:RDF>".length)
                } else raw
            } else raw
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(cleanXml))).documentElement
    }

    private val IIM_FIELD_TYPES: Map<String, IptcType> = mutableMapOf<String, IptcType>().apply {
        put(MetadataFieldDefinitions.IIM_RECORD_VERSION, IptcTypes.RECORD_VERSION)
        put(MetadataFieldDefinitions.IIM_OBJECT_TYPE, IptcTypes.OBJECT_TYPE_REFERENCE)
        put(MetadataFieldDefinitions.IIM_OBJECT_ATTRIBUTE, IptcTypes.OBJECT_ATTRIBUTE_REFERENCE)
        put(MetadataFieldDefinitions.IIM_OBJECT_NAME, IptcTypes.OBJECT_NAME)
        put(MetadataFieldDefinitions.IIM_EDIT_STATUS, IptcTypes.EDIT_STATUS)
        put(MetadataFieldDefinitions.IIM_URGENCY, IptcTypes.URGENCY)
        put(MetadataFieldDefinitions.IIM_CATEGORY, IptcTypes.CATEGORY)
        put(MetadataFieldDefinitions.IIM_SUPPLEMENTAL_CATEGORY, IptcTypes.SUPPLEMENTAL_CATEGORY)
        put(MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS, IptcTypes.SPECIAL_INSTRUCTIONS)
        put(MetadataFieldDefinitions.IIM_DATE_CREATED, IptcTypes.DATE_CREATED)
        put(MetadataFieldDefinitions.IIM_TIME_CREATED, IptcTypes.TIME_CREATED)
        put(MetadataFieldDefinitions.IIM_JOB_IDENTIFIER, IptcTypes.ORIGINAL_TRANSMISSION_REFERENCE)
        put(MetadataFieldDefinitions.IIM_BYLINE, IptcTypes.BYLINE)
        put(MetadataFieldDefinitions.IIM_BYLINE_TITLE, IptcTypes.BYLINE_TITLE)
        put(MetadataFieldDefinitions.IIM_CITY, IptcTypes.CITY)
        put(MetadataFieldDefinitions.IIM_SUBLOCATION, IptcTypes.SUBLOCATION)
        put(MetadataFieldDefinitions.IIM_STATE, IptcTypes.PROVINCE_STATE)
        put(MetadataFieldDefinitions.IIM_COUNTRY_CODE, IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE)
        put(MetadataFieldDefinitions.IIM_COUNTRY_NAME, IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME)
        put(MetadataFieldDefinitions.IIM_HEADLINE, IptcTypes.HEADLINE)
        put(MetadataFieldDefinitions.IIM_CREDIT, IptcTypes.CREDIT)
        put(MetadataFieldDefinitions.IIM_SOURCE, IptcTypes.SOURCE)
        put(MetadataFieldDefinitions.IIM_COPYRIGHT, IptcTypes.COPYRIGHT_NOTICE)
        put(MetadataFieldDefinitions.IIM_CAPTION, IptcTypes.CAPTION_ABSTRACT)
        put(MetadataFieldDefinitions.IIM_WRITER, IptcTypes.WRITER_EDITOR)
    }

    private val XMP_FIELD_TAGS: Map<String, String> = mutableMapOf<String, String>().apply {
        put(MetadataFieldDefinitions.DC_TITLE, "dc:title")
        put(MetadataFieldDefinitions.DC_DESCRIPTION, "dc:description")
        put(MetadataFieldDefinitions.DC_CREATOR, "dc:creator")
        put(MetadataFieldDefinitions.DC_RIGHTS, "dc:rights")
        put(MetadataFieldDefinitions.DC_SOURCE, "dc:source")
        put(MetadataFieldDefinitions.DC_SUBJECT, "dc:subject")
        put(MetadataFieldDefinitions.IIM_WRITER, "photoshop:CaptionWriter")
        put(MetadataFieldDefinitions.IIM_BYLINE_TITLE, "photoshop:AuthorsPosition")
        put(MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS, "photoshop:Instructions")
        put(MetadataFieldDefinitions.IIM_CATEGORY, "photoshop:Category")
        put(MetadataFieldDefinitions.IIM_URGENCY, "photoshop:Urgency")
        put(MetadataFieldDefinitions.IIM_SUPPLEMENTAL_CATEGORY, "photoshop:SupplementalCategories")
        put(MetadataFieldDefinitions.PHOTOSHOP_HEADLINE, "photoshop:Headline")
        put(MetadataFieldDefinitions.PHOTOSHOP_CREDIT, "photoshop:Credit")
        put(MetadataFieldDefinitions.PHOTOSHOP_SOURCE, "photoshop:Source")
        put(MetadataFieldDefinitions.PHOTOSHOP_CITY, "photoshop:City")
        put(MetadataFieldDefinitions.PHOTOSHOP_STATE, "photoshop:State")
        put(MetadataFieldDefinitions.PHOTOSHOP_COUNTRY, "photoshop:Country")
        put(MetadataFieldDefinitions.PHOTOSHOP_DATE_CREATED, "photoshop:DateCreated")
        put(MetadataFieldDefinitions.PHOTOSHOP_TRANSMISSION_REFERENCE, "photoshop:TransmissionReference")
        put(MetadataFieldDefinitions.IPTC_CORE_LOCATION, "Iptc4xmpCore:Location")
        put(MetadataFieldDefinitions.IPTC_CORE_COUNTRY_CODE, "Iptc4xmpCore:CountryCode")
        put(MetadataFieldDefinitions.IPTC_CORE_INTELLECTUAL_GENRE, "Iptc4xmpCore:IntellectualGenre")
        put(MetadataFieldDefinitions.IPTC_CORE_SCENE, "Iptc4xmpCore:Scene")
        put(MetadataFieldDefinitions.IPTC_CORE_SUBJECT_CODE, "Iptc4xmpCore:SubjectCode")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiAdrCity")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_REGION, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiAdrRegion")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiAdrCtry")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_COUNTRY_CODE, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiAdrCtryCode")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_ADDRESS, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiAdrExtadr")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_EMAIL, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiEmailWork")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_PHONE, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiTelWork")
        put(MetadataFieldDefinitions.IPTC_CORE_CREATOR_WEB_URL, "Iptc4xmpCore:CreatorContactInfo/Iptc4xmpCore:CiUrlWork")
        put(MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE, "Iptc4xmpExt:PersonInImage")
        put(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_CITY, "Iptc4xmpExt:LocationShown/Iptc4xmpExt:City")
        put(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_REGION, "Iptc4xmpExt:LocationShown/Iptc4xmpExt:ProvinceState")
        put(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME, "Iptc4xmpExt:LocationShown/Iptc4xmpExt:CountryName")
        put(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE, "Iptc4xmpExt:LocationShown/Iptc4xmpExt:CountryCode")
        put(MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION, "Iptc4xmpExt:LocationShown/Iptc4xmpExt:Sublocation")
        put(MetadataFieldDefinitions.IPTC_EXT_EVENT, "Iptc4xmpExt:Event")
        put(MetadataFieldDefinitions.IPTC_EXT_DIGITAL_SOURCE_TYPE, "Iptc4xmpExt:DigitalSourceType")
        put(MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME, "Iptc4xmpExt:OrganisationInImageName")
        put(MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE, "Iptc4xmpExt:OrganisationInImageCode")
        put(MetadataFieldDefinitions.IPTC_EXT_IMAGE_CREATOR_NAME, "Iptc4xmpExt:ImageCreator/Iptc4xmpExt:ImageCreatorName")
        put(MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_NAME, "Iptc4xmpExt:ImageSupplier/Iptc4xmpExt:ImageSupplierName")
        put(MetadataFieldDefinitions.IPTC_EXT_IMAGE_SUPPLIER_ID, "Iptc4xmpExt:ImageSupplier/Iptc4xmpExt:ImageSupplierID")
        put(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ENTRY_ROLE, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegEntryRole")
        put(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ITEM_ID, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegItemId")
        put(MetadataFieldDefinitions.IPTC_EXT_REGISTRY_ORGANISATION_ID, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegOrgId")
        put(MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_STATUS, "Iptc4xmpExt:ModelReleaseStatus")
        put(MetadataFieldDefinitions.IPTC_EXT_MODEL_RELEASE_ID, "Iptc4xmpExt:ModelReleaseId")
        put(MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_STATUS, "Iptc4xmpExt:PropertyReleaseStatus")
        put(MetadataFieldDefinitions.IPTC_EXT_PROPERTY_RELEASE_ID, "Iptc4xmpExt:PropertyReleaseId")
        put(MetadataFieldDefinitions.IPTC_EXT_DATA_MINING, "Iptc4xmpExt:DataMining")
        put(MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_USED, "Iptc4xmpExt:AiSystemUsed")
        put(MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED, "Iptc4xmpExt:AiSystemVersionUsed")
        put(MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT, "Iptc4xmpExt:AiPrompt")
        put(MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT_WRITER, "Iptc4xmpExt:AiPromptWriter")
        put(MetadataFieldDefinitions.PLUS_LICENSOR_NAME, "plus:Licensor/plus:LicensorName")
        put(MetadataFieldDefinitions.PLUS_LICENSOR_EMAIL, "plus:Licensor/plus:LicensorEmail")
        put(MetadataFieldDefinitions.PLUS_LICENSOR_URL, "plus:Licensor/plus:LicensorURL")
        put(MetadataFieldDefinitions.PLUS_LICENSOR_PHONE, "plus:Licensor/plus:LicensorTelephone1")
        put(MetadataFieldDefinitions.PLUS_COPYRIGHT_OWNER_NAME, "plus:CopyrightOwner/plus:CopyrightOwnerName")
    }
}
