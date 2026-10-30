package com.wkq.util.exif

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** 按信息含义匹配跨容器副本；不把未知字段宣称为已完成匿名化。 */
object MetadataPrivacyRules {
    private val location = setOf("city", "state", "province-state", "country", "countryname", "countrycode",
        "country-primarylocationname", "country-primarylocationcode", "sublocation", "location",
        "locationcreated", "locationshown", "worldregion", "locationname", "locationid")
    private val identity = setOf("creator", "artist", "author", "by-line", "by-linetitle", "byline", "bylinetitle",
        "writer-editor", "captionwriter", "rights", "copyright", "copyrightnotice", "credit", "source",
        "ownername", "cameraownername", "make", "model", "software", "creatortool", "lensmake", "lensmodel",
        "imageuniqueid", "documentid", "originaldocumentid", "instanceid", "history", "derivedfrom",
        "datetime", "datetimeoriginal", "datetimedigitized", "datecreated", "timecreated", "createdate",
        "modifydate", "metadatadate", "digitalcreationdate", "digitalcreationtime", "creatorcontactinfo",
        "imagecreator", "imagesupplier", "copyrightowner", "licensor", "personinimage", "personinimagewdetails",
        "usercomment", "imagedescription", "makernote", "makernotes")
    private val technical = setOf("exposuretime", "fnumber", "iso", "isospeedratings", "photographicsensitivity",
        "aperturevalue", "shutterspeedvalue", "exposurebiasvalue", "exposurecompensation", "maxaperturevalue",
        "focallength", "focallengthin35mmfilm", "focallengthin35mmformat", "digitalzoomratio", "flash",
        "whitebalance", "meteringmode", "exposureprogram", "scenetype", "scenecapturetype", "lightsource",
        "subjectdistance", "lensmake", "lensmodel", "lensspecification")

    fun selectedName(name: String, exif: Boolean, personal: Boolean): Boolean {
        val key = name.lowercase(Locale.ROOT)
        return (exif && (key.startsWith("gps") || key in location || key in technical)) ||
            (personal && (key in identity || "serialnumber" in key || key.startsWith("subsectime") ||
                key.startsWith("offsettime")))
    }

    fun selectedIptc(type: Int, iptc: Boolean, exif: Boolean, personal: Boolean): Boolean = iptc ||
        (exif && type in setOf(26, 27, 90, 92, 95, 100, 101)) ||
        (personal && type in setOf(55, 60, 62, 63, 80, 85, 110, 115, 116, 118, 122))

    /** 合并物理 XMP 副本，按名称清理属性和结构节点，保留未选中的描述字段。 */
    fun scrubXmp(packets: List<String>, iptc: Boolean, exif: Boolean, personal: Boolean): String {
        if (iptc) return "" // IPTC 选项原有语义包含整个 XMP。
        val documents = packets.filter(String::isNotBlank).distinct().map(::parse)
        if (documents.isEmpty()) return ""
        val result = documents.first()
        val rdf = result.getElementsByTagNameNS(RDF, "RDF").item(0)
            ?: error("XMP RDF is missing")
        for (document in documents.drop(1)) {
            val other = document.getElementsByTagNameNS(RDF, "RDF").item(0)
                ?: error("XMP RDF is missing")
            for (i in 0 until other.childNodes.length) rdf.appendChild(result.importNode(other.childNodes.item(i), true))
        }
        visit(result.documentElement, 0) { node ->
            node.namespaceURI !in setOf(RDF, XMLNS) && selectedName(node.localName.orEmpty(), exif, personal)
        }
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(result), StreamResult(writer))
        return writer.toString()
    }

    fun countXmp(packet: String, iptc: Boolean, exif: Boolean, personal: Boolean): Int {
        if (packet.isBlank()) return 0
        if (iptc) return 1
        var count = 0
        visit(parse(packet).documentElement, 0) { node ->
            val selected = node.namespaceURI !in setOf(RDF, XMLNS) && selectedName(node.localName.orEmpty(), exif, personal)
            if (selected) count++
            false
        }
        return count
    }

    private fun visit(element: Element, depth: Int, remove: (Node) -> Boolean) {
        require(depth < 128) { "XMP nesting exceeds limit" }
        for (i in element.attributes.length - 1 downTo 0) {
            val attribute = element.attributes.item(i)
            if (remove(attribute)) element.removeAttributeNode(attribute as org.w3c.dom.Attr)
        }
        for (i in element.childNodes.length - 1 downTo 0) {
            val child = element.childNodes.item(i)
            if (child is Element) {
                if (remove(child)) element.removeChild(child) else visit(child, depth + 1, remove)
            }
        }
    }

    private fun parse(packet: String): org.w3c.dom.Document {
        require(packet.length <= 4 * 1024 * 1024 &&
            !packet.contains("<!DOCTYPE", ignoreCase = true) && !packet.contains("<!ENTITY", ignoreCase = true)) {
            "Unsafe XMP structure"
        }
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External XML entities are forbidden") }
        }.parse(InputSource(StringReader(packet)))
    }

    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
}
