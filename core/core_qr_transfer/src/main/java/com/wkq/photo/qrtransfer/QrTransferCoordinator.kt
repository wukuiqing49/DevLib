package com.wkq.photo.qrtransfer

import android.content.Context
import android.os.Build
import com.wkq.photo.qrtransfer.file.IntegrityException
import com.wkq.photo.qrtransfer.file.ReceivedFileSink
import com.wkq.photo.qrtransfer.model.PreparedTransferSource
import com.wkq.photo.qrtransfer.model.QrTransferSession
import com.wkq.photo.qrtransfer.model.TransferError
import com.wkq.photo.qrtransfer.model.TransferFile
import com.wkq.photo.qrtransfer.model.TransferPhase
import com.wkq.photo.qrtransfer.model.TransferProgress
import com.wkq.photo.qrtransfer.model.TransferRole
import com.wkq.photo.qrtransfer.model.TransferState
import com.wkq.photo.qrtransfer.nearby.NearbyEvent
import com.wkq.photo.qrtransfer.nearby.NearbyTransferEngine
import com.wkq.photo.qrtransfer.nearby.PayloadStatus
import com.wkq.photo.qrtransfer.nearby.ReceivedFileHandle
import com.wkq.photo.qrtransfer.protocol.ManifestChunker
import com.wkq.photo.qrtransfer.protocol.QrSessionCodec
import com.wkq.photo.qrtransfer.protocol.TransferMessage
import com.wkq.photo.qrtransfer.protocol.TransferMessageCodec
import com.wkq.photo.qrtransfer.protocol.TransferMessageType
import com.wkq.photo.qrtransfer.protocol.TransferProtocolValidator
import com.wkq.photo.qrtransfer.security.ConnectionProof
import com.wkq.photo.qrtransfer.security.EndpointCredentialCodec
import com.wkq.photo.qrtransfer.security.SessionSecretGenerator
import com.wkq.photo.qrtransfer.security.base64UrlBytes
import java.util.TreeMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class QrTransferCoordinator(
    private val context: Context,
    private val engine: NearbyTransferEngine,
    private val sink: ReceivedFileSink,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var role: TransferRole? = null
    private var session: QrTransferSession? = null
    private var endpointId: String? = null
    private var localNonce: ByteArray? = null
    private var receiverNonce: ByteArray? = null
    private var senderNonce: ByteArray? = null
    private var authenticationDigits = ""
    private var authenticated = false
    private var sources: List<PreparedTransferSource> = emptyList()
    private var transferId: String? = null
    private var expiryJob: Job? = null
    private var sendJob: Job? = null
    private var cancelJob: Job? = null
    private val queuedMessages = ArrayDeque<TransferMessage>()
    private val manifestChunks = TreeMap<Int, List<TransferFile>>()
    private var expectedManifestChunks = 0
    private var expectedFileCount = 0
    private var expectedTotalBytes = 0L
    private var transferCompleteReceived = false
    private val bindings = mutableMapOf<Long, String>()
    private val boundPayloadIds = mutableSetOf<Long>()
    private val boundFileIds = mutableSetOf<String>()
    private val incomingFiles = mutableMapOf<Long, ReceivedFileHandle>()
    private val incomingStatuses = mutableMapOf<Long, PayloadStatus>()
    private val outgoingFiles = mutableMapOf<Long, TransferFile>()
    private val outgoingCompletion = mutableMapOf<Long, CompletableDeferred<PayloadStatus>>()
    private val outgoingTerminalStatus = mutableMapOf<Long, PayloadStatus>()
    private val completedFileIds = linkedSetOf<String>()
    private val receiveStoreMutex = Mutex()
    private val eventJob = scope.launch {
        engine.events.collect { event ->
            try {
                handleEvent(event)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                fail(TransferError.TRANSFER_FAILED)
            }
        }
    }

    suspend fun startReceiving(serviceId: String) {
        reset()
        if (!engine.isAvailable()) return fail(TransferError.UNSUPPORTED_DEVICE)
        role = TransferRole.RECEIVER
        val created = SessionSecretGenerator.create(serviceId)
        session = created
        val nonce = created.receiverNonce.base64UrlBytes()
        localNonce = nonce
        receiverNonce = nonce
        _state.value = TransferState(
            role = role,
            phase = TransferPhase.PREPARING,
            sessionQrContent = QrSessionCodec.encode(created),
        )
        try {
            engine.startAdvertising(serviceId, EndpointCredentialCodec.encode(created, TransferRole.RECEIVER, nonce))
            _state.value = _state.value.copy(phase = TransferPhase.WAITING_FOR_SCAN)
            expiryJob = scope.launch {
                delay((created.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                if (!authenticated && role == TransferRole.RECEIVER) fail(TransferError.EXPIRED_QR)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            fail(TransferError.ADVERTISING_FAILED)
        }
    }

    suspend fun startSending(qrContent: String, preparedSources: List<PreparedTransferSource>, serviceId: String) {
        reset()
        if (!engine.isAvailable()) return fail(TransferError.UNSUPPORTED_DEVICE)
        val scanned = try {
            QrSessionCodec.decode(qrContent)
        } catch (error: IllegalArgumentException) {
            return fail(if (error.message?.contains("expired", true) == true) TransferError.EXPIRED_QR else TransferError.INVALID_QR)
        }
        if (scanned.serviceId != serviceId) return fail(TransferError.INVALID_QR)
        val preparedFiles = preparedSources.map(PreparedTransferSource::file)
        if (preparedSources.size !in 1..TransferProtocolValidator.MAX_FILES ||
            preparedFiles.any { !TransferProtocolValidator.isValidFile(it) } ||
            preparedFiles.sumOf(TransferFile::size) > TransferProtocolValidator.MAX_TOTAL_BYTES
        ) {
            return fail(TransferError.FILE_READ_FAILED)
        }
        role = TransferRole.SENDER
        session = scanned
        sources = preparedSources
        transferId = UUID.randomUUID().toString()
        val nonce = SessionSecretGenerator.nonce()
        localNonce = nonce
        senderNonce = nonce
        receiverNonce = scanned.receiverNonce.base64UrlBytes()
        _state.value = TransferState(
            role = role,
            phase = TransferPhase.DISCOVERING,
            files = sources.map(PreparedTransferSource::file),
            progress = TransferProgress(totalFiles = sources.size, totalBytes = sources.sumOf { it.file.size }),
        )
        try {
            engine.startDiscovery(serviceId)
            expiryJob = scope.launch {
                delay((scanned.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                if (!authenticated) {
                    fail(if (endpointId == null) TransferError.ENDPOINT_NOT_FOUND else TransferError.AUTHENTICATION_FAILED)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            fail(TransferError.DISCOVERY_FAILED)
        }
    }

    fun cancel() {
        if (_state.value.phase in TERMINAL_PHASES || cancelJob?.isActive == true) return
        val currentEndpoint = endpointId
        val currentSession = session
        cancelJob = scope.launch {
            if (currentEndpoint != null && currentSession != null) {
                withTimeoutOrNull(CANCEL_NOTIFY_TIMEOUT_MS) {
                    runCatching {
                        engine.sendBytes(
                            currentEndpoint,
                            TransferMessageCodec.encode(
                                TransferMessage(TransferMessageType.TRANSFER_CANCEL, currentSession.sessionId, transferId)
                            ),
                        )
                    }
                }
            }
            _state.value = _state.value.copy(
                phase = TransferPhase.CANCELLED,
                sessionQrContent = null,
                authenticationDigits = null,
                error = null,
            )
            releaseTransport()
        }
    }

    fun confirmAuthentication(matches: Boolean) {
        if (_state.value.phase != TransferPhase.AWAITING_USER_CONFIRMATION) return
        if (!matches) {
            fail(TransferError.AUTHENTICATION_FAILED)
            return
        }
        scope.launch { finishAuthentication() }
    }

    private suspend fun handleEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.EndpointFound -> onEndpointFound(event)
            is NearbyEvent.ConnectionInitiated -> onConnectionInitiated(event)
            is NearbyEvent.Connected -> onConnected(event.endpointId)
            is NearbyEvent.ConnectionRejected -> {
                if (event.endpointId == endpointId) fail(TransferError.CONNECTION_FAILED)
            }
            is NearbyEvent.Disconnected -> {
                if (event.endpointId == endpointId && _state.value.phase !in TERMINAL_PHASES) {
                    fail(TransferError.DISCONNECTED)
                }
            }
            is NearbyEvent.BytesReceived -> onBytesReceived(event)
            is NearbyEvent.FileReceived -> onFileReceived(event)
            is NearbyEvent.PayloadUpdate -> onPayloadUpdate(event)
            is NearbyEvent.EndpointLost -> Unit
        }
    }

    private suspend fun onEndpointFound(event: NearbyEvent.EndpointFound) {
        if (role != TransferRole.SENDER || endpointId != null) return
        val currentSession = session ?: return
        val credential = EndpointCredentialCodec.decodeAndVerify(event.endpointInfo, currentSession, TransferRole.RECEIVER)
            ?: return
        endpointId = event.endpointId
        receiverNonce = credential.nonce
        engine.stopDiscovery()
        _state.value = _state.value.copy(phase = TransferPhase.CONNECTING)
        try {
            engine.requestConnection(
                event.endpointId,
                EndpointCredentialCodec.encode(currentSession, TransferRole.SENDER, checkNotNull(localNonce)),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            fail(TransferError.CONNECTION_FAILED)
        }
    }

    private suspend fun onConnectionInitiated(event: NearbyEvent.ConnectionInitiated) {
        val currentRole = role ?: return engine.rejectConnection(event.endpointId)
        val currentSession = session ?: return engine.rejectConnection(event.endpointId)
        if (endpointId != null && endpointId != event.endpointId) {
            return engine.rejectConnection(event.endpointId)
        }
        val remoteRole = if (currentRole == TransferRole.RECEIVER) TransferRole.SENDER else TransferRole.RECEIVER
        val credential = EndpointCredentialCodec.decodeAndVerify(event.endpointInfo, currentSession, remoteRole)
        if (credential == null || currentSession.expiresAt <= System.currentTimeMillis()) {
            return engine.rejectConnection(event.endpointId)
        }
        endpointId = event.endpointId
        if (remoteRole == TransferRole.SENDER) senderNonce = credential.nonce else receiverNonce = credential.nonce
        authenticationDigits = event.authenticationDigits
        _state.value = _state.value.copy(
            phase = TransferPhase.AUTHENTICATING,
            authenticationDigits = event.authenticationDigits,
        )
        try {
            engine.acceptConnection(event.endpointId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            fail(TransferError.CONNECTION_FAILED)
        }
    }

    private suspend fun onConnected(connectedEndpoint: String) {
        if (connectedEndpoint != endpointId) return
        val currentSession = session ?: return
        val currentRole = role ?: return
        val proof = ConnectionProof.create(
            currentSession,
            currentRole,
            authenticationDigits,
            checkNotNull(receiverNonce),
            checkNotNull(senderNonce),
        )
        send(
            TransferMessage(
                type = TransferMessageType.AUTH_PROOF,
                sessionId = currentSession.sessionId,
                role = currentRole.name,
                proof = proof,
            )
        )
    }

    private suspend fun onBytesReceived(event: NearbyEvent.BytesReceived) {
        if (event.endpointId != endpointId) return
        val message = runCatching { TransferMessageCodec.decode(event.bytes) }.getOrElse {
            return fail(TransferError.TRANSFER_FAILED)
        }
        if (message.sessionId != session?.sessionId) return fail(TransferError.AUTHENTICATION_FAILED)
        if (!authenticated && message.type != TransferMessageType.AUTH_PROOF) {
            if (queuedMessages.size >= MAX_QUEUED_MESSAGES) return fail(TransferError.AUTHENTICATION_FAILED)
            queuedMessages += message
            return
        }
        processMessage(message)
    }

    private suspend fun processMessage(message: TransferMessage) {
        when (message.type) {
            TransferMessageType.AUTH_PROOF -> authenticate(message)
            TransferMessageType.HELLO -> _state.value = _state.value.copy(peerName = message.deviceName?.take(80))
            TransferMessageType.MANIFEST_BEGIN -> receiveManifestBegin(message)
            TransferMessageType.MANIFEST_CHUNK -> receiveManifestChunk(message)
            TransferMessageType.MANIFEST_END -> receiveManifestEnd(message)
            TransferMessageType.TRANSFER_READY -> {
                if (role == TransferRole.SENDER &&
                    message.transferId == transferId &&
                    _state.value.phase == TransferPhase.SENDING
                ) {
                    expiryJob?.cancel()
                    sendFiles()
                }
            }
            TransferMessageType.FILE_BINDING -> receiveBinding(message)
            TransferMessageType.TRANSFER_COMPLETE -> {
                if (role != TransferRole.RECEIVER || message.transferId != transferId) return
                transferCompleteReceived = true
                confirmReceiveIfComplete()
            }
            TransferMessageType.RECEIVE_CONFIRMED -> {
                if (role == TransferRole.SENDER && message.transferId == transferId) completeSender()
            }
            TransferMessageType.TRANSFER_CANCEL -> {
                _state.value = _state.value.copy(
                    phase = TransferPhase.CANCELLED,
                    sessionQrContent = null,
                    authenticationDigits = null,
                )
                releaseTransport()
            }
            TransferMessageType.TRANSFER_FAILED -> fail(TransferError.TRANSFER_FAILED)
        }
    }

    private suspend fun authenticate(message: TransferMessage) {
        if (authenticated) return
        val currentRole = role ?: return
        val remoteRole = if (currentRole == TransferRole.SENDER) TransferRole.RECEIVER else TransferRole.SENDER
        val valid = message.role == remoteRole.name && message.proof != null && ConnectionProof.verify(
            message.proof,
            checkNotNull(session),
            remoteRole,
            authenticationDigits,
            checkNotNull(receiverNonce),
            checkNotNull(senderNonce),
        )
        if (!valid) {
            _state.value = _state.value.copy(phase = TransferPhase.AWAITING_USER_CONFIRMATION)
            return
        }
        finishAuthentication()
    }

    private suspend fun finishAuthentication() {
        if (authenticated) return
        val currentRole = role ?: return
        authenticated = true
        expiryJob?.cancel()
        engine.stopAdvertising()
        engine.stopDiscovery()
        val nextPhase = if (currentRole == TransferRole.RECEIVER) TransferPhase.WAITING_FOR_MANIFEST else TransferPhase.CONNECTING
        _state.value = _state.value.copy(
            phase = nextPhase,
            sessionQrContent = null,
            authenticationDigits = null,
        )
        send(
            TransferMessage(
                type = TransferMessageType.HELLO,
                sessionId = checkNotNull(session).sessionId,
                deviceName = Build.MODEL.take(80),
            )
        )
        if (currentRole == TransferRole.SENDER) {
            sendManifest()
        } else {
            schedulePhaseTimeout(TransferPhase.WAITING_FOR_MANIFEST, MANIFEST_TIMEOUT_MS)
        }
        while (queuedMessages.isNotEmpty()) processMessage(queuedMessages.removeFirst())
    }

    private suspend fun sendManifest() {
        val currentSession = checkNotNull(session)
        val currentTransferId = checkNotNull(transferId)
        val files = sources.map(PreparedTransferSource::file)
        val chunks = ManifestChunker.chunk(currentSession.sessionId, currentTransferId, files)
        _state.value = _state.value.copy(phase = TransferPhase.SENDING)
        send(
            TransferMessage(
                type = TransferMessageType.MANIFEST_BEGIN,
                sessionId = currentSession.sessionId,
                transferId = currentTransferId,
                totalChunks = chunks.size,
                totalFiles = files.size,
                totalBytes = files.sumOf(TransferFile::size),
            )
        )
        chunks.forEach { send(it) }
        send(TransferMessage(TransferMessageType.MANIFEST_END, currentSession.sessionId, currentTransferId))
        schedulePhaseTimeout(TransferPhase.SENDING, READY_TIMEOUT_MS)
    }

    private fun receiveManifestBegin(message: TransferMessage) {
        if (role != TransferRole.RECEIVER) return
        if (transferId != null || !TransferProtocolValidator.isValidManifestBegin(message)) {
            return fail(TransferError.TRANSFER_FAILED)
        }
        transferId = checkNotNull(message.transferId)
        manifestChunks.clear()
        expectedManifestChunks = checkNotNull(message.totalChunks)
        expectedFileCount = checkNotNull(message.totalFiles)
        expectedTotalBytes = checkNotNull(message.totalBytes)
    }

    private fun receiveManifestChunk(message: TransferMessage) {
        if (role != TransferRole.RECEIVER) return
        val currentTransferId = transferId ?: return fail(TransferError.TRANSFER_FAILED)
        if (!TransferProtocolValidator.isValidManifestChunk(message, currentTransferId, expectedManifestChunks)) {
            return fail(TransferError.TRANSFER_FAILED)
        }
        val sequence = checkNotNull(message.sequence)
        if (manifestChunks.putIfAbsent(sequence, checkNotNull(message.files)) != null) {
            fail(TransferError.TRANSFER_FAILED)
        }
    }

    private suspend fun receiveManifestEnd(message: TransferMessage) {
        if (role != TransferRole.RECEIVER || message.transferId != transferId) return
        if (manifestChunks.size != expectedManifestChunks) return fail(TransferError.TRANSFER_FAILED)
        val files = (0 until expectedManifestChunks).flatMap { index -> manifestChunks[index].orEmpty() }
        if (!TransferProtocolValidator.isValidManifest(files, expectedFileCount, expectedTotalBytes)) {
            return fail(TransferError.TRANSFER_FAILED)
        }
        expiryJob?.cancel()
        if (!sink.hasCapacity(expectedTotalBytes)) return fail(TransferError.STORAGE_FAILED)
        _state.value = _state.value.copy(
            phase = TransferPhase.RECEIVING,
            files = files,
            progress = TransferProgress(totalFiles = files.size, totalBytes = expectedTotalBytes),
        )
        send(TransferMessage(TransferMessageType.TRANSFER_READY, checkNotNull(session).sessionId, transferId))
    }

    private fun sendFiles() {
        if (sendJob?.isActive == true) return
        expiryJob?.cancel()
        sendJob = scope.launch {
            val target = endpointId ?: return@launch
            for ((index, source) in sources.withIndex()) {
                _state.value = _state.value.copy(
                    phase = TransferPhase.SENDING,
                    progress = _state.value.progress.copy(currentFileIndex = index + 1, currentTotalBytes = source.file.size),
                )
                val descriptor = try {
                    context.contentResolver.openFileDescriptor(source.uri, "r")
                        ?: return@launch fail(TransferError.FILE_READ_FAILED)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@launch fail(TransferError.FILE_READ_FAILED)
                }
                val payloadId = try {
                    engine.sendFile(target, descriptor)
                } catch (error: CancellationException) {
                    descriptor.close()
                    throw error
                } catch (_: Throwable) {
                    descriptor.close()
                    return@launch fail(TransferError.TRANSFER_FAILED)
                }
                outgoingFiles[payloadId] = source.file
                val completion = CompletableDeferred<PayloadStatus>()
                outgoingCompletion[payloadId] = completion
                outgoingTerminalStatus.remove(payloadId)?.let(completion::complete)
                send(
                    TransferMessage(
                        type = TransferMessageType.FILE_BINDING,
                        sessionId = checkNotNull(session).sessionId,
                        transferId = transferId,
                        fileId = source.file.fileId,
                        payloadId = payloadId,
                    )
                )
                if (completion.await() != PayloadStatus.SUCCESS) return@launch fail(TransferError.TRANSFER_FAILED)
                completedFileIds += source.file.fileId
            }
            _state.value = _state.value.copy(phase = TransferPhase.WAITING_FOR_CONFIRMATION)
            send(TransferMessage(TransferMessageType.TRANSFER_COMPLETE, checkNotNull(session).sessionId, transferId))
            schedulePhaseTimeout(TransferPhase.WAITING_FOR_CONFIRMATION, CONFIRMATION_TIMEOUT_MS)
        }
    }

    private fun receiveBinding(message: TransferMessage) {
        if (role != TransferRole.RECEIVER || message.transferId != transferId) return
        val payloadId = message.payloadId ?: return fail(TransferError.TRANSFER_FAILED)
        val fileId = message.fileId ?: return fail(TransferError.TRANSFER_FAILED)
        if (_state.value.files.none { it.fileId == fileId } ||
            payloadId in boundPayloadIds || fileId in boundFileIds || boundPayloadIds.size >= expectedFileCount
        ) {
            return fail(TransferError.TRANSFER_FAILED)
        }
        boundPayloadIds += payloadId
        boundFileIds += fileId
        bindings[payloadId] = fileId
        processIncomingIfReady(payloadId)
    }

    private fun onFileReceived(event: NearbyEvent.FileReceived) {
        if (event.endpointId != endpointId || role != TransferRole.RECEIVER ||
            (event.payloadId !in incomingFiles && incomingFiles.size >= expectedFileCount)
        ) {
            event.file.close()
            if (event.endpointId == endpointId && role == TransferRole.RECEIVER) fail(TransferError.TRANSFER_FAILED)
            return
        }
        incomingFiles.put(event.payloadId, event.file)?.close()
        processIncomingIfReady(event.payloadId)
    }

    private fun onPayloadUpdate(event: NearbyEvent.PayloadUpdate) {
        if (event.endpointId != endpointId) return
        if (role == TransferRole.SENDER) {
            val file = outgoingFiles[event.payloadId]
            if (file != null) {
                val completedBytes = sources.filter { it.file.fileId in completedFileIds }.sumOf { it.file.size }
                _state.value = _state.value.copy(
                    progress = _state.value.progress.copy(
                        currentBytes = event.bytesTransferred.coerceAtLeast(0L),
                        currentTotalBytes = event.totalBytes.coerceAtLeast(file.size),
                        transferredBytes = completedBytes + event.bytesTransferred.coerceIn(0L, file.size),
                    )
                )
            }
            if (event.status != PayloadStatus.IN_PROGRESS) {
                val completion = outgoingCompletion.remove(event.payloadId)
                if (completion != null) completion.complete(event.status) else outgoingTerminalStatus[event.payloadId] = event.status
            }
        } else if (role == TransferRole.RECEIVER) {
            if (event.payloadId !in incomingStatuses && incomingStatuses.size >= expectedFileCount) {
                return fail(TransferError.TRANSFER_FAILED)
            }
            incomingStatuses[event.payloadId] = event.status
            val completedBytes = _state.value.files.filter { it.fileId in completedFileIds }.sumOf(TransferFile::size)
            val currentFileSize = bindings[event.payloadId]
                ?.let { fileId -> _state.value.files.firstOrNull { it.fileId == fileId }?.size }
            val currentBytes = event.bytesTransferred.coerceAtLeast(0L)
                .let { bytes -> currentFileSize?.let(bytes::coerceAtMost) ?: bytes }
            _state.value = _state.value.copy(
                progress = _state.value.progress.copy(
                    currentBytes = currentBytes,
                    currentTotalBytes = event.totalBytes.coerceAtLeast(0L),
                    transferredBytes = (completedBytes + currentBytes).coerceAtMost(_state.value.progress.totalBytes),
                )
            )
            if (event.status == PayloadStatus.FAILURE || event.status == PayloadStatus.CANCELLED) {
                fail(TransferError.TRANSFER_FAILED)
            } else {
                processIncomingIfReady(event.payloadId)
            }
        }
    }

    private fun processIncomingIfReady(payloadId: Long) {
        if (incomingStatuses[payloadId] != PayloadStatus.SUCCESS) return
        val fileId = bindings[payloadId] ?: return
        val handle = incomingFiles.remove(payloadId) ?: return
        val file = _state.value.files.firstOrNull { it.fileId == fileId } ?: return handle.close()
        bindings.remove(payloadId)
        incomingStatuses.remove(payloadId)
        if (handle.size >= 0L && handle.size != file.size) {
            handle.close()
            return fail(TransferError.INTEGRITY_FAILED)
        }
        scope.launch {
            receiveStoreMutex.withLock {
                _state.value = _state.value.copy(phase = TransferPhase.VERIFYING)
                try {
                    withContext(Dispatchers.IO) { sink.store(file, handle.openInputStream()) }
                    completedFileIds += file.fileId
                    _state.value = _state.value.copy(
                        phase = TransferPhase.RECEIVING,
                        completedFiles = completedFileIds.toList(),
                        progress = _state.value.progress.copy(
                            currentFileIndex = completedFileIds.size,
                            transferredBytes = _state.value.files.filter { it.fileId in completedFileIds }.sumOf(TransferFile::size),
                        ),
                    )
                    confirmReceiveIfComplete()
                } catch (_: IntegrityException) {
                    fail(TransferError.INTEGRITY_FAILED)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    fail(TransferError.STORAGE_FAILED)
                } finally {
                    handle.close()
                }
            }
        }
    }

    private suspend fun confirmReceiveIfComplete() {
        if (!transferCompleteReceived || completedFileIds.size != _state.value.files.size) return
        send(TransferMessage(TransferMessageType.RECEIVE_CONFIRMED, checkNotNull(session).sessionId, transferId))
        _state.value = _state.value.copy(
            phase = TransferPhase.COMPLETED,
            sessionQrContent = null,
            authenticationDigits = null,
        )
        endpointId?.let(engine::disconnect)
    }

    private fun completeSender() {
        expiryJob?.cancel()
        _state.value = _state.value.copy(
            phase = TransferPhase.COMPLETED,
            sessionQrContent = null,
            authenticationDigits = null,
            completedFiles = sources.map { it.file.fileId },
            progress = _state.value.progress.copy(
                currentFileIndex = sources.size,
                transferredBytes = sources.sumOf { it.file.size },
            ),
        )
        endpointId?.let(engine::disconnect)
    }

    private suspend fun send(message: TransferMessage) {
        val target = endpointId ?: return
        try {
            engine.sendBytes(target, TransferMessageCodec.encode(message))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            fail(TransferError.TRANSFER_FAILED)
        }
    }

    private fun fail(error: TransferError) {
        if (_state.value.phase in TERMINAL_PHASES) return
        _state.value = _state.value.copy(
            phase = TransferPhase.FAILED,
            sessionQrContent = null,
            authenticationDigits = null,
            error = error,
        )
        releaseTransport()
    }

    private fun reset() {
        cancelJob?.cancel()
        cancelJob = null
        releaseTransport()
        role = null
        session = null
        endpointId = null
        localNonce = null
        receiverNonce = null
        senderNonce = null
        authenticationDigits = ""
        authenticated = false
        sources = emptyList()
        transferId = null
        queuedMessages.clear()
        manifestChunks.clear()
        expectedManifestChunks = 0
        expectedFileCount = 0
        expectedTotalBytes = 0L
        transferCompleteReceived = false
        bindings.clear()
        boundPayloadIds.clear()
        boundFileIds.clear()
        incomingFiles.values.forEach { it.close() }
        incomingFiles.clear()
        incomingStatuses.clear()
        outgoingFiles.clear()
        outgoingCompletion.values.forEach { it.cancel() }
        outgoingCompletion.clear()
        outgoingTerminalStatus.clear()
        completedFileIds.clear()
        _state.value = TransferState()
    }

    private fun releaseTransport() {
        expiryJob?.cancel()
        sendJob?.cancel()
        expiryJob = null
        sendJob = null
        incomingFiles.values.forEach { runCatching { it.close() } }
        incomingFiles.clear()
        engine.stopAll()
    }

    override fun close() {
        eventJob.cancel()
        reset()
        engine.close()
    }

    private fun schedulePhaseTimeout(phase: TransferPhase, timeoutMillis: Long) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(timeoutMillis)
            if (_state.value.phase == phase) fail(TransferError.TRANSFER_FAILED)
        }
    }

    companion object {
        private const val MAX_QUEUED_MESSAGES = 16
        private const val CANCEL_NOTIFY_TIMEOUT_MS = 500L
        private const val MANIFEST_TIMEOUT_MS = 30_000L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val CONFIRMATION_TIMEOUT_MS = 30_000L
        private val TERMINAL_PHASES = setOf(TransferPhase.COMPLETED, TransferPhase.FAILED, TransferPhase.CANCELLED)
    }
}
