package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.WebDavProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

class WebDavCertificatesTest {
    private val store = KeyStore.getInstance("PKCS12").apply {
        WebDavCertificatesTest::class.java.getResourceAsStream("/tls/nas-test.p12").use { load(it, "test-only".toCharArray()) }
    }
    private val cert = store.getCertificate("test") as X509Certificate
    private fun server(keysStore: KeyStore = store): MockWebServer {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keysStore, "test-only".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        return MockWebServer().apply { useHttps(ssl.socketFactory, false); start() }
    }
    @Test fun defaultRejectsPrivateCertificateBeforeCredentialsAreSent() {
        server().use { s ->
            WebDavProvider(s.url("/root").toString(), "test-user", "test-secret").use { p ->
                try { p.read("file"); fail("Must reject unknown certificate") } catch (_: SSLException) { }
            }
            assertEquals(0, s.requestCount)
        }
    }
    @Test fun confirmedCertificateAllowsIpMismatchButKeepsAuthentication() {
        server().use { s ->
            s.enqueue(MockResponse().setBody("test-content"))
            WebDavProvider(s.url("/root").toString(), "test-user", "test-secret", WebDavCertificates.fingerprint(cert)).use { p ->
                assertEquals("test-content", p.read("file").bufferedReader().use { it.readText() })
            }
            assertNotNull(s.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun differentCertificateIsBlockedBeforeCredentialsAreSent() {
        server().use { s ->
            WebDavProvider(s.url("/root").toString(), "test-user", "test-secret", "cert-sha256:" + "0".repeat(64)).use { p ->
                try { p.read("file"); fail("Must reject different certificate") } catch (_: SSLException) { }
            }
            assertEquals(0, s.requestCount)
        }
    }
    @Test fun inspectionOnlyObservesCertificateAndSendsNoHttp() = runBlocking {
        server().use { s ->
            assertEquals(WebDavCertificates.fingerprint(cert), WebDavCertificates.inspect(s.url("/root").toString()))
            assertEquals(0, s.requestCount)
        }
    }
    @Test fun expiredPinnedCertificateIsStillRejected() {
        val expired = KeyStore.getInstance("PKCS12").apply {
            WebDavCertificatesTest::class.java.getResourceAsStream("/tls/expired-test.p12").use { load(it, "test-only".toCharArray()) }
        }
        server(expired).use { s ->
            val pin = WebDavCertificates.fingerprint(expired.getCertificate("test") as X509Certificate)
            WebDavProvider(s.url("/root").toString(), "test-user", "test-secret", pin).use { p ->
                try { p.read("file"); fail("Expired certificate accepted") } catch (_: SSLException) { }
            }
            assertEquals(0, s.requestCount)
        }
    }
    @Test fun malformedPinsAreRejected() {
        listOf("trust-all", "cert-sha256:", "sha256:" + "0".repeat(64)).forEach {
            try { WebDavCertificates.validate(it); fail("Malformed pin accepted") } catch (_: IllegalArgumentException) { }
        }
        WebDavCertificates.validate("")
    }
    @Test fun pinsCannotEnablePlainHttp() {
        try {
            WebDavProvider("http://localhost/root", "", "", WebDavCertificates.fingerprint(cert))
            fail("Pinned certificate must require HTTPS")
        } catch (_: IllegalArgumentException) { }
    }
}
