package com.wkq.naslib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** 连接级证书信任；沿用协议专属指纹存储列，不改变 Room 格式。 */
object WebDavCertificates {
    private const val PREFIX = "cert-sha256:"
    fun validate(pin: String) { require(pin.isEmpty() || pin.matches(Regex("cert-sha256:[0-9a-f]{64}"))) }
    fun fingerprint(cert: X509Certificate): String = PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** 仅观察证书，在握手阶段主动中止；绝不发送 HTTP 请求或凭据。结果尚未经信任。 */
    suspend fun inspect(root: String): String = withContext(Dispatchers.IO) {
        val uri = URI(root)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        var observed: X509Certificate? = null
        val observer = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                observed = chain.firstOrNull()
                throw CertificateException("Certificate inspection only")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), null) }
        Socket().use { raw ->
            raw.connect(InetSocketAddress(uri.host, if (uri.port == -1) 443 else uri.port), 15000)
            (tls.socketFactory.createSocket(raw, uri.host, if (uri.port == -1) 443 else uri.port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 15000
                try { socket.startHandshake() } catch (error: javax.net.ssl.SSLException) {
                    if (observed == null) throw error
                }
            }
        }
        val cert = observed ?: throw CertificateException("No server certificate")
        cert.checkValidity()
        fingerprint(cert)
    }

    internal fun configure(builder: OkHttpClient.Builder, root: String, pin: String): OkHttpClient.Builder {
        validate(pin)
        if (pin.isEmpty()) return builder
        val host = URI(root).also { require(it.scheme == "https") }.host
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
                cert.checkValidity()
                if (fingerprint(cert) != pin) throw CertificateException("Trusted NAS certificate changed")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return builder.sslSocketFactory(tls.socketFactory, manager).hostnameVerifier { requested: String, session: SSLSession ->
            // 证书身份由用户确认的完整证书指纹验证，且仅适用于当前连接主机。
            requested == host && runCatching { fingerprint(session.peerCertificates.first() as X509Certificate) == pin }.getOrDefault(false)
        }
    }
}
