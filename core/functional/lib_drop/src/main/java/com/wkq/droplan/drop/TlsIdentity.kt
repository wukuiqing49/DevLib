package com.wkq.droplan.drop

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** 本地 PKCS12 身份；不联网申请证书，不安装 CA，不关闭接收端的证书验证。 */
object TlsIdentity {
    const val MAX_BYTES = 1024 * 1024

    fun load(input: InputStream, password: CharArray, listeningIp: String): SSLContext {
        var bytes = byteArrayOf()
        try {
            bytes = input.use { it.readBytesBounded() }
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(bytes), password)
            val aliases = store.aliases().toList().filter { store.isKeyEntry(it) }
            require(aliases.size == 1) { "Exactly one private-key identity is required" }
            val alias = aliases.single()
            require(store.getKey(alias, password) is PrivateKey)
            val cert = store.getCertificate(alias) as X509Certificate
            cert.checkValidity()
            require(cert.subjectAlternativeNames?.any { it[0] == 7 && it[1] == listeningIp } == true) {
                "Certificate IP SAN must match the listening address"
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, password)
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        } finally { bytes.fill(0); password.fill('\u0000') }
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1)
        try {
            var count = 0
            while (count < buffer.size) {
                val n = read(buffer, count, buffer.size - count)
                if (n < 0) break
                require(n > 0)
                count += n
            }
            require(count in 1..MAX_BYTES) { "PKCS12 identity exceeds limit or is empty" }
            return buffer.copyOf(count)
        } finally { buffer.fill(0) }
    }
}
