package com.starxen.live.agora

import android.content.Context
import android.media.projection.MediaProjection
import android.view.View
import android.os.Looper
import com.starxen.live.media.*
import io.agora.base.TextureBuffer
import io.agora.base.VideoFrame
import io.agora.base.internal.ThreadUtils
import io.agora.base.internal.video.WrappedNativeTextureBuffer
import io.agora.rtc2.Constants

/** Vendor-specific states and frames stop at this adapter. */
class AgoraMediaAdapter(context: Context) : LiveMediaEngine {
    override var listener: ((String, MediaEvent) -> Unit)? = null
    private var channel = ""
    private val engine = AgoraLiveEngine(context, object : AgoraLiveListener {
        override fun onEvent(channelId: String, event: AgoraLiveEvent) {
            val mapped = when (event) {
                is AgoraLiveEvent.Joined -> MediaEvent.Joined(event.uid, event.rejoined)
                AgoraLiveEvent.Left -> MediaEvent.Left
                is AgoraLiveEvent.Connection -> MediaEvent.Connection(when (event.state) {
                    Constants.CONNECTION_STATE_CONNECTING -> ConnectionStatus.CONNECTING
                    Constants.CONNECTION_STATE_CONNECTED -> ConnectionStatus.CONNECTED
                    Constants.CONNECTION_STATE_RECONNECTING -> ConnectionStatus.RECONNECTING
                    Constants.CONNECTION_STATE_FAILED -> ConnectionStatus.FAILED
                    else -> ConnectionStatus.DISCONNECTED
                })
                is AgoraLiveEvent.RemoteUser -> MediaEvent.Participant(event.uid, event.joined)
                is AgoraLiveEvent.PublishState -> MediaEvent.Publication(event.video, when (event.state) {
                    Constants.PUB_STATE_PUBLISHED -> TrackStatus.ACTIVE
                    Constants.PUB_STATE_PUBLISHING -> TrackStatus.STARTING
                    else -> TrackStatus.IDLE
                })
                is AgoraLiveEvent.FirstFrame -> MediaEvent.FirstFrame(event.uid, event.video)
                // Resubscription can resume decoding without repeating the one-time first-frame callback.
                is AgoraLiveEvent.RemoteMediaState -> if (
                    event.video && event.state == Constants.REMOTE_VIDEO_STATE_DECODING ||
                    !event.video && event.state == Constants.REMOTE_AUDIO_STATE_DECODING
                ) MediaEvent.FirstFrame(event.uid, event.video) else null
                is AgoraLiveEvent.VideoSize -> MediaEvent.VideoSize(event.uid, event.width, event.height, event.rotation)
                is AgoraLiveEvent.AudioVolumes -> MediaEvent.Volumes(event.speakers.associate { it.uid to it.volume })
                AgoraLiveEvent.TokenRequired -> MediaEvent.TokenRequired
                is AgoraLiveEvent.Error -> MediaEvent.Failure("engine")
                is AgoraLiveEvent.RoleChangeFailed -> MediaEvent.Failure("role")
                else -> null
            }
            if (mapped != null) listener?.invoke(channelId, mapped)
        }
        override fun onOperationError(operation: String, code: Int) {
            android.util.Log.w("AgoraMedia", "Operation failed: $operation, code=$code")
            listener?.invoke(channel, MediaEvent.Failure(operation))
        }
        override fun onCdnFirstFrame(playerId: String) {
            listener?.invoke(channel, MediaEvent.CdnFirstFrame(playerId))
        }
        override fun onCdnVolume(playerId: String, level: Int) {
            listener?.invoke(channel, MediaEvent.CdnVolume(playerId, level))
        }
        override fun onCdnPlayerState(playerId: String, state: Int, reason: Int) {
            android.util.Log.d("AgoraMedia", "CDN player state=$state, reason=$reason")
            if (state == io.agora.mediaplayer.Constants.MediaPlayerState.getValue(
                    io.agora.mediaplayer.Constants.MediaPlayerState.PLAYER_STATE_FAILED))
                listener?.invoke(channel, MediaEvent.CdnFailed(playerId))
        }
    })

