package com.wkq.photo.qrtransfer.security

import android.util.Base64
import com.wkq.photo.qrtransfer.model.QrTransferSession
import com.wkq.photo.qrtransfer.model.TransferRole
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SessionSecretGenerator {
    private val random = SecureRandom()

    fun create(serviceId: String, now: Long = System.currentTimeMillis()): QrTransferSession {
        val sessionBytes = ByteArray(16).also(random::nextBytes)
        val secret = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(16).also(random::nextBytes)
        return QrTransferSession(
            serviceId = serviceId,
            sessionId = sessionBytes.base64Url(),
            secret = secret.base64Url(),
            expiresAt = now + 60_000L,
            receiverNonce = nonce.base64Url(),
        )
    }

    fun nonce(): ByteArray = ByteArray(16).also(random::nextBytes)
}

object HmacVerifier {
    fun sign(secret: ByteArray, vararg fields: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        fields.forEach { field ->
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
            mac.update(field)
        }
        return mac.doFinal()
    }

    fun verify(expected: ByteArray, actual: ByteArray): Boolean = MessageDigest.isEqual(expected, actual)
}

data class EndpointCredential(
    val role: TransferRole,
    val sessionId: ByteArray,
    val nonce: ByteArray,
)

/** 固定 66 字节，避免 Nearby endpointInfo 的容量限制。 */
object EndpointCredentialCodec {
    private const val VERSION: Byte = 1
    private const val SESSION_SIZE = 16
    private const val NONCE_SIZE = 16
    private const val MAC_SIZE = 32

    fun encode(session: QrTransferSession, role: TransferRole, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE)
        val sessionId = session.sessionId.base64UrlBytes()
        require(sessionId.size == SESSION_SIZE)
        val header = ByteBuffer.allocate(2 + SESSION_SIZE + NONCE_SIZE)
            .put(VERSION)
            .put(role.ordinal.toByte())
            .put(sessionId)
            .put(nonce)
            .array()
        val proof = HmacVerifier.sign(session.secret.base64UrlBytes(), "endpoint".utf8(), header)
        return header + proof
    }

    fun decodeAndVerify(data: ByteArray, session: QrTransferSession, expectedRole: TransferRole): EndpointCredential? {
        if (data.size != 2 + SESSION_SIZE + NONCE_SIZE + MAC_SIZE) return null
        val header = data.copyOfRange(0, data.size - MAC_SIZE)
        val proof = data.copyOfRange(data.size - MAC_SIZE, data.size)
        val buffer = ByteBuffer.wrap(header)
        if (buffer.get() != VERSION || buffer.get().toInt() != expectedRole.ordinal) return null
        val sessionId = ByteArray(SESSION_SIZE).also(buffer::get)
        val nonce = ByteArray(NONCE_SIZE).also(buffer::get)
        if (!MessageDigest.isEqual(sessionId, session.sessionId.base64UrlBytes())) return null
        val expected = HmacVerifier.sign(session.secret.base64UrlBytes(), "endpoint".utf8(), header)
        return if (HmacVerifier.verify(expected, proof)) EndpointCredential(expectedRole, sessionId, nonce) else null
    }
}

object ConnectionProof {
    fun create(
        session: QrTransferSession,
        role: TransferRole,
        authenticationDigits: String,
        receiverNonce: ByteArray,
        senderNonce: ByteArray,
    ): String = HmacVerifier.sign(
        session.secret.base64UrlBytes(),
        "connection".utf8(),
        session.sessionId.utf8(),
        role.name.utf8(),
        authenticationDigits.utf8(),
        receiverNonce,
        senderNonce,
    ).base64Url()

    fun verify(
        proof: String,
        session: QrTransferSession,
        role: TransferRole,
        authenticationDigits: String,
        receiverNonce: ByteArray,
        senderNonce: ByteArray,
    ): Boolean = runCatching {
        MessageDigest.isEqual(
            create(session, role, authenticationDigits, receiverNonce, senderNonce).base64UrlBytes(),
            proof.base64UrlBytes(),
        )
    }.getOrDefault(false)
}

fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
fun String.base64UrlBytes(): ByteArray = Base64.decode(this, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
