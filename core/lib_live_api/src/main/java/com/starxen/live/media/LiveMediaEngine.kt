package com.starxen.live.media

import android.media.projection.MediaProjection
import android.view.View

enum class MediaRole { PUBLISHER, AUDIENCE }
enum class MediaMode { VIDEO, VOICE, SCREEN }
enum class RenderMode { FILL, FIT }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }
enum class TrackStatus { IDLE, STARTING, ACTIVE, FAILED }

data class MediaSession(
    val appId: String,
    val channelId: String,
    val uid: Int,
    val token: String,
    val role: MediaRole,
    val mode: MediaMode = MediaMode.VIDEO,
    val publishAudio: Boolean = true,
    val publishVideo: Boolean = true,
    val autoSubscribeAudio: Boolean = true,
    val autoSubscribeVideo: Boolean = true
) {
    override fun toString() = "MediaSession(channelId=$channelId, uid=$uid, role=$role, mode=$mode)"
}

data class VideoProfile(val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int,
    val mirrorPublished: Boolean = false) {
    init { require(width > 0 && height > 0 && fps in 1..60 && bitrateKbps > 0) }
    companion object {
        fun portrait(level: Int) = when (level) {
            360 -> VideoProfile(360, 480, 15, 600)
            540 -> VideoProfile(480, 640, 15, 800)
            720 -> VideoProfile(720, 960, 15, 1500)
            1080 -> VideoProfile(960, 1280, 15, 1500)
            else -> throw IllegalArgumentException("Unsupported portrait profile")
        }
    }
}
data class ScreenProfile(val width: Int, val height: Int, val fps: Int = 15,
    val deviceAudio: Boolean = false, val deviceVolume: Int = 100)

sealed class MediaEvent {
    data class CdnFirstFrame(val id: String) : MediaEvent()
    data class CdnVolume(val id: String, val level: Int) : MediaEvent()
    data class CdnFailed(val id: String) : MediaEvent()
    data class Joined(val uid: Int, val rejoined: Boolean) : MediaEvent()
    object Left : MediaEvent()
    data class Connection(val status: ConnectionStatus) : MediaEvent()
    data class Participant(val uid: Int, val joined: Boolean) : MediaEvent()
    data class Publication(val video: Boolean, val status: TrackStatus) : MediaEvent()
    data class FirstFrame(val uid: Int, val video: Boolean) : MediaEvent()
    data class VideoSize(val uid: Int, val width: Int, val height: Int, val rotation: Int) : MediaEvent()
    data class Volumes(val levels: Map<Int, Int>) : MediaEvent()
    object TokenRequired : MediaEvent()
    data class Failure(val operation: String) : MediaEvent()
}

// Accepted means only that the request was accepted; completion comes through MediaEvent.
sealed class MediaResult {
    object Accepted : MediaResult()
    data class Failed(val operation: String) : MediaResult()
}

/** Controls and events run on the main thread. A caller owns one engine and must close it. */
interface LiveMediaEngine {
    var listener: ((String, MediaEvent) -> Unit)?
    fun initialize(appId: String)
    fun join(session: MediaSession): MediaResult
    fun leave(): MediaResult
    fun preview(view: View, mirror: Boolean = true): MediaResult
    fun detachPreview(): MediaResult
    fun stopPreview(): MediaResult
    fun cameraEnabled(enabled: Boolean): MediaResult
    fun publish(audio: Boolean, video: Boolean): MediaResult
    fun stopPublishing(): MediaResult
    fun play(uid: Int, view: View? = null, video: Boolean = true): MediaResult
    fun stopPlaying(uid: Int): MediaResult
    /** Changes main-channel reception without leaving the room or changing publication. */
    fun playbackSubscription(audio: Boolean, video: Boolean): MediaResult
    fun attachVideo(uid: Int, view: View?, mode: RenderMode = RenderMode.FILL): MediaResult
    fun muteMicrophone(muted: Boolean): MediaResult
    fun muteRemote(uid: Int, muted: Boolean): MediaResult
    fun remoteVolume(uid: Int, volume: Int): MediaResult
    fun playbackVolume(volume: Int): MediaResult
    fun recordingVolume(volume: Int): MediaResult
    /** Records existing room audio without opening another microphone. Controls run on main. */
    fun startAudioRecording(path: String): MediaResult
    fun stopAudioRecording(): MediaResult
    fun switchCamera(): MediaResult
    fun mirrorPreview(enabled: Boolean): MediaResult
    fun torchSupported(): Boolean
    fun torch(enabled: Boolean): MediaResult
    fun configureVideo(profile: VideoProfile): MediaResult
    fun indicateVolumes(enabled: Boolean): MediaResult
    fun renewToken(token: String): MediaResult
    fun screenProjection(projection: MediaProjection): MediaResult
    fun startScreen(profile: ScreenProfile): MediaResult
    fun stopScreen(): MediaResult
    fun publishCdn(url: String): MediaResult
    fun stopPublishCdn(url: String): MediaResult
    fun playCdn(id: String, url: String, view: View?): MediaResult
    fun attachCdn(id: String, view: View?): MediaResult
    fun cdnVolume(id: String, volume: Int): MediaResult
    fun stopCdn(id: String)
    fun joinExtra(session: MediaSession): MediaResult
    fun updateExtra(session: MediaSession): MediaResult
    fun leaveExtra(channelId: String): MediaResult
    fun attachExtra(channelId: String, uid: Int, view: View?): MediaResult
    fun muteExtra(channelId: String, uid: Int, muted: Boolean): MediaResult
    fun extraVolume(channelId: String, uid: Int, volume: Int): MediaResult
    fun renewExtraToken(channelId: String, token: String): MediaResult
    fun setBeauty(processor: TextureBeautyProcessor?): MediaResult
    fun close()
}
