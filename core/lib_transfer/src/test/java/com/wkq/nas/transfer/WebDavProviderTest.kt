package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.WebDavProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: WebDavProvider
    @Before fun setup() { server = MockWebServer(); server.start(); provider = WebDavProvider(server.url("/root/").toString(), "user", "password") }
    @After fun cleanup() { provider.close(); server.shutdown() }
    @Test fun visitsStreamEntriesWithoutRetainingDuplicateResponsesAndRejectsLateInvalidPaths() {
        fun response(path: String) = "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:getcontentlength>7</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">" +
            response("/root/") + response("/root/a") + response("/root/a") + response("/root/b") + "</d:multistatus>"))
        val paths = mutableListOf<String>(); provider.visit("") { paths += it.path }
        assertEquals(listOf("a", "b"), paths)
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">" +
            response("/root/a") + response("/escape") + "</d:multistatus>"))
        try { provider.visit("") { }; fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun parsesNamespacedMetadataAndEncodedFileNames() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/root/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/root/a%20b.txt</d:href><d:propstat><d:prop><d:getcontentlength>7</d:getcontentlength><d:getlastmodified>Sat, 10 Oct 2026 00:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent()))
        val file = provider.list("").single()
        assertEquals("a b.txt", file.path)
        assertEquals(7L, file.size)
        assertTrue(file.modifiedAt > 0)
        assertEquals("PROPFIND", server.takeRequest().method)
    }
    @Test fun uploadAndCommitCannotOverwriteExistingTargets() {
        server.enqueue(MockResponse().setResponseCode(201))
        provider.write("file.part", "hello".byteInputStream(), 5) {}
        val put = server.takeRequest()
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("hello", put.body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(412))
        try { provider.moveNew("file.part", "file"); fail() }
        catch (error: TransferException) { assertEquals(TransferError.CONFLICT, error.error) }
        assertEquals("F", server.takeRequest().getHeader("Overwrite"))
    }
    @Test fun redirectsDoNotLeakCredentials() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/file"))
        try { provider.read("file"); fail() }
        catch (error: TransferException) { assertEquals(TransferError.UNSUPPORTED, error.error) }
        assertEquals(1, server.requestCount)
    }
    @Test fun unavailableOptionalPropertiesDoNotDiscardValidSize() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/root/file</d:href><d:propstat><d:prop><d:getcontentlength>9</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:getlastmodified/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"""))
        assertEquals(9L, provider.stat("file")!!.size)
    }
    @Test fun authenticationFailuresAreNotRetryable() {
        server.enqueue(MockResponse().setResponseCode(401))
        try { provider.read("file"); fail() }
        catch (error: TransferException) { assertEquals(TransferError.AUTHENTICATION, error.error); assertFalse(error.retryable) }
    }
    @Test fun listingCannotEscapeConfiguredRoot() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/outside/file</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response></d:multistatus>"""))
        try { provider.list(""); fail() } catch (_: IllegalArgumentException) { }
    }
}
