package com.starxen.live.agora

enum class LiveRole {
    HOST,
    AUDIENCE
}

data class AgoraLiveSession @JvmOverloads constructor(
    val appId: String,
    val channelId: String,
    val uid: Int,
    val token: String,
    val role: LiveRole,
    val publishAudio: Boolean = true,
    val publishVideo: Boolean = true,
    val lowLatencyAudience: Boolean = true,
    val mode: LiveMode = LiveMode.VIDEO,
    val autoSubscribeAudio: Boolean = true,
    val autoSubscribeVideo: Boolean = true
) {
    override fun toString(): String =
        "AgoraLiveSession(channelId=$channelId, uid=$uid, role=$role, mode=$mode)"
}

enum class LiveMode { VIDEO, VOICE, SCREEN }

enum class LiveRenderMode { FILL, FIT }

enum class LiveAudioProfile { SPEECH, MUSIC, MUSIC_STEREO, HIGH_QUALITY, HIGH_QUALITY_STEREO }

data class LiveVideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val mirrorPublishedVideo: Boolean = false
) {
    init {
        require(width > 0 && height > 0 && fps in 1..60 && bitrateKbps > 0)
    }

    companion object {
        @JvmStatic
        fun portraitPreset(level: Int): LiveVideoConfig = when (level) {
            360 -> LiveVideoConfig(360, 480, 15, 600)
            540 -> LiveVideoConfig(480, 640, 15, 800)
            720 -> LiveVideoConfig(720, 960, 15, 1500)
            1080 -> LiveVideoConfig(960, 1280, 15, 1500)
            else -> throw IllegalArgumentException("Unsupported portrait preset")
        }
    }
}

data class LiveScreenConfig @JvmOverloads constructor(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 15,
    val captureDeviceAudio: Boolean = false,
    val deviceAudioVolume: Int = 100
) {
    init {
        require(width > 0 && height > 0 && fps in 1..60 && deviceAudioVolume in 0..100)
    }
}

sealed class AgoraLiveEvent {
    data class Joined(val uid: Int, val rejoined: Boolean) : AgoraLiveEvent()
    object Left : AgoraLiveEvent()
    data class Connection(val state: Int, val reason: Int) : AgoraLiveEvent()
    data class RemoteUser(val uid: Int, val joined: Boolean, val reason: Int = 0) : AgoraLiveEvent()
    data class PublishState(val video: Boolean, val state: Int, val previousState: Int) : AgoraLiveEvent()
    data class SubscribeState(val uid: Int, val video: Boolean, val state: Int, val previousState: Int) : AgoraLiveEvent()
    data class RemoteMediaState(val uid: Int, val video: Boolean, val state: Int, val reason: Int) : AgoraLiveEvent()
    data class LocalMediaState(val video: Boolean, val state: Int, val reason: Int) : AgoraLiveEvent()
    data class VideoSize(val uid: Int, val width: Int, val height: Int, val rotation: Int) : AgoraLiveEvent()
    data class FirstFrame(val uid: Int, val video: Boolean) : AgoraLiveEvent()
    data class VideoQuality(val uid: Int, val bitrateKbps: Int, val decoderFps: Int, val renderFps: Int, val packetLoss: Int, val width: Int, val height: Int) : AgoraLiveEvent()
    data class AudioQuality(val uid: Int, val bitrateKbps: Int, val packetLoss: Int, val delayMs: Int) : AgoraLiveEvent()
    data class NetworkQuality(val uid: Int, val upload: Int, val download: Int) : AgoraLiveEvent()
    data class AudioVolumes(val speakers: List<LiveSpeakerVolume>, val totalVolume: Int) : AgoraLiveEvent()
    data class RoleChanged(val role: LiveRole) : AgoraLiveEvent()
    data class RoleChangeFailed(val reason: Int) : AgoraLiveEvent()
    object TokenRequired : AgoraLiveEvent()
    data class TokenRenewed(val result: Int) : AgoraLiveEvent()
    data class RtmpState(val url: String, val state: Int, val error: Int) : AgoraLiveEvent() {
        override fun toString(): String = "RtmpState(state=$state, error=$error)"
    }
    data class PermissionError(val permission: Int) : AgoraLiveEvent()
    data class Error(val code: Int) : AgoraLiveEvent()
}

data class LiveSpeakerVolume(
    val uid: Int,
    val volume: Int
)

interface AgoraLiveListener {
    fun onEvent(channelId: String, event: AgoraLiveEvent) {}
    fun onOperationError(operation: String, code: Int) {}
    fun onCdnPlayerState(playerId: String, state: Int, reason: Int) {}
    fun onCdnPlayerEvent(playerId: String, event: Int) {}
    fun onCdnFirstFrame(playerId: String) {}
    fun onCdnVolume(playerId: String, level: Int) {}
    fun onCdnVideoSize(playerId: String, width: Int, height: Int) {}
    fun onCdnQuality(playerId: String, videoFps: Long, videoBitrate: Long, audioBitrate: Long) {}
    fun onLogUploadResult(requestId: String, success: Boolean, reason: Int) {}
    fun onJoined(channelId: String, uid: Int) {}
    fun onRemoteUserJoined(uid: Int) {}
    fun onRemoteUserLeft(uid: Int) {}
    fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int) {}
    fun onConnectionStateChanged(state: Int, reason: Int) {}
    fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {}
    fun onTokenWillExpire(token: String) {}
    fun onAudioVolumes(speakers: List<LiveSpeakerVolume>, totalVolume: Int) {}
    fun onFaceCountChanged(count: Int) {}
    fun onError(code: Int, message: String?) {}
}

/** Java pages can override only the callbacks they use. */
open class AgoraLiveListenerAdapter : AgoraLiveListener
