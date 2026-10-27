package com.wkq.dev.demo.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** 验证隔离文件的归属、失败清理、有界读取与取消，不依赖真实 NAS 或 USB。 */
class StorageLabTest {
    /** 成功校验后删除且只删除本次新文件，既有文件保持不变。 */
    @Test fun roundTripKeepsExistingFiles() {
        val files = mutableMapOf("existing" to byteArrayOf(42))
        verifyStorageRoundTrip("probe", { name, bytes -> check(!files.containsKey(name)); files[name] = bytes },
            { files.getValue(it).inputStream() }, { files.remove(it) }, {})
        assertEquals(setOf("existing"), files.keys)
        assertArrayEquals(byteArrayOf(42), files.getValue("existing"))
    }
    /** 同名独占写入失败时不能删除已有目标。 */
    @Test fun rejectedWriteDoesNotDeleteExistingTarget() {
        var deleted = false
        try {
            verifyStorageRoundTrip("existing", { _, _ -> throw IOException("exists") },
                { ByteArrayInputStream(byteArrayOf()) }, { deleted = true }, {})
            fail("Expected conflict")
        } catch (_: IOException) { assertFalse(deleted) }
    }
    /** 读回内容不一致时清理已归属的新文件并报告失败。 */
    @Test fun corruptReadBackCleansOwnedFile() {
        var deleted = false
        try {
            verifyStorageRoundTrip("probe", { _, _ -> }, { byteArrayOf(1).inputStream() }, { deleted = true }, {})
            fail("Expected mismatch")
        } catch (_: IllegalArgumentException) { assertTrue(deleted) }
    }
    /** 写入后取消仍清理本次文件，同时保留原取消类型。 */
    @Test fun cancellationCleansOwnedFile() {
        var deleted = false
        try {
            verifyStorageRoundTrip("probe", { _, _ -> }, { byteArrayOf().inputStream() }, { deleted = true },
                { throw CancellationException("cancel") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertTrue(deleted) }
    }
    /** 清理失败不得被误报为验证成功。 */
    @Test fun cleanupFailureIsReported() {
        var payload = byteArrayOf()
        try {
            verifyStorageRoundTrip("probe", { _, bytes -> payload = bytes }, { payload.inputStream() },
                { throw IOException("cleanup failed") }, {})
            fail("Expected cleanup error")
        } catch (error: IOException) { assertEquals("cleanup failed", error.message) }
    }
    /** 输入超过校验上限时拒绝继续分配内存。 */
    @Test fun boundedReadRejectsOversizedData() {
        try { ByteArray(4097).inputStream().readBytesBounded(4096) {}; fail("Expected bound") }
        catch (_: IllegalArgumentException) { }
    }
    /** 下载超过页面限制时删除本次本地产物。 */
    @Test fun oversizedDownloadRemovesPartialOutput() {
        val output = File.createTempFile("devlib-test-", ".bin")
        try {
            try { downloadStorageFile(ByteArray(8 * 1024 * 1024 + 1).inputStream(), output) {}; fail("Expected bound") }
            catch (_: IllegalArgumentException) { assertFalse(output.exists()) }
        } finally { output.delete() }
    }
}
