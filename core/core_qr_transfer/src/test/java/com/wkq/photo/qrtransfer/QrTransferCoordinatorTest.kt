package com.wkq.photo.qrtransfer

import android.os.ParcelFileDescriptor
import com.wkq.photo.qrtransfer.file.ReceivedFileSink
import com.wkq.photo.qrtransfer.model.TransferPhase
import com.wkq.photo.qrtransfer.model.TransferRole
import com.wkq.photo.qrtransfer.nearby.NearbyEvent
import com.wkq.photo.qrtransfer.nearby.NearbyTransferEngine
import com.wkq.photo.qrtransfer.protocol.QrSessionCodec
import com.wkq.photo.qrtransfer.security.EndpointCredentialCodec
import com.wkq.photo.qrtransfer.security.SessionSecretGenerator
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QrTransferCoordinatorTest {
    @Test
    fun unrelatedDisconnect_doesNotFailActiveSession() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.startReceiving(SERVICE_ID)

        fixture.engine.emit(NearbyEvent.Disconnected("unrelated-endpoint"))
        yield()

        assertEquals(TransferPhase.WAITING_FOR_SCAN, fixture.coordinator.state.value.phase)
        fixture.close()
    }

    @Test
    fun cancel_notifiesPeerBeforeStoppingTransport() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.startReceiving(SERVICE_ID)
        val session = QrSessionCodec.decode(checkNotNull(fixture.coordinator.state.value.sessionQrContent))
        val senderNonce = SessionSecretGenerator.nonce()
        fixture.engine.emit(
            NearbyEvent.ConnectionInitiated(
                endpointId = "primary-endpoint",
                endpointInfo = EndpointCredentialCodec.encode(session, TransferRole.SENDER, senderNonce),
                authenticationDigits = "1234",
                rawAuthenticationToken = byteArrayOf(1),
                incoming = true,
            )
        )
        yield()
        fixture.engine.actions.clear()

        fixture.coordinator.cancel()
        yield()

        assertEquals(TransferPhase.CANCELLED, fixture.coordinator.state.value.phase)
        assertTrue(fixture.engine.actions.indexOf("sendBytes") < fixture.engine.actions.indexOf("stopAll"))
        fixture.close()
    }

    private class Fixture {
        val engine = FakeNearbyTransferEngine()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = QrTransferCoordinator(
            context = RuntimeEnvironment.getApplication(),
            engine = engine,
            sink = object : ReceivedFileSink {
                override suspend fun store(file: com.wkq.photo.qrtransfer.model.TransferFile, input: InputStream) = "unused"
            },
            scope = scope,
        )

        fun close() {
            coordinator.close()
            scope.cancel()
        }
    }

    private class FakeNearbyTransferEngine : NearbyTransferEngine {
        private val mutableEvents = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<NearbyEvent> = mutableEvents
        val actions = mutableListOf<String>()

        suspend fun emit(event: NearbyEvent) = mutableEvents.emit(event)
        override fun isAvailable() = true
        override suspend fun startAdvertising(serviceId: String, endpointInfo: ByteArray) = Unit
        override suspend fun startDiscovery(serviceId: String) = Unit
        override suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray) = Unit
        override suspend fun acceptConnection(endpointId: String) = Unit
        override suspend fun rejectConnection(endpointId: String) = Unit
        override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long {
            actions += "sendBytes"
            return 1L
        }
        override suspend fun sendFile(endpointId: String, descriptor: ParcelFileDescriptor) = 2L
        override suspend fun cancelPayload(payloadId: Long) = Unit
        override fun stopAdvertising() = Unit
        override fun stopDiscovery() = Unit
        override fun disconnect(endpointId: String) = Unit
        override fun stopAll() {
            actions += "stopAll"
        }
        override fun close() = Unit
    }

    private companion object {
        const val SERVICE_ID = "com.wkq.photo.qrtransfer.test"
    }
}
