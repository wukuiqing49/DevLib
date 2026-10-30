package com.wkq.droplan.drop

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.ZipFile

class SnapshotZipArchiveTest {
    @Test fun folderPathsKeepHierarchyAndCannotEscapeArchiveRoot() {
        val parent = Files.createTempDirectory("droplan-folders").toFile()
        val original = source("photo.bin", byteArrayOf(4,5,6))
        val nested = object : ShareSource by original { override val archivePath = "Photos/Trip/photo.bin" }
        val hostile = object : ShareSource by source("bad", byteArrayOf(7)) { override val archivePath = "../../bad" }
        try {
            SnapshotZipArchive.prepare(File(parent,"archive"),listOf(nested,hostile),SnapshotZipArchive.Mode.DEFLATE,100000).use {
                ZipFile(File(parent,"archive/bundle.zip")).use { zip ->
                    assertArrayEquals(byteArrayOf(4,5,6),zip.getInputStream(zip.getEntry("Photos/Trip/1-photo.bin")).use { it.readBytes() })
                    zip.entries().asSequence().forEach { entry ->
                        assertFalse(entry.name.startsWith("/")); assertFalse(entry.name.split('/').contains(".."))
                    }
                }
            }
        } finally { parent.delete() }
    }
    private fun source(name: String, data: ByteArray) = object : ShareSource {
        override val id = ShareEngine.newFileId()
        override val displayName = name
        override val mimeType = "application/octet-stream"
        override val length = data.size.toLong()
        override val supportsRandomAccess = false
        override fun openStream() = ByteArrayInputStream(data)
        override fun openRange(offset: Long, length: Long): InputStream = error("sequential")
    }
    @Test fun storeAndDeflateRoundTripSafeNamesStableHashAndRange() {
        val parent = Files.createTempDirectory("droplan-zip-test").toFile()
        try {
            for (mode in SnapshotZipArchive.Mode.entries) {
                val first = byteArrayOf(1,2,3,4); val second = "中文hello".toByteArray()
                val dir = File(parent, mode.name)
                val stages = mutableListOf<String>()
                SnapshotZipArchive.prepare(dir, listOf(source("../../evil.bin", first), source("C:\\folder\\evil.bin", second)), mode, 1024 * 1024, stage = { stages.add(it) }).use { archive ->
                    assertEquals(listOf("zip", "verify", "hash"), stages)
                    assertTrue(archive.supportsRandomAccess); assertEquals(64, archive.contentVersion.length)
                    ZipFile(File(dir, "bundle.zip")).use { zip ->
                        assertArrayEquals(first, zip.getInputStream(zip.getEntry("1-evil.bin")).use { it.readBytes() })
                        assertArrayEquals(second, zip.getInputStream(zip.getEntry("2-evil.bin")).use { it.readBytes() })
                        assertEquals(if (mode == SnapshotZipArchive.Mode.STORE) 0 else 8, zip.getEntry("1-evil.bin").method)
                    }
                    val bytes = archive.openStream().use { it.readBytes() }
                    assertArrayEquals(bytes.copyOfRange(3, 20), archive.openRange(3,17).use { it.readBytes() })
                }
                assertFalse(dir.exists())
            }
        } finally { parent.delete() }
    }
    @Test fun cancellationQuotaAndChangingSourceNeverPublishPartialArchive() {
        val parent = Files.createTempDirectory("droplan-zip-fail").toFile()
        try {
            val cases = listOf("cancel", "quota", "changed")
            for (name in cases) {
                val dir = File(parent, name)
                val original = source("file", ByteArray(256))
                val value = if (name == "changed") object : ShareSource by original { override val length = 100L } else original
                try {
                    SnapshotZipArchive.prepare(dir, listOf(value), SnapshotZipArchive.Mode.STORE, if (name == "quota") 32 else 10000L) { name == "cancel" }
                    fail("Should reject $name")
                } catch (_: IOException) { }
                assertFalse(dir.exists())
            }
        } finally { parent.delete() }
    }
    @Test fun actualZip64EntryOverFourGiBIsGeneratedAndFullyValidatedWithBoundedReads() {
        val parent = Files.createTempDirectory("droplan-zip64").toFile()
        val length = (1L shl 32) + 13
        val value = object : ShareSource by source("large-zero.bin", byteArrayOf()) {
            override val length = length
            override fun openStream() = object : InputStream() {
                var remaining = length
                override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 0 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    assertTrue(len <= 65536)
                    if (remaining == 0L) return -1
                    val n = minOf(remaining, len.toLong()).toInt()
                    java.util.Arrays.fill(b, off, off+n, 0.toByte()); remaining -= n; return n
                }
            }
        }
        try {
            val dir = File(parent, "archive")
            SnapshotZipArchive.prepare(dir, listOf(value), SnapshotZipArchive.Mode.DEFLATE, length + 1024 * 1024).use {
                ZipFile(File(dir,"bundle.zip")).use { zip ->
                    val entry = zip.entries().nextElement()
                    assertEquals(length, entry.size)
                    assertTrue(entry.extra != null) // ZIP64 扩展实际记录 64 位长度。
                }
                assertTrue(it.length < 64L * 1024 * 1024)
            }
        } finally { parent.delete() }
    }
}
