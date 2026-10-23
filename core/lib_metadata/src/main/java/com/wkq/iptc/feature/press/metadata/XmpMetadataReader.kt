package com.wkq.iptc.feature.press.metadata

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object XmpMetadataReader {

    fun readPacket(file: File): String = when {
        EmbeddedContainerMetadata.format(file) != null -> MetadataXmpStore.read(file)
        NativeContainerMetadata.isCandidate(file) -> runCatching {
            androidx.exifinterface.media.ExifInterface(file)
                .getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
        else -> JpegXmpStore.read(file)
    }

    fun readPacketFields(packet: String): Result<IptcXmpFields> = runCatching { parseIptcXmpFields(packet) }

    data class FieldcamMetadata(
        val templateId: String = "",
        val templateName: String = "",
        val templateType: String = "",
        val sceneFields: List<SceneField> = emptyList()
    ) {
        val hasContent: Boolean
            get() = templateId.isNotBlank() ||
                templateName.isNotBlank() ||
                templateType.isNotBlank() ||
                sceneFields.isNotEmpty()
    }

    data class SceneField(
        val name: String,
        val value: String
    )

    data class IptcXmpFields(
        val title: String = "",
        val headline: String = "",
        val caption: String = "",
        val persons: List<String> = emptyList(),
        val event: String = "",
        val locationShown: String = "",
        val jobIdentifier: String = "",
        val keywords: List<String> = emptyList(),
        val creator: String = "",
        val credit: String = "",
        val source: String = "",
        val copyright: String = "",
        val country: String = "",
        val state: String = "",
        val city: String = "",
        val location: String = "",
        val isoCountryCode: String = "",
        val intellectualGenre: String = "",
        val dateCreated: String = "",
        val rightsUsageTerms: String = "",
        val copyrightStatus: String = "",
        val copyrightOwner: String = ""
    ) {
        val hasContent: Boolean
            get() = listOf(
                title, headline, caption, event, locationShown, jobIdentifier, creator,
                credit, source, copyright, country, state, city, location, isoCountryCode,
                intellectualGenre, dateCreated, rightsUsageTerms, copyrightStatus, copyrightOwner
            ).any(String::isNotBlank) || persons.isNotEmpty() || keywords.isNotEmpty()
    }

    fun readFieldcamMetadata(photoFile: File): Result<FieldcamMetadata> = runCatching {
        val xmp = readPacket(photoFile)
        if (xmp.isBlank()) return@runCatching FieldcamMetadata()
        val root = parseXmlRoot(xmp) ?: return@runCatching FieldcamMetadata()
        FieldcamMetadata(
            templateId = root.firstText("fieldcam:templateId").cleanQuestionPlaceholder(),
            templateName = root.firstText("fieldcam:templateName").cleanQuestionPlaceholder(),
            templateType = root.firstText("fieldcam:templateType").cleanQuestionPlaceholder(),
            sceneFields = root.sceneFields()
        )
    }

    fun readIptcXmpFields(photoFile: File): Result<IptcXmpFields> = runCatching {
        val xmp = readPacket(photoFile)
        parseIptcXmpFields(xmp)
    }

    fun readSidecarFields(sidecarFile: File): Result<IptcXmpFields> = runCatching {
        parseIptcXmpFields(sidecarFile.readText(Charsets.UTF_8))
    }

    internal fun parseIptcXmpFields(xmp: String): IptcXmpFields {
        if (xmp.isBlank()) return IptcXmpFields()
        val root = parseXmlRoot(xmp) ?: return IptcXmpFields()
        return IptcXmpFields(
            title = root.firstText("dc:title").cleanQuestionPlaceholder(),
            headline = root.firstText("photoshop:Headline").cleanQuestionPlaceholder(),
            caption = root.firstText("dc:description")
                .ifBlank { root.firstText("Iptc4xmpCore:Description") }
                .cleanQuestionPlaceholder(),
            persons = root.firstTextList("Iptc4xmpExt:PersonInImage")
                .map { it.cleanQuestionPlaceholder() }
                .filter(String::isNotBlank),
            event = root.firstText("Iptc4xmpExt:Event").cleanQuestionPlaceholder(),
            locationShown = root.parseLocationStructure("Iptc4xmpExt:LocationShown"),
            jobIdentifier = root.firstText("photoshop:TransmissionReference")
                .ifBlank { root.firstText("Iptc4xmpCore:JobId") }
                .cleanQuestionPlaceholder(),
            keywords = root.firstTextList("dc:subject")
                .map { it.cleanQuestionPlaceholder() }
                .filter(String::isNotBlank),
            creator = root.firstText("dc:creator")
                .ifBlank { root.firstText("tiff:Artist") }
                .cleanQuestionPlaceholder(),
            credit = root.firstText("photoshop:Credit").cleanQuestionPlaceholder(),
            source = root.firstText("photoshop:Source").cleanQuestionPlaceholder(),
            copyright = root.firstText("dc:rights")
                .ifBlank { root.firstText("xmpRights:UsageTerms") }
                .cleanQuestionPlaceholder(),
            country = root.firstText("photoshop:Country")
                .ifBlank { root.firstText("Iptc4xmpCore:CountryName") }
                .cleanQuestionPlaceholder(),
            state = root.firstText("photoshop:State")
                .ifBlank { root.firstText("Iptc4xmpCore:ProvinceState") }
                .cleanQuestionPlaceholder(),
            city = root.firstText("photoshop:City").cleanQuestionPlaceholder(),
            location = root.firstText("Iptc4xmpCore:Location").cleanQuestionPlaceholder(),
            isoCountryCode = root.firstText("Iptc4xmpCore:CountryCode").cleanQuestionPlaceholder(),
            intellectualGenre = root.firstText("Iptc4xmpCore:IntellectualGenre").cleanQuestionPlaceholder(),
            dateCreated = root.firstText("photoshop:DateCreated")
                .ifBlank { root.firstText("xmp:CreateDate") }
                .cleanQuestionPlaceholder(),
            rightsUsageTerms = root.firstText("xmpRights:UsageTerms").cleanQuestionPlaceholder(),
            copyrightStatus = root.firstText("xmpRights:Marked").cleanQuestionPlaceholder(),
            copyrightOwner = root.firstText("plus:CopyrightOwnerName").cleanQuestionPlaceholder()
        )
    }

    private fun parseXmlRoot(xmp: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xmp))).documentElement
        }.getOrNull()
    }

    private fun Element.sceneFields(): List<SceneField> {
        val container = firstElement("fieldcam:sceneFields") ?: return emptyList()
        val items = container.getElementsByTagName("rdf:li")
        return buildList {
            for (index in 0 until items.length) {
                val item = items.item(index) as? Element ?: continue
                val name = item.firstText("fieldcam:name").cleanQuestionPlaceholder()
                if (name.isBlank()) continue
                add(
                    SceneField(
                        name = name,
                        value = item.firstText("fieldcam:value").cleanQuestionPlaceholder()
                    )
                )
            }
        }
    }

    private fun Element.firstText(tagName: String): String {
        val element = firstElement(tagName) ?: return ""
        val listItems = element.getElementsByTagName("rdf:li")
        if (listItems.length == 0) return element.textContent?.trim().orEmpty()

        // rdf:Alt 可能包含多个语言值。优先 x-default，避免详情页拼接所有语言文本。
        var firstValue = ""
        for (index in 0 until listItems.length) {
            val item = listItems.item(index) as? Element ?: continue
            val value = item.textContent?.trim().orEmpty()
            if (value.isBlank()) continue
            if (firstValue.isBlank()) firstValue = value
            if (item.getAttribute("xml:lang").equals("x-default", ignoreCase = true)) {
                return value
            }
        }
        return firstValue
    }

    private fun Element.firstTextList(tagName: String): List<String> {
        val el = firstElement(tagName) ?: return emptyList()
        val items = el.getElementsByTagName("rdf:li")
        return buildList {
            for (i in 0 until items.length) {
                val text = items.item(i)?.textContent?.trim().orEmpty()
                if (text.isNotBlank()) add(text)
            }
        }.ifEmpty {
            el.textContent?.trim()?.split(",")?.map(String::trim)?.filter(String::isNotBlank)
                ?: emptyList()
        }
    }

    private fun Element.parseLocationStructure(tagName: String): String {
        val locEl = firstElement(tagName) ?: return ""
        val liEl = locEl.firstElement("rdf:li") ?: locEl
        val country = liEl.firstText("Iptc4xmpExt:CountryName")
            .ifBlank { liEl.firstText("Iptc4xmpExt:Country") }
        val state = liEl.firstText("Iptc4xmpExt:ProvinceState")
            .ifBlank { liEl.firstText("Iptc4xmpExt:State") }
        val city = liEl.firstText("Iptc4xmpExt:City")
        val sublocation = liEl.firstText("Iptc4xmpExt:Sublocation")
            .ifBlank { liEl.firstText("Iptc4xmpExt:Location") }
        val code = liEl.firstText("Iptc4xmpExt:CountryCode")

        val parts = listOf(country, state, city, sublocation)
            .map { it.cleanQuestionPlaceholder() }
            .filter(String::isNotBlank)
        val mainStr = parts.joinToString(" ")

        val cleanCode = code.cleanQuestionPlaceholder()
        return if (cleanCode.isNotBlank()) {
            if (mainStr.isNotBlank()) "$mainStr ($cleanCode)" else cleanCode
        } else {
            mainStr
        }
    }

    private fun String.cleanQuestionPlaceholder(): String {
        val clean = trim()
        return clean.takeUnless { it.isQuestionMarkPlaceholder() }.orEmpty()
    }

    private fun String.isQuestionMarkPlaceholder(): Boolean {
        val compact = filterNot(Char::isWhitespace)
        return compact.length >= 2 && compact.all { it == '?' || it.code == 0xFF1F }
    }

    private fun Element.firstElement(tagName: String): Element? {
        val nodes = getElementsByTagName(tagName)
        return nodes.item(0) as? Element
    }
}
