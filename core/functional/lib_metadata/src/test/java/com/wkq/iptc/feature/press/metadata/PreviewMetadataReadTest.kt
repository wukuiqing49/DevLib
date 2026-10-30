package com.wkq.iptc.feature.press.metadata

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PreviewMetadataReadTest {
    @get:Rule val folder = TemporaryFolder()
    private fun photo() = folder.newFile("source.jpg").apply {
        writeBytes(requireNotNull(this@PreviewMetadataReadTest.javaClass.classLoader?.getResourceAsStream("test.jpg")).use { it.readBytes() })
    }
    private fun insertSegment(file: File, marker: Int, payload: ByteArray) {
        val bytes = file.readBytes()
        val size = payload.size + 2
        file.writeBytes(bytes.copyOfRange(0, 2) + byteArrayOf(-1, marker.toByte(), (size shr 8).toByte(), size.toByte()) + payload + bytes.copyOfRange(2, bytes.size))
    }

    @Test fun validJpegWithoutMetadataIsKnownEmpty() {
        val template = MetadataTemplateImporter.readJpegForPreview(photo()).getOrThrow()
        assertEquals("", template.headline)
        assertEquals("", template.caption)
    }

    @Test fun malformedPhotoshopIptcFailsStrictReadButKeepsLegacyImportFallback() {
        val source = photo()
        insertSegment(source, 0xed, "Photoshop 3.0\u00008BIM".toByteArray(Charsets.US_ASCII))
        assertTrue(MetadataTemplateImporter.readJpegForPreview(source).isFailure)
        assertTrue(MetadataTemplateImporter.importFromPhoto(source, "").isSuccess)
    }

    @Test fun malformedXmpCannotBecomeAnEmptyOriginal() {
        val source = photo()
        insertSegment(source, 0xe1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>".toByteArray(Charsets.UTF_8))
        assertTrue(MetadataTemplateImporter.readJpegForPreview(source).isFailure)
    }

    @Test fun truncatedJpegDoesNotBecomeKnownEmpty() {
        val source = photo()
        source.writeBytes(byteArrayOf(-1, -40, -1, -19, 0, 30, 1))
        assertTrue(MetadataTemplateImporter.readJpegForPreview(source).isFailure)
    }

    @Test fun validWrittenMetadataMatchesLegacyImport() {
        val source = photo()
        IptcWriter.writeTemplateMetadata(source, com.wkq.dev.metadata.model.MetadataTemplate(
            "fixture", headline = "Original headline", caption = "Original caption", creator = "Author"
        )).getOrThrow()
        val strict = MetadataTemplateImporter.readJpegForPreview(source).getOrThrow()
        val legacy = MetadataTemplateImporter.importFromPhoto(source, "").getOrThrow().template
        assertEquals(legacy, strict)
        assertEquals("Original headline", strict.standardFields[com.wkq.dev.metadata.model.MetadataFieldDefinitions.IIM_HEADLINE])
        assertEquals("Original caption", strict.standardFields[com.wkq.dev.metadata.model.MetadataFieldDefinitions.IIM_CAPTION])
    }

    @Test fun historicalExifTag700IsStillRead() {
        val source = photo()
        val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"><photoshop:Headline>Legacy headline</photoshop:Headline></rdf:Description></rdf:RDF></x:xmpmeta>"""
        androidx.exifinterface.media.ExifInterface(source).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_XMP, packet)
            saveAttributes()
        }
        val template = MetadataTemplateImporter.readJpegForPreview(source).getOrThrow()
        assertEquals("Legacy headline", template.standardFields[com.wkq.dev.metadata.model.MetadataFieldDefinitions.PHOTOSHOP_HEADLINE])
    }
}