    private fun result(operation: String, code: Int): MediaResult {
        if (code != 0) android.util.Log.w("AgoraMedia", "Operation failed: $operation, code=$code")
        return if (code == 0) MediaResult.Accepted else MediaResult.Failed(operation)
    }
    private fun MediaSession.agora() = AgoraLiveSession(appId, channelId, uid, token,
        if (role == MediaRole.PUBLISHER) LiveRole.HOST else LiveRole.AUDIENCE,
        publishAudio, publishVideo, mode = LiveMode.valueOf(mode.name),
        autoSubscribeAudio = autoSubscribeAudio, autoSubscribeVideo = autoSubscribeVideo)
    override fun initialize(appId: String) = engine.initialize(appId)
    override fun join(session: MediaSession): MediaResult {
        channel = session.channelId
        return result("join", engine.join(session.agora()))
    }
    override fun leave() = result("leave", engine.leave())
    override fun preview(view: View, mirror: Boolean) = result("preview", engine.attachLocalVideo(view, mirror))
    override fun detachPreview() = result("detachPreview", engine.detachLocalVideo())
    override fun stopPreview() = result("stopPreview", engine.stopPreview())
    override fun cameraEnabled(enabled: Boolean) = result("cameraEnabled", engine.setCameraEnabled(enabled))
    override fun publish(audio: Boolean, video: Boolean) = result("publish", engine.startPublishing(audio, video))
    override fun stopPublishing() = result("stopPublishing", engine.stopPublishing())
    override fun play(uid: Int, view: View?, video: Boolean) = result("play", engine.startPlaying(uid, view, video))
    override fun stopPlaying(uid: Int) = result("stopPlaying", engine.stopPlaying(uid))
    override fun playbackSubscription(audio: Boolean, video: Boolean) =
        result("playbackSubscription", engine.setPlaybackSubscription(audio, video))
    override fun attachVideo(uid: Int, view: View?, mode: RenderMode) = result("attachVideo",
        engine.attachRemoteVideo(uid, view, LiveRenderMode.valueOf(mode.name)))
    override fun muteMicrophone(muted: Boolean) = result("microphone", engine.muteMicrophone(muted))
    override fun muteRemote(uid: Int, muted: Boolean) = result("muteRemote", engine.setRemoteAudioMuted(uid, muted))
    override fun remoteVolume(uid: Int, volume: Int) = result("remoteVolume", engine.setRemotePlaybackVolume(uid, volume))
    override fun playbackVolume(volume: Int) = result("playbackVolume", engine.setAllPlaybackVolume(volume))
    override fun recordingVolume(volume: Int) = result("recordingVolume", engine.setRecordingVolume(volume))
    override fun switchCamera() = result("camera", engine.switchCamera())
    override fun mirrorPreview(enabled: Boolean) = result("mirror", engine.setPreviewMirror(enabled))
    override fun torchSupported() = engine.isTorchSupported()
    override fun torch(enabled: Boolean) = result("torch", engine.setTorchEnabled(enabled))
    override fun configureVideo(profile: VideoProfile) = result("videoProfile", engine.setVideoConfig(
        LiveVideoConfig(profile.width, profile.height, profile.fps, profile.bitrateKbps, profile.mirrorPublished)))
    override fun startAudioRecording(path: String) = result("audioRecording", engine.startAudioRecording(path))
    override fun stopAudioRecording() = result("audioRecordingStop", engine.stopAudioRecording())
    override fun indicateVolumes(enabled: Boolean) = result("volumes",
        if (enabled) engine.enableAudioVolumeIndication() else engine.stopAudioVolumeIndication())
    override fun renewToken(token: String) = result("token", engine.renewToken(token))
    override fun screenProjection(projection: MediaProjection) = result("projection", engine.setScreenProjection(projection))
    override fun startScreen(profile: ScreenProfile) = result("screen", engine.startScreenShare(
        LiveScreenConfig(profile.width, profile.height, profile.fps, profile.deviceAudio, profile.deviceVolume)))
    override fun stopScreen() = result("stopScreen", engine.stopScreenShare())
    override fun publishCdn(url: String) = result("publishCdn", engine.addPublishCdnUrl(url))
    override fun stopPublishCdn(url: String) = result("stopPublishCdn", engine.removePublishCdnUrl(url))
    override fun playCdn(id: String, url: String, view: View?) = result("playCdn", engine.startCdnPlayback(id, url, view))
    override fun attachCdn(id: String, view: View?) = result("attachCdn", engine.attachCdnVideo(id, view))
    override fun cdnVolume(id: String, volume: Int) = result("cdnVolume", engine.setCdnPlaybackVolume(id, volume))
    override fun stopCdn(id: String) = engine.stopCdnPlayback(id)
    override fun joinExtra(session: MediaSession) = result("joinExtra", engine.joinExtraChannel(session.agora()))
    override fun updateExtra(session: MediaSession) = result("updateExtra", engine.updateExtraChannel(session.agora()))
    override fun leaveExtra(channelId: String) = result("leaveExtra", engine.leaveExtraChannel(channelId))
    override fun attachExtra(channelId: String, uid: Int, view: View?) = result("attachExtra", engine.attachExtraChannelVideo(channelId, uid, view))
    override fun muteExtra(channelId: String, uid: Int, muted: Boolean) = result("muteExtra", engine.setExtraChannelAudioMuted(channelId, uid, muted))
    override fun extraVolume(channelId: String, uid: Int, volume: Int) = result("extraVolume", engine.setExtraChannelPlaybackVolume(channelId, uid, volume))
    override fun renewExtraToken(channelId: String, token: String) = result("extraToken", engine.renewExtraChannelToken(channelId, token))
    override fun setBeauty(processor: TextureBeautyProcessor?): MediaResult = result("beauty",
        engine.setVideoFrameProcessor(processor?.let { effect -> object : AgoraVideoFrameProcessor {
            override fun process(sourceType: Int, frame: VideoFrame): Boolean {
                val input = frame.buffer as? VideoFrame.TextureBuffer
                if (input == null || input.type != VideoFrame.TextureBuffer.Type.RGB) {
                    effect.unavailable()
                    return true
                }
                val conversionHandler = when (input) {
                    is TextureBuffer -> input.toI420Handler
                    is WrappedNativeTextureBuffer -> input.toI420Handler
                    else -> null
                }
                val converter = when (input) {
                    is TextureBuffer -> input.yuvConverter
                    is WrappedNativeTextureBuffer -> input.yuvConverter
                    else -> null
                }
                if (conversionHandler == null || converter == null || conversionHandler.looper == Looper.getMainLooper()) {
                    effect.unavailable()
                    return true
                }
                val texture = TextureFrame(input.textureId, input.width, input.height,
                    frame.rotation, frame.timestampNs, input.nativeEglContext, input.transformMatrixArray.copyOf())
                // The observer callback need not have a current EGL context. Use the texture's GL thread.
                val output = ThreadUtils.invokeAtFrontUninterruptibly(conversionHandler,
                    java.util.concurrent.Callable { effect.process(texture) })
                if (output.discard) {
                    output.release()
                    return false
                }
                if (output.textureId <= 0) {
                    output.release()
                    return true
                }
                if (output.textureId != input.textureId) {
                    input.retain()
                    val replacement = TextureBuffer(input.eglBaseContext, input.width, input.height,
                        input.type, output.textureId, input.transformMatrix, conversionHandler,
                        converter, Runnable {
                            try { output.release() } finally { input.release() }
                        })
                    frame.replaceBuffer(replacement, frame.rotation, frame.timestampNs)
                } else output.release()
                return true
            }
        } }))
    override fun close() {
        listener = null
        engine.destroy()
    }
}
