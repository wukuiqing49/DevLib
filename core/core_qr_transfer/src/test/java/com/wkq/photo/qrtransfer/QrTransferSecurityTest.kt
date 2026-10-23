package com.wkq.photo.qrtransfer

import com.wkq.photo.qrtransfer.model.TransferRole
import com.wkq.photo.qrtransfer.security.ConnectionProof
import com.wkq.photo.qrtransfer.security.EndpointCredentialCodec
import com.wkq.photo.qrtransfer.security.SessionSecretGenerator
import com.wkq.photo.qrtransfer.security.base64UrlBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrTransferSecurityTest {
    @Test
    fun endpointCredential_roundTripsAndRejectsTampering() {
        val session = SessionSecretGenerator.create("com.wkq.photo.qrtransfer", now = 100L)
        val nonce = SessionSecretGenerator.nonce()
        val encoded = EndpointCredentialCodec.encode(session, TransferRole.SENDER, nonce)

        val decoded = EndpointCredentialCodec.decodeAndVerify(encoded, session, TransferRole.SENDER)
        assertNotNull(decoded)
        assertArrayEquals(nonce, decoded?.nonce)

        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()
        assertNull(EndpointCredentialCodec.decodeAndVerify(encoded, session, TransferRole.SENDER))
    }

    @Test
    fun connectionProof_isBoundToRoleAndNearbyDigits() {
        val session = SessionSecretGenerator.create("com.wkq.photo.qrtransfer", now = 100L)
        val receiverNonce = session.receiverNonce.base64UrlBytes()
        val senderNonce = SessionSecretGenerator.nonce()
        val proof = ConnectionProof.create(session, TransferRole.SENDER, "1234", receiverNonce, senderNonce)

        assertTrue(ConnectionProof.verify(proof, session, TransferRole.SENDER, "1234", receiverNonce, senderNonce))
        assertFalse(ConnectionProof.verify(proof, session, TransferRole.RECEIVER, "1234", receiverNonce, senderNonce))
        assertFalse(ConnectionProof.verify(proof, session, TransferRole.SENDER, "5678", receiverNonce, senderNonce))
    }
}
