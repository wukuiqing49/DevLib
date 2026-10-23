package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.IptcDocument
import com.wkq.dev.metadata.model.IptcImageRegion
import com.wkq.dev.metadata.model.IptcImageRegionBoundary
import com.wkq.dev.metadata.model.IptcImageRegionVertex
import com.wkq.dev.metadata.model.MetadataFieldGroup
import com.wkq.dev.metadata.model.MetadataTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class ExpandedIptcXmpWriterTest {

    @Test
    fun exposesFieldCapabilityMatrixForPresetAndProcessingChoices() {
        val locationCapability = MetadataFieldDefinitions.capability(
            MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION
        )
        val aiCapability = MetadataFieldDefinitions.capability(
            MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT
        )
        val regionCapability = MetadataFieldDefinitions.capability(
            MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION
        )

        assertEquals(MetadataFieldGroup.LOCATION, locationCapability?.group)
        assertTrue(locationCapability?.writeSupported == true)
        assertTrue(locationCapability?.presetSupported == true)
        assertTrue(locationCapability?.perPhotoSupported == true)

        assertEquals(MetadataFieldGroup.AI_DISCLOSURE, aiCapability?.group)
        assertTrue(aiCapability?.writeSupported == true)
        assertTrue(aiCapability?.presetSupported == true)
        assertFalse(aiCapability?.perPhotoSupported == true)

        assertEquals(MetadataFieldGroup.STRUCTURED_ADVANCED, regionCapability?.group)
        assertTrue(regionCapability?.writeSupported == true)
        assertFalse(regionCapability?.presetSupported == true)
        assertFalse(regionCapability?.perPhotoSupported == true)
    }

    @Test
    fun writesExpandedIptcExtensionAndPlusFields() {
        val template = MetadataTemplate(
            id = "expanded_iptc",
            standardFields = mapOf(
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE to "SG",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME to "Singapore",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_REGION to "Central",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_CITY to "Singapore",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION to "Marina Bay",
                MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME to "Example News",
                MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE to "EXN",
                MetadataFieldDefinitions.IPTC_EXT_DATA_MINING to "http://example.com/no-data-mining",
                MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_USED to "Example AI",
                MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED to "2025.1",
                MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT to "A concise editorial caption prompt",
                MetadataFieldDefinitions.PLUS_LICENSOR_NAME to "Example Licensor",
                MetadataFieldDefinitions.PLUS_LICENSOR_EMAIL to "licensor@example.com"
            ),
            enabledFields = listOf(
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_REGION,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_CITY,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_SHOWN_SUBLOCATION,
                MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_NAME,
                MetadataFieldDefinitions.IPTC_EXT_ORGANISATION_IN_IMAGE_CODE,
                MetadataFieldDefinitions.IPTC_EXT_DATA_MINING,
                MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_USED,
                MetadataFieldDefinitions.IPTC_EXT_AI_SYSTEM_VERSION_USED,
                MetadataFieldDefinitions.IPTC_EXT_AI_PROMPT,
                MetadataFieldDefinitions.PLUS_LICENSOR_NAME,
                MetadataFieldDefinitions.PLUS_LICENSOR_EMAIL
            )
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals("SG", doc.firstText("Iptc4xmpExt:CountryCode"))
        assertEquals("Singapore", doc.firstText("Iptc4xmpExt:CountryName"))
        assertEquals("Central", doc.firstText("Iptc4xmpExt:ProvinceState"))
        assertEquals("Marina Bay", doc.firstText("Iptc4xmpExt:Sublocation"))
        assertEquals("Example News", doc.firstText("Iptc4xmpExt:OrganisationInImageName"))
        assertEquals("EXN", doc.firstText("Iptc4xmpExt:OrganisationInImageCode"))
        assertEquals("http://example.com/no-data-mining", doc.firstText("plus:DataMining"))
        assertEquals("Example AI", doc.firstText("Iptc4xmpExt:AISystemUsed"))
        assertEquals("2025.1", doc.firstText("Iptc4xmpExt:AISystemVersionUsed"))
        assertEquals("A concise editorial caption prompt", doc.firstText("Iptc4xmpExt:AIPromptInformation"))
        assertEquals("Example Licensor", doc.firstText("plus:LicensorName"))
        assertEquals("licensor@example.com", doc.firstText("plus:LicensorEmail"))
    }

    @Test
    fun standardFieldsFallbackWhenLegacyTemplateFieldsAreBlank() {
        val template = MetadataTemplate(
            id = "standard_fallback",
            standardFields = mapOf(
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_CITY to "Shanghai",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_REGION to "Shanghai",
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME to "China",
                MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY to "Beijing",
                MetadataFieldDefinitions.DC_TITLE to "Standard title",
                MetadataFieldDefinitions.PHOTOSHOP_HEADLINE to "Standard headline"
            ),
            enabledFields = listOf(
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_CITY,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_REGION,
                MetadataFieldDefinitions.IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME,
                MetadataFieldDefinitions.IPTC_CORE_CREATOR_CITY,
                MetadataFieldDefinitions.DC_TITLE,
                MetadataFieldDefinitions.PHOTOSHOP_HEADLINE
            )
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals("Shanghai", doc.firstText("Iptc4xmpExt:City"))
        assertEquals("Shanghai", doc.firstText("Iptc4xmpExt:ProvinceState"))
        assertEquals("China", doc.firstText("Iptc4xmpExt:CountryName"))
        assertEquals("Beijing", doc.firstText("Iptc4xmpCore:CiAdrCity"))
        assertEquals("Standard title", doc.firstText("dc:title"))
        assertEquals("Standard headline", doc.firstText("photoshop:Headline"))
    }

    @Test
    fun copyrightNoticeAndUsageTermsAreWrittenToSeparateProperties() {
        val template = MetadataTemplate(
            id = "rights_usage",
            copyright = "Copyright 2026 News Agency",
            rightsUsageTerms = "Editorial use only. No resale without written permission.",
            enabledFields = listOf(
                "copyright",
                "rightsUsageTerms",
                MetadataFieldDefinitions.DC_RIGHTS
            )
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals(
            "Copyright 2026 News Agency",
            doc.firstText("dc:rights")
        )
        assertEquals(
            "Editorial use only. No resale without written permission.",
            doc.firstText("xmpRights:UsageTerms")
        )
    }

    @Test
    fun questionMarkPlaceholdersAreNotWrittenAsPeopleMetadata() {
        val template = MetadataTemplate(
            id = "placeholder_people",
            standardFields = mapOf(
                MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE to "??"
            ),
            enabledFields = listOf(MetadataFieldDefinitions.IPTC_EXT_PERSON_IN_IMAGE)
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals("", doc.firstText("Iptc4xmpExt:PersonInImage"))
    }

    @Test
    fun writesStructuredImageRegion() {
        val template = MetadataTemplate(
            id = "image_region",
            enabledFields = listOf(MetadataFieldDefinitions.IPTC_EXT_IMAGE_REGION),
            iptcDocument = IptcDocument.EMPTY.withImageRegions(
                listOf(
                    IptcImageRegion(
                        id = "region-1",
                        name = "Lead subject",
                        boundary = IptcImageRegionBoundary(
                            shape = IptcImageRegionBoundary.SHAPE_RECTANGLE,
                            unit = IptcImageRegionBoundary.UNIT_RELATIVE,
                            x = 0.25,
                            y = 0.2,
                            width = 0.4,
                            height = 0.5
                        ),
                        roles = listOf("main subject"),
                        contentTypes = listOf("person"),
                        persons = listOf("Jane Reporter"),
                        organisations = listOf("Example News")
                    ),
                    IptcImageRegion(
                        id = "region-2",
                        name = "Triangle area",
                        boundary = IptcImageRegionBoundary(
                            shape = IptcImageRegionBoundary.SHAPE_POLYGON,
                            unit = IptcImageRegionBoundary.UNIT_RELATIVE,
                            vertices = listOf(
                                IptcImageRegionVertex(0.1, 0.1),
                                IptcImageRegionVertex(0.5, 0.1),
                                IptcImageRegionVertex(0.3, 0.4)
                            )
                        )
                    )
                )
            )
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals("region-1", doc.firstText("Iptc4xmpExt:rId"))
        assertEquals("Lead subject", doc.firstText("Iptc4xmpExt:Name"))
        assertEquals("rectangle", doc.firstText("Iptc4xmpExt:rbShape"))
        assertEquals("relative", doc.firstText("Iptc4xmpExt:rbUnit"))
        assertEquals("0.25", doc.firstText("Iptc4xmpExt:rbX"))
        assertEquals("0.4", doc.firstText("Iptc4xmpExt:rbW"))
        assertEquals("main subject", doc.firstText("Iptc4xmpExt:rRole"))
        assertEquals("person", doc.firstText("Iptc4xmpExt:rCtype"))
        assertEquals("Jane Reporter", doc.firstText("Iptc4xmpExt:PersonInImage"))
        assertEquals("Example News", doc.firstText("Iptc4xmpExt:OrganisationInImageName"))
        assertEquals(1, doc.getElementsByTagName("Iptc4xmpExt:ImageRegion").length)
        assertEquals(2, doc.getElementsByTagName("Iptc4xmpExt:RegionBoundary").length)
    }

    @Test
    fun keepsDisabledIptcDocumentFieldsOutOfTheXmpPacket() {
        val template = MetadataTemplate(
            id = "disabled_image_region",
            iptcDocument = IptcDocument.EMPTY.withImageRegions(
                listOf(
                    IptcImageRegion(
                        id = "region-disabled",
                        name = "Preset only",
                        boundary = IptcImageRegionBoundary(
                            x = 0.1,
                            y = 0.2,
                            width = 0.3,
                            height = 0.4
                        )
                    )
                )
            )
        )

        val doc = parseXml(XmpWriter.buildTemplatePacket(template))

        assertEquals(0, doc.getElementsByTagName("Iptc4xmpExt:ImageRegion").length)
        assertEquals("", doc.firstText("Iptc4xmpExt:rId"))
    }

    @Test
    fun replacementPreservesUnsupportedIptcExtensionProperties() {
        val existingPacket = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                  xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
                  xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/">
                <rdf:Description rdf:about="">
                  <photoshop:Headline>Old headline</photoshop:Headline>
                  <Iptc4xmpExt:UnsupportedThing>Keep me</Iptc4xmpExt:UnsupportedThing>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val generatedPacket = XmpWriter.buildTemplatePacket(
            MetadataTemplate(
                id = "replacement",
                caption = "Fresh caption",
                enabledFields = listOf("caption")
            )
        )

        val merged = XmpWriter.mergePreservingUnknownProperties(
            existingPacket = existingPacket,
            generatedPacket = generatedPacket,
            replaceManagedProperties = true
        )
        val doc = parseXml(merged)

        assertEquals("", doc.firstText("photoshop:Headline"))
        assertEquals("Fresh caption", doc.firstText("dc:description"))
        assertEquals("Keep me", doc.firstText("Iptc4xmpExt:UnsupportedThing"))
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        return factory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
            .documentElement
    }

    private fun Element.firstText(tagName: String): String {
        val nodes = getElementsByTagName(tagName)
        if (nodes.length == 0) return ""
        return nodes.item(0).textContent.trim()
    }
}
