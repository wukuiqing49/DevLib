package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class FilePreviewFormatsTest {
    @Test fun typeRecognitionIsCaseInsensitiveAndUnknownFilesRemainExternal() {
        assertEquals(FilePreviewType.PDF, FilePreviewFormats.type("folder/report.PDF"))
        assertEquals(FilePreviewType.VIDEO, FilePreviewFormats.type("clip.MP4"))
        assertEquals(FilePreviewType.IMAGE, FilePreviewFormats.type("image.HEIC"))
        assertEquals(FilePreviewType.TEXT, FilePreviewFormats.type("notes.yaml"))
        assertEquals(FilePreviewType.OTHER, FilePreviewFormats.type("archive.zip"))
    }
    @Test fun utfBomAndExplicitLegacyEncodingDecodeWithoutGuessing() {
        assertEquals("text", FilePreviewFormats.decodeText(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "text".toByteArray()))
        assertEquals("文本", FilePreviewFormats.decodeText(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "文本".toByteArray(Charsets.UTF_16LE)))
        assertEquals("文本", FilePreviewFormats.decodeText(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "文本".toByteArray(Charsets.UTF_16BE)))
        assertEquals("café", FilePreviewFormats.decodeText("café".toByteArray(java.nio.charset.Charset.forName("windows-1252")), "windows-1252"))
    }
    @Test(expected = java.nio.charset.CharacterCodingException::class) fun malformedUtf8IsNotSilentlyReplaced() { FilePreviewFormats.decodeText(byteArrayOf(0xff.toByte())) }
    @Test(expected = IllegalArgumentException::class) fun textPreviewRemainsBounded() { FilePreviewFormats.decodeText(ByteArray(1024 * 1024 + 1)) }
}
