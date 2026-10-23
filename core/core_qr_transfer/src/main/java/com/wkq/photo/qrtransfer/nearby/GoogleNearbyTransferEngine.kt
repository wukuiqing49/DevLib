package com.wkq.photo.qrtransfer.nearby

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class GoogleNearbyTransferEngine(context: Context) : NearbyTransferEngine {
    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val ownedDescriptors = ConcurrentHashMap<Long, ParcelFileDescriptor>()
    private val filePayloadIds = ConcurrentHashMap.newKeySet<Long>()
    private val lastProgressEvents = ConcurrentHashMap<Long, Long>()
    private val _events = MutableSharedFlow<NearbyEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes()?.copyOf() ?: ByteArray(0)
                    _events.tryEmit(NearbyEvent.BytesReceived(endpointId, payload.id, bytes))
                    payload.close()
                }
                Payload.Type.FILE -> {
                    filePayloadIds += payload.id
                    _events.tryEmit(
                        NearbyEvent.FileReceived(
                            endpointId,
                            payload.id,
                            GoogleReceivedFileHandle(payload),
                        )
                    )
                }
                else -> payload.close()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.payloadId !in filePayloadIds) return
            val status = when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> PayloadStatus.SUCCESS
                PayloadTransferUpdate.Status.FAILURE -> PayloadStatus.FAILURE
                PayloadTransferUpdate.Status.CANCELED -> PayloadStatus.CANCELLED
                else -> PayloadStatus.IN_PROGRESS
            }
            val now = SystemClock.elapsedRealtime()
            if (status == PayloadStatus.IN_PROGRESS) {
                val previous = lastProgressEvents[update.payloadId]
                if (previous != null && now - previous < PROGRESS_INTERVAL_MS) return
                lastProgressEvents[update.payloadId] = now
            }
            _events.tryEmit(
                NearbyEvent.PayloadUpdate(
                    endpointId = endpointId,
                    payloadId = update.payloadId,
                    status = status,
                    bytesTransferred = update.bytesTransferred,
                    totalBytes = update.totalBytes,
                )
            )
            if (status != PayloadStatus.IN_PROGRESS) {
                ownedDescriptors.remove(update.payloadId)?.close()
                filePayloadIds.remove(update.payloadId)
                lastProgressEvents.remove(update.payloadId)
            }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            _events.tryEmit(
                NearbyEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    endpointInfo = info.endpointInfo?.copyOf() ?: ByteArray(0),
                    authenticationDigits = info.authenticationDigits.orEmpty(),
                    rawAuthenticationToken = info.rawAuthenticationToken?.copyOf() ?: ByteArray(0),
                    incoming = info.isIncomingConnection,
                )
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) _events.tryEmit(NearbyEvent.Connected(endpointId))
            else _events.tryEmit(NearbyEvent.ConnectionRejected(endpointId, resolution.status.statusCode))
        }

        override fun onDisconnected(endpointId: String) {
            _events.tryEmit(NearbyEvent.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            _events.tryEmit(NearbyEvent.EndpointFound(endpointId, info.endpointInfo?.copyOf() ?: ByteArray(0)))
        }

        override fun onEndpointLost(endpointId: String) {
            _events.tryEmit(NearbyEvent.EndpointLost(endpointId))
        }
    }

    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    override suspend fun startAdvertising(serviceId: String, endpointInfo: ByteArray) {
        client.startAdvertising(
            endpointInfo,
            serviceId,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).awaitResult()
    }

    override suspend fun startDiscovery(serviceId: String) {
        client.startDiscovery(
            serviceId,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).awaitResult()
    }

    override suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray) {
        client.requestConnection(endpointInfo, endpointId, connectionCallback).awaitResult()
    }

    override suspend fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback).awaitResult()
    }

    override suspend fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId).awaitResult()
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long {
        require(bytes.size <= ConnectionsClient.MAX_BYTES_DATA_SIZE)
        val payload = Payload.fromBytes(bytes)
        client.sendPayload(endpointId, payload).awaitResult()
        return payload.id
    }

    override suspend fun sendFile(endpointId: String, descriptor: ParcelFileDescriptor): Long {
        val payload = Payload.fromFile(descriptor)
        ownedDescriptors[payload.id] = descriptor
        filePayloadIds += payload.id
        try {
            client.sendPayload(endpointId, payload).awaitResult()
        } catch (error: Throwable) {
            ownedDescriptors.remove(payload.id)?.close()
            filePayloadIds.remove(payload.id)
            lastProgressEvents.remove(payload.id)
            payload.close()
            throw error
        }
        return payload.id
    }

    override suspend fun cancelPayload(payloadId: Long) {
        runCatching { client.cancelPayload(payloadId).awaitResult() }
        ownedDescriptors.remove(payloadId)?.close()
        filePayloadIds.remove(payloadId)
        lastProgressEvents.remove(payloadId)
    }

    override fun stopAdvertising() = client.stopAdvertising()
    override fun stopDiscovery() = client.stopDiscovery()
    override fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    override fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        ownedDescriptors.values.forEach { runCatching { it.close() } }
        ownedDescriptors.clear()
        filePayloadIds.clear()
        lastProgressEvents.clear()
    }

    override fun close() = stopAll()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 100L
    }
}

private class GoogleReceivedFileHandle(private val payload: Payload) : ReceivedFileHandle {
    private val payloadFile = checkNotNull(payload.asFile())
    override val size: Long get() = payloadFile.size

    override fun openInputStream(): InputStream {
        payloadFile.asJavaFile()?.let { return FileInputStream(it) }
        val descriptor = checkNotNull(payloadFile.asParcelFileDescriptor())
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
    }

    override fun asTemporaryFile(): File? = payloadFile.asJavaFile()
    override fun close() = payload.close()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
