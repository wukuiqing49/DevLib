package com.wkq.util.exif

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 验证原生图片元数据变更不会破坏受保护的数据块。 */
class RasterMetadataChunksTest {
    /** 从工程根目录定位分组后模块中的原始格式样本。 */
    private fun source(name: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
        return File(root, "core/functional/lib_metadata/src/androidTest/assets/format-matrix/$name")
    }
    private fun fixture(name: String, block: (File) -> Unit) {
        val dir = Files.createTempDirectory("raster-chunks-test").toFile()
        try { block(source(name).copyTo(File(dir, name))) } finally { dir.deleteRecursively() }
    }

    @Test fun pngNativeXmpRemovalPreservesProtectedChunks() =
        fixture("png_with_xmp_in_both_exif_and_itxt.png") { file ->
            val digest = RasterMetadataChunks.protectedDigest(file)
            assertTrue(RasterMetadataChunks.countTextOrXmp(file, false) > 0)
            RasterMetadataChunks.removeXmp(file, true)
            assertEquals(0, RasterMetadataChunks.countTextOrXmp(file, true))
            assertEquals(digest, RasterMetadataChunks.protectedDigest(file))
        }

    @Test fun webpAnimationAndFramesRemainUnchanged() =
        fixture("webp_with_anim_without_exif.webp") { file ->
            val digest = RasterMetadataChunks.protectedDigest(file)
            RasterMetadataChunks.removeXmp(file, true)
            assertEquals(digest, RasterMetadataChunks.protectedDigest(file))
        }

    @Test fun pngAlphaAndColourRemainUnchanged() = fixture("png_alpha.png") { file ->
        val digest = RasterMetadataChunks.protectedDigest(file)
        RasterMetadataChunks.removeXmp(file)
        assertEquals(digest, RasterMetadataChunks.protectedDigest(file))
    }

    @Test fun writesLargeNativePngXmpWithoutJpegSegmentLimit() = fixture("png_alpha.png") { file ->
        val before = RasterMetadataChunks.protectedDigest(file)
        val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:description="${"a".repeat(70_000)}"/></rdf:RDF></x:xmpmeta>"""
        RasterMetadataChunks.writeNativeXmp(file, packet)
        assertEquals(1, RasterMetadataChunks.countTextOrXmp(file, false))
        assertEquals(packet, RasterMetadataChunks.readNativeXmp(file))
        assertEquals(before, RasterMetadataChunks.protectedDigest(file))
        val directory = com.drew.imaging.ImageMetadataReader.readMetadata(file)
            .getFirstDirectoryOfType(com.drew.metadata.xmp.XmpDirectory::class.java)
        assertEquals(70_000, directory.xmpMeta.getLocalizedText(
            "http://purl.org/dc/elements/1.1/", "description", null, "x-default").value.length)
    }

    @Test fun writesNativeAnimatedWebpXmpWithoutChangingFrames() = fixture("webp_with_anim_without_exif.webp") { file ->
        val before = RasterMetadataChunks.protectedDigest(file)
        RasterMetadataChunks.writeNativeXmp(file, "<test/>")
        assertEquals(1, RasterMetadataChunks.countTextOrXmp(file, false))
        assertEquals("<test/>", RasterMetadataChunks.readNativeXmp(file))
        assertEquals(before, RasterMetadataChunks.protectedDigest(file))
    }

    @Test fun truncatedWebpIsRejectedWithoutChangingInput() = fixture("webp_lossy.webp") { file ->
        java.io.RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        val before = file.readBytes()
        assertTrue(runCatching { RasterMetadataChunks.removeXmp(file) }.isFailure)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun protectedDigestDetectsPayloadChanges() = fixture("png_alpha.png") { file ->
        val before = RasterMetadataChunks.protectedDigest(file)
        java.io.RandomAccessFile(file, "rw").use { input ->
            input.seek(8 + 8 + 4) // IHDR高度字段，仍是合法结构长度。
            val value = input.readUnsignedByte()
            input.seek(8 + 8 + 4)
            input.writeByte(value xor 1)
        }
        assertTrue(runCatching { RasterMetadataChunks.protectedDigest(file) }.isFailure)
    }
}
