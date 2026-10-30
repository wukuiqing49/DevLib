package com.wkq.iptc.feature.press.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class XmpMetadataReaderTest {

    @Test
    fun readsXDefaultFromMultilingualAlternativeValues() {
        val fields = XmpMetadataReader.parseIptcXmpFields(
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/">
                  <dc:title><rdf:Alt>
                    <rdf:li xml:lang="fr">Titre francais</rdf:li>
                    <rdf:li xml:lang="x-default">Default title</rdf:li>
                  </rdf:Alt></dc:title>
                  <dc:description><rdf:Alt>
                    <rdf:li xml:lang="zh-CN">中文说明</rdf:li>
                    <rdf:li xml:lang="x-default">Default caption</rdf:li>
                  </rdf:Alt></dc:description>
                  <dc:rights><rdf:Alt>
                    <rdf:li xml:lang="x-default">Copyright 2026</rdf:li>
                    <rdf:li xml:lang="de">Urheberrecht 2026</rdf:li>
                  </rdf:Alt></dc:rights>
                  <xmpRights:UsageTerms><rdf:Alt>
                    <rdf:li xml:lang="es">Uso editorial</rdf:li>
                    <rdf:li xml:lang="x-default">Editorial use only</rdf:li>
                  </rdf:Alt></xmpRights:UsageTerms>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        )

        assertEquals("Default title", fields.title)
        assertEquals("Default caption", fields.caption)
        assertEquals("Copyright 2026", fields.copyright)
        assertEquals("Editorial use only", fields.rightsUsageTerms)
    }

    @Test
    fun fallsBackToFirstAlternativeValueWhenDefaultIsAbsent() {
        val fields = XmpMetadataReader.parseIptcXmpFields(
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title><rdf:Alt>
                    <rdf:li xml:lang="ja">最初のタイトル</rdf:li>
                    <rdf:li xml:lang="en">Second title</rdf:li>
                  </rdf:Alt></dc:title>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        )

        assertEquals("最初のタイトル", fields.title)
    }
}
