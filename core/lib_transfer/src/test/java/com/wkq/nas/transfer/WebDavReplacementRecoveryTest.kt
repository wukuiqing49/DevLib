package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.TaskEntity
import com.wkq.nas.transfer.provider.*
import com.wkq.nas.transfer.work.TransferExecutor
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** 经过实际 HTTP Provider 的失败注入；不连接真实 NAS，不把内存服务等同服务器兼容验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavReplacementRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = ConcurrentHashMap<String, ByteArray>()
    private lateinit var server: MockWebServer
    private lateinit var source: File
    @Volatile private var failCommit = false
    @Volatile private var failRestore = false
    @Volatile private var concurrentTarget = false
    @Volatile private var putStatus = 0
    @Volatile private var corruptUpload = false
    @Volatile private var disconnectUpload = false
    private val backup = ".nas-replacement.backup"

    @Before fun setup() {
        source = temporary.newFolder("source")
        File(source, "a").writeText("new-content")
        content["a"] = "old-content".toByteArray()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath.removePrefix("/root/")
                    fun status(code: Int) = MockResponse().setResponseCode(code)
                    return when (request.method) {
                        "PROPFIND" -> {
                            val bytes = content[path] ?: return status(404)
                            status(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/root/$path</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                        }
                        "GET" -> content[path]?.let { status(200).setBody(okio.Buffer().write(it)) } ?: status(404)
                        "PUT" -> {
                            if (putStatus != 0) return status(putStatus)
                            if (disconnectUpload) return status(200).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            if (request.getHeader("If-None-Match") != "*" || content.containsKey(path)) return status(412)
                            val body = request.body.readByteArray()
                            if (corruptUpload && body.isNotEmpty()) body[0] = (body[0].toInt() xor 1).toByte()
                            content[path] = body; status(201)
                        }
                        "MOVE" -> {
                            val destination = URI(request.getHeader("Destination")!!).path.removePrefix("/root/")
                            if (request.getHeader("Overwrite") != "F" || content.containsKey(destination)) return status(412)
                            if (path.endsWith(".part") && failCommit) {
                                if (concurrentTarget) content[destination] = "concurrent-file".toByteArray()
                                return status(503)
                            }
                            if (path == backup && failRestore) return status(503)
                            val bytes = content.remove(path) ?: return status(404)
                            content[destination] = bytes; status(201)
                        }
                        "DELETE" -> { content.remove(path); status(204) }
                        else -> status(405)
                    }
                }
            }
            start()
        }
    }
    @After fun cleanup() { server.shutdown() }
    private fun execute(): TaskState {
        val file = File(source, "a")
        val task = TaskEntity("replacement", "batch", "COPY", "source", "a", "target", "a", "a", "REPLACE",
            file.length(), file.lastModified(), false, targetExpectedSize = 11, targetExpectedModified = 0)
        return WebDavProvider(server.url("/root/").toString(), "", "").use { target ->
            TransferExecutor().execute(task, LocalProvider(source.path), target, {}, {}, {}, {})
        }
    }
    private fun expectFailure() {
        try { execute(); fail("Injected commit must fail") }
        catch (error: TransferException) { assertEquals(TransferError.NETWORK, error.error); assertTrue(error.retryable) }
    }
    @Test fun verifiedReplacementDeletesBackupOnlyAfterSuccess() {
        assertEquals(TaskState.SUCCEEDED, execute())
        assertEquals("new-content", String(content.getValue("a")))
        assertEquals(setOf("a"), content.keys)
    }
    @Test fun failedHttpCommitRestoresOriginalContent() {
        failCommit = true; expectFailure()
        assertEquals("old-content", String(content.getValue("a")))
        assertFalse(content.containsKey(backup))
        assertTrue(content.containsKey(".nas-replacement.part"))
    }
    @Test fun failedHttpRollbackRetainsRecoverableOriginalBackup() {
        failCommit = true; failRestore = true; expectFailure()
        assertFalse(content.containsKey("a"))
        assertEquals("old-content", String(content.getValue(backup)))
    }
    @Test fun concurrentTargetIsNeverOverwrittenByRollback() {
        failCommit = true; concurrentTarget = true; expectFailure()
        assertEquals("concurrent-file", String(content.getValue("a")))
        assertEquals("old-content", String(content.getValue(backup)))
    }
    private fun expectProtectedFailure(expected: TransferError) {
        try { execute(); fail("Injected failure must not succeed") }
        catch (error: TransferException) { assertEquals(expected, error.error) }
        assertEquals("old-content", String(content.getValue("a")))
        assertFalse(content.containsKey(backup))
    }
    @Test fun authenticationFailureDoesNotTouchOriginalTarget() {
        putStatus = 401; expectProtectedFailure(TransferError.AUTHENTICATION)
        assertEquals(setOf("a"), content.keys)
    }
    @Test fun permissionFailureDoesNotTouchOriginalTarget() {
        putStatus = 403; expectProtectedFailure(TransferError.PERMISSION)
        assertEquals(setOf("a"), content.keys)
    }
    @Test fun fullRemoteStorageDoesNotTouchOriginalTarget() {
        putStatus = 507; expectProtectedFailure(TransferError.SPACE)
        assertEquals(setOf("a"), content.keys)
    }
    @Test fun corruptedUploadIsRejectedBeforeOriginalIsMoved() {
        corruptUpload = true; expectProtectedFailure(TransferError.VERIFICATION)
        assertTrue(content.containsKey(".nas-replacement.part"))
    }
    @Test fun disconnectedUploadDoesNotTouchOriginalTarget() {
        disconnectUpload = true; expectProtectedFailure(TransferError.NETWORK)
        assertEquals(setOf("a"), content.keys)
    }
    @Test fun retryAfterFailedCommitFinishesWithExactContentAndNoOrphanBackup() {
        failCommit = true; expectFailure()
        failCommit = false
        assertEquals(TaskState.SUCCEEDED, execute())
        assertEquals("new-content", String(content.getValue("a")))
        assertEquals(setOf("a"), content.keys)
    }
}
