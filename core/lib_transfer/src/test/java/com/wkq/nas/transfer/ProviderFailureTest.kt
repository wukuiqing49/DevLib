package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.classify
import com.wkq.nas.transfer.provider.providerCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderFailureTest {
    @Test fun wrappedTransportAndPermissionErrorsRemainActionable() {
        assertEquals(TransferError.NETWORK to true, classify(IOException("wrapper", ConnectException())))
        assertEquals(TransferError.PERMISSION to false, classify(IOException("wrapper", SecurityException())))
        assertEquals(TransferError.SPACE to false, classify(IOException("wrapper", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))))
    }
    @Test fun explicitBusinessErrorsTakePrecedenceWhileTlsUsesActualCause() {
        val network = android.system.ErrnoException("connect", android.system.OsConstants.ECONNREFUSED)
        assertEquals(TransferError.VERIFICATION to false, classify(TransferException(TransferError.VERIFICATION).apply { initCause(network) }))
        assertEquals(TransferError.NETWORK to true, classify(SSLHandshakeException("connection reset").apply { initCause(network) }))
        assertEquals(TransferError.CERTIFICATE to false, classify(SSLHandshakeException("handshake").apply { initCause(java.security.cert.CertificateException()) }))
    }
    @Test fun tlsDisconnectIsRetryableButUnverifiedPeerNeverBecomesNetworkOrTrusted() {
        assertEquals(TransferError.NETWORK to true, classify(IOException("wrapped", javax.net.ssl.SSLException("closed"))))
        assertEquals(TransferError.NETWORK to true, classify(javax.net.ssl.SSLException("closed")))
        assertEquals(TransferError.CERTIFICATE to false, classify(javax.net.ssl.SSLPeerUnverifiedException("hostname")))
    }
    @Test fun providerBoundaryConvertsNetworkFailureAndRetainsCause() = runBlocking {
        val cause = IOException("wrapper", ConnectException())
        try { providerCall { throw cause }; fail("Must expose typed network error") }
        catch (error: TransferException) { assertEquals(TransferError.NETWORK,error.error); assertTrue(error.retryable); assertSame(cause,error.cause) }
    }
    @Test fun cancellationAndInvalidParametersAreNotConvertedToRetryableFailures() = runBlocking {
        val canceled = CancellationException("cancel")
        try { providerCall { throw canceled }; fail() } catch (error: CancellationException) { assertSame(canceled,error) }
        val invalid = IllegalArgumentException("path")
        try { providerCall { throw invalid }; fail() } catch (error: IllegalArgumentException) { assertSame(invalid,error) }
        val explicit = TransferException(TransferError.CONFLICT)
        try { providerCall { throw explicit }; fail() } catch (error: TransferException) { assertSame(explicit,error) }
    }
    @Test fun unknownIoIsNotMisrepresentedAsNetworkOrPermission() {
        assertEquals(TransferError.UNKNOWN to true,classify(IOException("unknown storage error")))
        assertEquals(TransferError.UNKNOWN to false,classify(IllegalStateException("unknown")))
    }
}
