package com.wkq.util.exif

import org.junit.Assert.*
import org.junit.Test

class MetadataPrivacyRulesTest {
    private fun packet(body: String) = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:other="urn:test">$body</rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test fun removesLocationUnderAnyPrefixAndKeepsUnselectedTitle() {
        val xml = packet("""<other:GPSLatitude>12</other:GPSLatitude><other:LocationShown>
            <other:City>Private city</other:City></other:LocationShown><other:title>Keep</other:title>""")
        val output = MetadataPrivacyRules.scrubXmp(listOf(xml), false, true, false)
        assertFalse(output.contains("GPSLatitude"))
        assertFalse(output.contains("Private city"))
        assertTrue(output.contains("Keep"))
        assertEquals(0, MetadataPrivacyRules.countXmp(output, false, true, false))
    }

    @Test fun attributesIdentityAndPhysicalCopiesAreHandled() {
        val first = packet("<other:title>Keep first</other:title>").replace("xmlns:other=\"urn:test\"",
            "xmlns:other=\"urn:test\" other:CreatorTool=\"Private tool\"")
        val second = packet("<other:creator>Private author</other:creator><other:description>Keep second</other:description>")
        val result = MetadataPrivacyRules.scrubXmp(listOf(first, second), false, false, true)
        assertTrue(result.contains("Keep first") && result.contains("Keep second"))
        assertFalse(result.contains("Private"))
    }

    @Test fun rejectsEntitiesButCanDiscardFullySelectedMalformedXmp() {
        val xml = "<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///not-readable'>]>" + packet("&leak;")
        assertThrows(IllegalArgumentException::class.java) {
            MetadataPrivacyRules.scrubXmp(listOf(xml), false, true, false)
        }
        assertEquals("", MetadataPrivacyRules.scrubXmp(listOf(xml), true, true, true))
    }

    @Test fun selectedLegacyIptcIncludesIdentityAndLocationButKeepsHeadline() {
        assertTrue(MetadataPrivacyRules.selectedIptc(80, false, false, true))
        assertTrue(MetadataPrivacyRules.selectedIptc(90, false, true, false))
        assertFalse(MetadataPrivacyRules.selectedIptc(105, false, true, true))
        assertFalse(MetadataPrivacyRules.selectedName("Orientation", true, true))
        assertFalse(MetadataPrivacyRules.selectedName("ColorSpace", true, true))
    }
}
