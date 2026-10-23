package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataTemplate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class XmpSidecarStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val template = MetadataTemplate(id = "sidecar", title = "相机原图", caption = "News & <caption>")

    @Test fun createsVerifiedSidecarWithoutChangingOpaqueCameraFile() {
        val source = folder.newFile("DSC_1234.NEF")
        val bytes = byteArrayOf(0, 1, 2, -1, 34)
        source.writeBytes(bytes)
        val output = folder.newFolder("output")
        val first = XmpSidecarStore.create(source, source.name, template, output)
        val second = XmpSidecarStore.create(source, source.name, template, output)
        assertEquals("DSC_1234.xmp", first.name)
        assertNotEquals(first.parent, second.parent)
        assertArrayEquals(bytes, source.readBytes())
        assertTrue(MetadataVerifier.verifySidecar(first, template))
        assertFalse(MetadataVerifier.verifySidecar(first, template.copy(title = "Other")))
    }

    @Test fun retainsUnknownPropertiesAndCaptureDateWhileUpdatingManagedFields() {
        val source = folder.newFile("DSC_1234.CR3")
        val existing = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:custom="urn:camera:settings" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"><custom:Setting>Keep me</custom:Setting><photoshop:DateCreated>2020-01-02T03:04:05</photoshop:DateCreated></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val result = XmpSidecarStore.create(source, source.name, template, folder.newFolder(), existing)
        assertTrue(result.readText().contains("Keep me"))
        assertTrue(result.readText().contains("2020-01-02T03:04:05"))
        assertTrue(MetadataVerifier.verifySidecar(result, template))
    }

    @Test fun invalidExistingPacketFailsAndCleansPartialOutput() {
        val source = folder.newFile("DSC.ARW")
        val output = folder.newFolder()
        assertThrows(Exception::class.java) {
            XmpSidecarStore.create(source, source.name, template, output, "broken XML")
        }
        assertEquals(0, output.listFiles()!!.size)
    }

    @Test fun rejectsPathTraversalBeforeCreatingOutput() {
        val source = folder.newFile("DSC.RAF")
        val output = folder.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            XmpSidecarStore.create(source, "../DSC.RAF", template, output)
        }
        assertEquals(0, output.listFiles()!!.size)
    }
}
