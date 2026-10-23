package com.wkq.photo.qrtransfer.nearby

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.SharedFlow

interface NearbyTransferEngine : Closeable {
    val events: SharedFlow<NearbyEvent>

    fun isAvailable(): Boolean
    suspend fun startAdvertising(serviceId: String, endpointInfo: ByteArray)
    suspend fun startDiscovery(serviceId: String)
    suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray)
    suspend fun acceptConnection(endpointId: String)
    suspend fun rejectConnection(endpointId: String)
    suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long
    suspend fun sendFile(endpointId: String, descriptor: ParcelFileDescriptor): Long
    suspend fun cancelPayload(payloadId: Long)
    fun stopAdvertising()
    fun stopDiscovery()
    fun disconnect(endpointId: String)
    fun stopAll()
}

sealed interface NearbyEvent {
    data class EndpointFound(val endpointId: String, val endpointInfo: ByteArray) : NearbyEvent
    data class EndpointLost(val endpointId: String) : NearbyEvent
    data class ConnectionInitiated(
        val endpointId: String,
        val endpointInfo: ByteArray,
        val authenticationDigits: String,
        val rawAuthenticationToken: ByteArray,
        val incoming: Boolean,
    ) : NearbyEvent
    data class Connected(val endpointId: String) : NearbyEvent
    data class ConnectionRejected(val endpointId: String, val statusCode: Int) : NearbyEvent
    data class Disconnected(val endpointId: String) : NearbyEvent
    data class BytesReceived(val endpointId: String, val payloadId: Long, val bytes: ByteArray) : NearbyEvent
    data class FileReceived(val endpointId: String, val payloadId: Long, val file: ReceivedFileHandle) : NearbyEvent
    data class PayloadUpdate(
        val endpointId: String,
        val payloadId: Long,
        val status: PayloadStatus,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) : NearbyEvent
}

enum class PayloadStatus { IN_PROGRESS, SUCCESS, FAILURE, CANCELLED }

interface ReceivedFileHandle : Closeable {
    val size: Long
    fun openInputStream(): InputStream
    fun asTemporaryFile(): File?
}
