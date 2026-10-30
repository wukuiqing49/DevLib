package com.wkq.usb



import android.content.ContentProvider

import android.content.ContentValues

import android.content.pm.ProviderInfo

import android.database.MatrixCursor

import android.net.Uri

import android.os.Bundle

import android.os.ParcelFileDescriptor

import android.provider.DocumentsContract

import org.junit.Assert.*

import org.junit.Before

import org.junit.After

import org.junit.Test

import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner

import org.robolectric.RuntimeEnvironment

import org.robolectric.annotation.Config

import org.robolectric.shadows.ShadowContentResolver

import java.io.File



@RunWith(RobolectricTestRunner::class)

@Config(sdk = [28])

class UsbStorageTest {

    /** 写入回调取消时只删除本次创建目标，原取消异常保持。 */
    @Test fun cancelledWriteRemovesCreatedDocument() {
        val cancellation = java.util.concurrent.CancellationException("cancel")
        val actual = runCatching { provider.write("output", byteArrayOf(1).inputStream()) { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, actual)
        assertFalse(documents.outputCreated)
        assertEquals(1, documents.deletions)
    }
    /** 源流失败时清理新目标，不把部分写入标记为成功。 */
    @Test fun sourceFailureRemovesCreatedDocument() {
        val failure = java.io.IOException("source failed")
        val source = object : java.io.InputStream() {
            /** 模拟源文件在读取时失效。 */
            override fun read(): Int = throw failure
        }
        assertSame(failure, runCatching { provider.write("output", source) }.exceptionOrNull())
        assertFalse(documents.outputCreated)
    }


    private lateinit var documents: TestDocuments

    private lateinit var provider: UsbStorage

    @Before fun setup() {

        documents = TestDocuments()

        documents.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {

            authority = "nas.test.documents"

            exported = true

            grantUriPermissions = true

        })

        ShadowContentResolver.registerProviderInternal("nas.test.documents", documents)

        provider = UsbStorage(RuntimeEnvironment.getApplication(), "content://nas.test.documents/tree/root")

    }

    @After fun cleanup() { documents.output.delete() }

    @Test fun directoryMetadataMatchesEnumeration() {

        val metadata = provider.stat("child")!!

        assertEquals(8192L, metadata.size)

        assertEquals(metadata, provider.list("").single())

        val visited = mutableListOf<UsbEntry>()

        provider.visit("") { visited += it }

        assertEquals(listOf(metadata), visited)

    }

    @Test fun streamedWriteReadAndFileDelete() {

        val bytes = ByteArray(65536) { (it % 251).toByte() }

        var progress = -1L

        provider.write("output", bytes.inputStream()) { progress = it }

        assertEquals(bytes.size.toLong(), progress)

        assertArrayEquals(bytes, provider.read("output").use { it.readBytes() })

        assertFalse(provider.stat("output")!!.directory)

        provider.delete("output")

        assertNull(provider.stat("output"))

    }

    @Test fun traversalCannotEscapeGrantedTree() {

        for (path in listOf("../child", "/child", "child/../other", "child\\other")) {

            try { provider.stat(path); fail("Invalid path accepted") }

            catch (_: IllegalArgumentException) { }

        }

        assertEquals(0, documents.deletions)

    }

    @Test fun existingPathsPreserveUnicodeIdentity() {

        val nfd = "café.pdf"

        val nfc = "café.pdf"

        assertNotEquals(UsbPaths.normalize(nfc), UsbPaths.normalize(nfd))
        assertEquals(nfd, UsbPaths.normalize(nfd))

        assertTrue(UsbPaths.isCrossPlatformSafe("safe-file.txt"))

        assertFalse(UsbPaths.isCrossPlatformSafe("illegal:char?.txt"))

        assertEquals("illegal_char_.txt", UsbPaths.sanitizeCrossPlatformName("illegal:char?.txt"))

    }

    @Test fun emptyChildCanBeDeleted() {

        provider.deleteEmptyDirectory("child")

        assertEquals(1, documents.deletions)

    }

    @Test fun authorizedRootCannotBeDeleted() {

        try { provider.deleteEmptyDirectory(""); fail("Root must be protected") }

        catch (_: IllegalArgumentException) { }

        assertEquals(0, documents.deletions)

    }

    @Test fun unpreviewedChildPreventsRecursiveDeletion() {

        documents.nonEmpty = true

        rejects(UsbError.SOURCE_CHANGED)

    }

    @Test fun readOnlyDirectoryCannotBeDeleted() {

        documents.deleteSupported = false

        rejects(UsbError.PERMISSION)

    }

    @Test fun failedEnumerationCannotBeTreatedAsEmpty() {

        documents.failedEnumeration = true

        rejects(UsbError.STORAGE_UNAVAILABLE)

    }

    @Test fun fileCannotBeDeletedThroughDirectoryApi() {

        documents.directory = false

        rejects(UsbError.CONFLICT)

    }

    private fun rejects(expected: UsbError) {

        try { provider.deleteEmptyDirectory("child"); fail("Deletion must be rejected") }

        catch (e: UsbException) { assertEquals(expected, e.error) }

        assertEquals(0, documents.deletions)

    }

    class TestDocuments : ContentProvider() {

        var nonEmpty = false

        var deleteSupported = true

        var failedEnumeration = false

        var directory = true

        var deletions = 0

        var outputCreated = false

        val output: File = File.createTempFile("usb-storage-test-", ".bin")

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor? {

            val columns = projection ?: error("Projection required")

            val cursor = MatrixCursor(columns)

            val childListing = uri.lastPathSegment == "children"

            val id = if (childListing) uri.pathSegments[uri.pathSegments.size - 2] else uri.lastPathSegment

            if (childListing && id == "child" && failedEnumeration) return null

            if (childListing && id == "child" && !nonEmpty) return cursor

            fun row(name: String, isDirectory: Boolean) = columns.map<String, Any?> { column -> when (column) {

                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> name

                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name

                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"

                DocumentsContract.Document.COLUMN_FLAGS -> if (deleteSupported) DocumentsContract.Document.FLAG_SUPPORTS_DELETE else 0

                DocumentsContract.Document.COLUMN_SIZE -> if (isDirectory) 8192L else 0L

                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L

                else -> null

            } }.toTypedArray()

            cursor.addRow(row(if (id == "root") "child" else "new-file", directory || id == "root" && !childListing))

            if (childListing && id == "root" && outputCreated) cursor.addRow(row("output", false))

            return cursor

        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =

            ParcelFileDescriptor.open(output, ParcelFileDescriptor.parseMode(mode))

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {

            if (method == "android:createDocument") {

                outputCreated = true

                return Bundle().apply { putParcelable("uri", Uri.parse("content://nas.test.documents/tree/root/document/output")) }

            }

            check(method == "android:deleteDocument")

            outputCreated = false

            deletions++

            return Bundle()

        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    }

}
