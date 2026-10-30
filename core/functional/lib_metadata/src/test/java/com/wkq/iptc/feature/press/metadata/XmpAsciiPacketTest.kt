package com.wkq.iptc.feature.press.metadata

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XmpAsciiPacketTest {
    @Test fun unicodeContentAndUnknownAttributesSurviveAsciiSerialization() {
        val value = "\u6444\u5f71\u4f5c\u8005\uD834\uDD1E"
        val input = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description xmlns:client='https://example.test/' xmlns:dc='http://purl.org/dc/elements/1.1/' client:note='$value'><dc:rights>Copyright $value</dc:rights></rdf:Description></rdf:RDF></x:xmpmeta>"
        val output = XmpWriter.asciiPacket(input)
        assertTrue(output.all { it.code < 128 })
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(output.toByteArray(Charsets.US_ASCII)))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
        assertEquals(value, description.getAttributeNS("https://example.test/", "note"))
        assertEquals("Copyright $value", document.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "rights").item(0).textContent)
    }

    @Test fun malformedPacketCannotBeSerializedAsSuccess() {
        assertThrows(Exception::class.java) { XmpWriter.asciiPacket("<broken>") }
    }
}
