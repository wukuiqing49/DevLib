package com.wkq.naslib

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NasClientTest {
    @Test fun publicClientKeepsUnicodeFileIdentityAcrossBrowseReadMoveAndDelete() {
        MockWebServer().use { server ->
            server.start()
            val nfd = "cafe\u0301.txt"
            val nfc = "caf\u00e9.txt"
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/root/cafe%CC%81.txt</d:href><d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                  <d:response><d:href>/root/caf%C3%A9.txt</d:href><d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                </d:multistatus>
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("NFD"))
            server.enqueue(MockResponse().setBody("NFC"))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(204))
            NasClient.open(NasConnection(NasProtocol.WEBDAV, server.url("/root/").toString())).use { storage ->
                assertEquals(setOf(nfd, nfc), storage.list("").map { it.path }.toSet())
                assertEquals("NFD", storage.read(nfd).bufferedReader().use { it.readText() })
                assertEquals("NFC", storage.read(nfc).bufferedReader().use { it.readText() })
                storage.moveNew(nfd, "renamed.txt")
                storage.delete(nfc)
            }
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("/root/cafe%CC%81.txt", server.takeRequest().path)
            assertEquals("/root/caf%C3%A9.txt", server.takeRequest().path)
            val move = server.takeRequest()
            assertEquals("/root/cafe%CC%81.txt", move.path)
            assertEquals("F", move.getHeader("Overwrite"))
            assertEquals(server.url("/root/renamed.txt").toString(), move.getHeader("Destination"))
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/root/caf%C3%A9.txt", delete.path)
        }
    }

    @Test fun publicClientMapsAuthenticationAndSpaceErrorsWithoutProtocolTypes() {
        MockWebServer().use { server ->
            server.start()
            NasClient.open(NasConnection(NasProtocol.WEBDAV, server.url("/root/").toString())).use { storage ->
                for ((status, expected) in listOf(401 to NasError.AUTHENTICATION, 507 to NasError.SPACE)) {
                    server.enqueue(MockResponse().setResponseCode(status))
                    try { storage.list(""); fail("Expected protocol error") }
                    catch (error: NasException) { assertEquals(expected, error.error); assertFalse(error.retryable) }
                }
            }
        }
    }

    @Test fun invalidConfigurationIsRejectedAndCredentialsStayOutOfDiagnostics() {
        val config = NasConnection(NasProtocol.SMB, "smb://host/share", "private-user", "private-secret")
        assertFalse(config.toString().contains("private-user"))
        assertFalse(config.toString().contains("private-secret"))
        listOf("https://user:secret@host/root", "https://host/root?token=secret", "sftp://host/root").forEach { root ->
            try { NasConnection(NasProtocol.WEBDAV, root); fail("Unexpected accepted endpoint") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun callerControlExceptionsKeepTheirIdentity() {
        MockWebServer().use { server ->
            server.start()
            NasClient.open(NasConnection(NasProtocol.WEBDAV, server.url("/root/").toString())).use { storage ->
                val paused = IllegalStateException("host paused")
                try { storage.moveNew("source", "target") { throw paused }; fail("Control ignored") }
                catch (error: Exception) { assertSame(paused, error) }
                assertEquals(0, server.requestCount)
                val canceled = kotlinx.coroutines.CancellationException("host canceled")
                try { storage.moveNew("source", "target") { throw canceled }; fail("Cancellation ignored") }
                catch (error: Exception) { assertSame(canceled, error) }
            }
        }
    }

    @Test fun structuralPathChecksPreserveNamesAndRejectTraversal() {
        assertNotEquals(NasPaths.normalize("cafe\u0301.txt"), NasPaths.normalize("caf\u00e9.txt"))
        listOf("../file", "/absolute", "a/../b", "a\\b", "a\u0000b").forEach {
            try { NasPaths.normalize(it); fail("Traversal accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
