package com.starxen.live.agora

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.media.projection.MediaProjection
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.ScreenCaptureParameters
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.util.concurrent.atomic.AtomicReference

class AgoraLiveEngine(
    context: Context,
    listener: AgoraLiveListener
) {
    private var listener: AgoraLiveListener? = listener
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rtcEngine: RtcEngine? = null
    private var initializedAppId: String? = null
    @Volatile private var activeSession: AgoraLiveSession? = null
    private var screenCaptureStarted = false
    private var screenAudioEnabled = false
    private var leaving = false
    private var microphoneMuted = false
    private var videoConfig: LiveVideoConfig? = null
    private var localRenderMode = LiveRenderMode.FILL
    private var frontCamera = true
    private var previousRoleSession: AgoraLiveSession? = null
    private var videoFrameObserver: AgoraVideoFrameObserver? = null
    private var allPlaybackVolume = 100
    private var audioVolumeIndication: Pair<Int, Int>? = null
    private var audioRecording = false
    private val extraChannels = mutableMapOf<String, RtcConnection>()
    private val extraSessions = mutableMapOf<String, AgoraLiveSession>()
    private val extraChannelsReady = mutableSetOf<String>()
    private val cdnPlayers = mutableMapOf<String, AgoraCdnPlayer>()
    @Volatile private var generation = 0
    @Volatile private var listenerGeneration = 0
    private val extraChannelsLeaving = mutableSetOf<String>()
    private val rtmpUrls = mutableSetOf<String>()

    companion object {
        private val owner = AtomicReference<AgoraLiveEngine?>()
        @JvmStatic fun sdkVersion(): String = RtcEngine.getSdkVersion()
    }

    fun setListener(listener: AgoraLiveListener?) {
        checkMainThread()
        listenerGeneration++
        this.listener = listener
    }

    fun isInitialized(): Boolean = rtcEngine != null

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call Agora controls on the main thread" }
    }

    private fun result(operation: String, code: Int): Int {
        if (code < 0) postEvent {
            onOperationError(operation, code)
            onError(code, "$operation failed")
        }
        return code
    }

    @Synchronized
    fun initialize(appId: String) {
        checkMainThread()
        require(appId.isNotBlank()) { "Agora App ID is required" }
        val current = rtcEngine
        if (current != null) {
            check(initializedAppId == appId) { "An Agora engine cannot switch App IDs" }
            return
        }

        check(owner.compareAndSet(null, this)) { "Another AgoraLiveEngine owns the process RTC engine" }
        generation++
        val config = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = appId
            mEventHandler = createEventHandler()
        }
        rtcEngine = try {
            RtcEngine.create(config)
        } catch (error: Exception) {
            owner.compareAndSet(this, null)
            postEvent { onError(-1, error.message) }
            throw IllegalStateException("Failed to initialize Agora RTC", error)
        }
        initializedAppId = appId
        result("enableVideo", requireEngine().enableVideo())
    }

    @Synchronized
    fun join(session: AgoraLiveSession): Int {
        checkMainThread()
        check(activeSession == null && !leaving) { "Wait for Left before joining another channel" }
        check(extraChannels.isEmpty()) { "Wait for extra channels to leave before switching rooms" }
        require(session.channelId.isNotBlank()) { "Agora channel ID is required" }
        require(session.uid != 0) { "Agora UID must be a nonzero UInt32 bit pattern" }
        require(session.token.isNotBlank()) { "Agora token is required" }
        initialize(session.appId)

        val engine = requireEngine()
        val options = createMediaOptions(session)
        val modeResult = result("configureVideoMode", if (session.mode == LiveMode.VOICE) engine.disableVideo() else engine.enableVideo())
        if (modeResult < 0) return modeResult
        activeSession = session
        val code = result("joinChannel", engine.joinChannel(session.token, session.channelId, session.uid, options))
        if (code < 0) {
            activeSession = null
        }
        return code
    }

    @JvmOverloads
    fun startPublishing(audio: Boolean = true, video: Boolean = true): Int {
        val session = requireNotNull(activeSession) { "Join a channel before publishing" }
        check(!leaving)
        val updated = session.copy(role = LiveRole.HOST, publishAudio = audio, publishVideo = video)
        val code = result("startPublishing", requireEngine().updateChannelMediaOptions(createMediaOptions(updated)))
        if (code == 0) {
            if (session.role != updated.role) previousRoleSession = session
            activeSession = updated
            val extraCode = syncExtraCapture(screenCaptureStarted, screenAudioEnabled, audio, video)
            if (extraCode < 0) stopPublishing()
            return extraCode
        }
        return code
    }

    fun stopPublishing(): Int {
        val session = activeSession ?: return 0
        if (leaving) return 0
        val updated = session.copy(role = LiveRole.AUDIENCE, publishAudio = false, publishVideo = false)
        var code = result("stopPublishing", requireEngine().updateChannelMediaOptions(createMediaOptions(updated)))
        if (code == 0) {
            if (session.role != updated.role) previousRoleSession = session
            activeSession = updated
        } else {
            val left = result("leaveOnPublicationFailure", requireEngine().leaveChannel())
            if (left == 0) leaving = true
        }
        for ((channelId, connection) in extraChannels) {
            if (channelId in extraChannelsLeaving) continue
            val stopped = result("stopExtraPublishing", (requireEngine() as RtcEngineEx)
                .updateChannelMediaOptionsEx(ChannelMediaOptions().apply {
                    clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
                    publishCameraTrack = false
                    publishMicrophoneTrack = false
                    publishScreenCaptureVideo = false
                    publishScreenCaptureAudio = false
                }, connection))
            if (stopped != 0) {
                if (code == 0) code = stopped
                val left = result("leaveExtraChannel", (requireEngine() as RtcEngineEx).leaveChannelEx(connection))
                if (left == 0) extraChannelsLeaving.add(channelId)
            } else {
                extraSessions[channelId] = extraSessions[channelId]?.copy(
                    role = LiveRole.AUDIENCE, publishAudio = false, publishVideo = false) ?: continue
            }
        }
        if (screenCaptureStarted) {
            val stopped = result("stopScreenCapture", requireEngine().stopScreenCapture())
            if (stopped < 0) {
                if (code == 0) code = stopped
            } else {
                screenCaptureStarted = false
                screenAudioEnabled = false
            }
        }
        return code
    }

    fun stopPreview(): Int = result("stopPreview", requireEngine().stopPreview())

    fun detachLocalVideo(): Int = result("detachLocalVideo", requireEngine().setupLocalVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, 0)))

    @JvmOverloads
    fun attachLocalVideo(view: View, mirror: Boolean = true, mode: LiveRenderMode = LiveRenderMode.FILL): Int {
        val engine = requireEngine()
        val canvas = VideoCanvas(view, renderMode(mode), 0).apply {
            mirrorMode = if (mirror) Constants.VIDEO_MIRROR_MODE_ENABLED else Constants.VIDEO_MIRROR_MODE_DISABLED
        }
        val code = result("setupLocalVideo", engine.setupLocalVideo(canvas))
        if (code == 0) localRenderMode = mode
        return if (code < 0) code else result("startPreview", engine.startPreview())
    }

    @JvmOverloads
    fun attachRemoteVideo(uid: Int, view: View?, mode: LiveRenderMode = LiveRenderMode.FILL): Int {
        require(uid != 0) { "Agora UID must be a nonzero UInt32 bit pattern" }
        return result("setupRemoteVideo", requireEngine().setupRemoteVideo(VideoCanvas(view, renderMode(mode), uid).apply {
            // Mirroring is encoded by the publisher; never mirror it a second time on reception.
            mirrorMode = Constants.VIDEO_MIRROR_MODE_DISABLED
        }))
    }

    fun detachRemoteVideo(uid: Int): Int {
        require(uid != 0) { "Agora UID must be a nonzero UInt32 bit pattern" }
        return attachRemoteVideo(uid, null)
    }

    private fun renderMode(mode: LiveRenderMode): Int = if (mode == LiveRenderMode.FILL) VideoCanvas.RENDER_MODE_HIDDEN else VideoCanvas.RENDER_MODE_FIT

    fun setRemoteVideoMuted(uid: Int, muted: Boolean): Int {
        require(uid != 0)
        return result("muteRemoteVideoStream", requireEngine().muteRemoteVideoStream(uid, muted))
    }

    fun setRemotePlaybackVolume(uid: Int, volume: Int): Int {
        require(volume in 0..400 && uid != 0)
        return result("adjustUserPlaybackSignalVolume", requireEngine().adjustUserPlaybackSignalVolume(uid, volume))
    }

    @JvmOverloads
    fun startPlaying(uid: Int, view: View? = null, video: Boolean = true): Int {
        val engine = requireEngine()
        require(uid != 0)
        var code = result("subscribeAudio", engine.muteRemoteAudioStream(uid, false))
        if (code < 0) return code
        if (activeSession?.mode == LiveMode.VOICE) return code
        code = result("subscribeVideo", engine.muteRemoteVideoStream(uid, !video))
        if (code < 0) return code
        if (!video) return detachRemoteVideo(uid)
        return if (view == null) code else attachRemoteVideo(uid, view)
    }

    fun setPlaybackSubscription(audio: Boolean, video: Boolean): Int {
        val session = activeSession ?: return 0
        if (leaving) return 0
        val updated = session.copy(autoSubscribeAudio = audio, autoSubscribeVideo = video)
        val code = result("playbackSubscription", requireEngine().updateChannelMediaOptions(createMediaOptions(updated)))
        if (code == 0) activeSession = updated
        return code
    }

    fun stopPlaying(uid: Int): Int {
        val engine = requireEngine()
        require(uid != 0)
        val audio = result("unsubscribeAudio", engine.muteRemoteAudioStream(uid, true))
        if (activeSession?.mode == LiveMode.VOICE) return audio
        val video = result("unsubscribeVideo", engine.muteRemoteVideoStream(uid, true))
        val canvas = detachRemoteVideo(uid)
        return listOf(audio, video, canvas).firstOrNull { it < 0 } ?: 0
    }

    fun setLocalAudioMuted(muted: Boolean): Int = result("muteLocalAudioStream", requireEngine().muteLocalAudioStream(muted))

    fun muteMicrophone(muted: Boolean): Int {
        val code = result("muteRecordingSignal", requireEngine().muteRecordingSignal(muted))
        if (code == 0) microphoneMuted = muted
        return code
    }

    fun isMicrophoneMuted(): Boolean = microphoneMuted

    fun setLocalVideoMuted(muted: Boolean): Int = result("muteLocalVideoStream", requireEngine().muteLocalVideoStream(muted))

    fun setRemoteAudioMuted(uid: Int, muted: Boolean): Int {
        require(uid != 0) { "Agora UID must be a nonzero UInt32 bit pattern" }
        return result("muteRemoteAudioStream", requireEngine().muteRemoteAudioStream(uid, muted))
    }

    fun setCameraEnabled(enabled: Boolean): Int = result("enableLocalVideo", requireEngine().enableLocalVideo(enabled))

    fun switchCamera(): Int {
        val code = result("switchCamera", requireEngine().switchCamera())
        if (code == 0) frontCamera = !frontCamera
        return code
    }

    fun useFrontCamera(front: Boolean): Int {
        val direction = if (front) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR
        val configuration = videoConfig?.let {
            CameraCapturerConfiguration(direction, CameraCapturerConfiguration.CaptureFormat(it.width, it.height, it.fps))
        } ?: CameraCapturerConfiguration(direction)
        val code = result("setCameraCapturerConfiguration", requireEngine().setCameraCapturerConfiguration(configuration))
        if (code == 0) frontCamera = front
        return code
    }

    fun isTorchSupported(): Boolean = !frontCamera && requireEngine().isCameraTorchSupported
    fun setTorchEnabled(enabled: Boolean): Int {
        if (enabled && frontCamera) return -1
        return result("setCameraTorchOn", requireEngine().setCameraTorchOn(enabled))
    }
    fun setPreviewMirror(enabled: Boolean): Int = result("setLocalRenderMode", requireEngine().setLocalRenderMode(renderMode(localRenderMode), if (enabled) Constants.VIDEO_MIRROR_MODE_ENABLED else Constants.VIDEO_MIRROR_MODE_DISABLED))

    fun setVideoEncoder(width: Int, height: Int, fps: Int, bitrateKbps: Int): Int = setVideoConfig(LiveVideoConfig(width, height, fps, bitrateKbps))

    fun getVideoConfig(): LiveVideoConfig? = videoConfig

    fun setVideoConfig(config: LiveVideoConfig): Int {
        val engine = requireEngine()
        val direction = if (frontCamera) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR
        val captureConfig = CameraCapturerConfiguration(direction, CameraCapturerConfiguration.CaptureFormat(config.width, config.height, config.fps))
        val captureCode = result("setCameraCapturerConfiguration", engine.setCameraCapturerConfiguration(captureConfig))
        if (captureCode < 0) return captureCode
        val configuration = VideoEncoderConfiguration().apply {
            dimensions = VideoEncoderConfiguration.VideoDimensions(config.width, config.height)
            frameRate = config.fps
            bitrate = config.bitrateKbps
            mirrorMode = if (config.mirrorPublishedVideo) VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_ENABLED else VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
            // CDN containers may discard RTC rotation metadata; encode the requested orientation.
            orientationMode = when {
                config.height > config.width -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
                config.width > config.height -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE
                else -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
            }
            degradationPrefer = VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_FRAMERATE
        }
        val code = result("setVideoEncoderConfiguration", engine.setVideoEncoderConfiguration(configuration))
        if (code == 0) videoConfig = config
        return code
    }

    @JvmOverloads
    fun setAudioProfile(profile: LiveAudioProfile = LiveAudioProfile.HIGH_QUALITY, chatRoom: Boolean = true): Int {
        val engine = requireEngine()
        val scenario = if (chatRoom) Constants.AUDIO_SCENARIO_CHATROOM else Constants.AUDIO_SCENARIO_DEFAULT
        val sdkProfile = when (profile) {
            LiveAudioProfile.SPEECH -> Constants.AUDIO_PROFILE_SPEECH_STANDARD
            LiveAudioProfile.MUSIC -> Constants.AUDIO_PROFILE_MUSIC_STANDARD
            LiveAudioProfile.MUSIC_STEREO -> Constants.AUDIO_PROFILE_MUSIC_STANDARD_STEREO
            LiveAudioProfile.HIGH_QUALITY -> Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY
            LiveAudioProfile.HIGH_QUALITY_STEREO -> Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY_STEREO
        }
        val code = result("setAudioScenario", engine.setAudioScenario(scenario))
        return if (code < 0) code else result("setAudioProfile", engine.setAudioProfile(sdkProfile))
    }

    fun setSpeakerphoneEnabled(enabled: Boolean): Int = result("setEnableSpeakerphone", requireEngine().setEnableSpeakerphone(enabled))

    fun setRecordingVolume(volume: Int): Int {
        require(volume in 0..400)
        return result("adjustRecordingSignalVolume", requireEngine().adjustRecordingSignalVolume(volume))
    }

    fun setPlaybackVolume(volume: Int): Int {
        require(volume in 0..400)
        return result("adjustPlaybackSignalVolume", requireEngine().adjustPlaybackSignalVolume(volume))
    }

    fun setAllPlaybackVolume(volume: Int): Int {
        require(volume in 0..100)
        val rtcCode = setPlaybackVolume(volume)
        if (rtcCode < 0) return rtcCode
        allPlaybackVolume = volume
        var firstError = 0
        for (player in cdnPlayers.values) {
            val code = result("cdnPlaybackVolume", player.setVolume(volume))
            if (code < 0 && firstError == 0) firstError = code
        }
        return firstError
    }

    @JvmOverloads
    fun enableAudioVolumeIndication(intervalMs: Int = 800, smooth: Int = 3): Int {
        require(intervalMs > 0 && smooth in 0..10)
        val configuration = intervalMs to smooth
        if (audioVolumeIndication == configuration) return 0
        // Volume metering is optional UI telemetry; return failures without terminating RTC.
        val code = requireEngine().enableAudioVolumeIndication(intervalMs, smooth, true)
        if (code == 0) audioVolumeIndication = configuration
        return code
    }

    fun stopAudioVolumeIndication(): Int {
        if (audioVolumeIndication == null) return 0
        val code = requireEngine().enableAudioVolumeIndication(-1, 3, false)
        if (code == 0) audioVolumeIndication = null
        return code
    }

    fun enableFaceDetection(enabled: Boolean): Int = result("enableFaceDetection", requireEngine().enableFaceDetection(enabled))

    fun renewToken(token: String): Int {
        require(token.isNotBlank()) { "Agora token is required" }
        return result("renewToken", requireEngine().renewToken(token))
    }

    @JvmOverloads
    fun startScreenShare(config: LiveScreenConfig = LiveScreenConfig()): Int {
        val engine = requireEngine()
        check(activeSession?.role == LiveRole.HOST && activeSession?.publishVideo == true) {
            "Screen capture requires host video publication"
        }
        check(!screenCaptureStarted && !leaving)
        val parameters = ScreenCaptureParameters().apply {
            captureVideo = true
            captureAudio = config.captureDeviceAudio
            videoCaptureParameters.width = config.width
            videoCaptureParameters.height = config.height
            videoCaptureParameters.framerate = config.fps
            audioCaptureParameters.captureSignalVolume = config.deviceAudioVolume
        }
        val code = result("startScreenCapture", engine.startScreenCapture(parameters))
        if (code < 0) return code
        screenCaptureStarted = true
        screenAudioEnabled = config.captureDeviceAudio

        val session = requireNotNull(activeSession)
        val options = createMediaOptions(session).apply {
            publishCameraTrack = false
            publishScreenCaptureVideo = true
            publishScreenCaptureAudio = session.publishAudio && config.captureDeviceAudio
        }
        val optionsResult = engine.updateChannelMediaOptions(options)
        if (optionsResult < 0) {
            stopPublishing()
            return result("publishScreenCapture", optionsResult)
        }
        val extraResult = syncExtraCapture(true, config.captureDeviceAudio)
        if (extraResult < 0) stopPublishing()
        return extraResult
    }

    fun stopScreenShare(): Int {
        val engine = requireEngine()
        if (!screenCaptureStarted) return 0
        val session = requireNotNull(activeSession)
        val options = createMediaOptions(session).apply {
            publishScreenCaptureVideo = false
            publishScreenCaptureAudio = false
            publishCameraTrack = session.role == LiveRole.HOST && session.publishVideo && session.mode == LiveMode.VIDEO
        }
        val code = result("unpublishScreenCapture", engine.updateChannelMediaOptions(options))
        val extraCode = syncExtraCapture(false, false)
        val stopped = result("stopScreenCapture", engine.stopScreenCapture())
        if (stopped == 0) {
            screenCaptureStarted = false
            screenAudioEnabled = false
        }
        return if (code < 0) code else if (extraCode < 0) extraCode else stopped
    }

    fun setScreenProjection(projection: MediaProjection): Int = result("setExternalMediaProjection", requireEngine().setExternalMediaProjection(projection))

    fun addPublishCdnUrl(url: String): Int {
        require(url.startsWith("rtmp://") || url.startsWith("rtmps://"))
        check(activeSession?.role == LiveRole.HOST && !leaving)
        val code = result("startRtmpStreamWithoutTranscoding", requireEngine().startRtmpStreamWithoutTranscoding(url))
        if (code == 0) rtmpUrls.add(url)
        return code
    }

    fun removePublishCdnUrl(url: String): Int {
        val code = result("stopRtmpStream", requireEngine().stopRtmpStream(url))
        if (code == 0) rtmpUrls.remove(url)
        return code
    }

    fun networkTimeMs(): Long = requireEngine().ntpWallTimeInMs
    fun uploadLog(): String = requireEngine().uploadLogFile().orEmpty()

    fun setVideoFrameProcessor(processor: AgoraVideoFrameProcessor?): Int {
        val observer = processor?.let { AgoraVideoFrameObserver(it) }
        val code = result("registerVideoFrameObserver", requireEngine().registerVideoFrameObserver(observer))
        if (code == 0) {
            videoFrameObserver?.release()
            videoFrameObserver = observer
        } else {
            observer?.release()
        }
        return code
    }

    @JvmOverloads
    fun startCdnPlayback(playerId: String, url: String, view: View? = null): Int {
        require(playerId.isNotBlank() && url.isNotBlank())
        val engine = requireEngine()
        stopCdnPlayback(playerId)
        lateinit var player: AgoraCdnPlayer
        player = AgoraCdnPlayer(engine.createMediaPlayer(), { mediaPlayer, target ->
            engine.setupLocalVideo(VideoCanvas(target, VideoCanvas.RENDER_MODE_HIDDEN, 0).apply {
                sourceType = Constants.VIDEO_SOURCE_MEDIA_PLAYER
                mediaPlayerId = mediaPlayer.mediaPlayerId
                mirrorMode = Constants.VIDEO_MIRROR_MODE_DISABLED
            })
        }, playerId,
            { id, state, reason -> postEvent { if (cdnPlayers[id] === player) onCdnPlayerState(id, state, reason) } },
            { id, event -> postEvent { if (cdnPlayers[id] === player) onCdnPlayerEvent(id, event) } },
            { id -> postEvent { if (cdnPlayers[id] === player) onCdnFirstFrame(id) } },
            { id, level -> postEvent { if (cdnPlayers[id] === player) onCdnVolume(id, level) } },
            { id, width, height -> postEvent { if (cdnPlayers[id] === player) onCdnVideoSize(id, width, height) } },
            { id, fps, videoBitrate, audioBitrate -> postEvent { if (cdnPlayers[id] === player) onCdnQuality(id, fps, videoBitrate, audioBitrate) } },
            { action -> mainHandler.post { if (cdnPlayers[playerId] === player) action() } },
            { operation, code -> result(operation, code) })
        cdnPlayers[playerId] = player
        val volumeCode = result("cdnPlaybackVolume", player.setVolume(allPlaybackVolume))
        if (volumeCode < 0) {
            stopCdnPlayback(playerId)
            return volumeCode
        }
        val code = player.open(url, view)
        if (code < 0) stopCdnPlayback(playerId)
        return code
    }

    fun setCdnPlaybackVolume(playerId: String, volume: Int): Int {
        checkMainThread()
        require(volume in 0..100)
        return result("cdnPlaybackVolume", requireNotNull(cdnPlayers[playerId]).setVolume(volume))
    }

    fun attachCdnVideo(playerId: String, view: View?): Int {
        checkMainThread()
        return result("cdnSetView", requireNotNull(cdnPlayers[playerId]).setView(view))
    }

    fun stopCdnPlayback(playerId: String) {
        checkMainThread()
        cdnPlayers.remove(playerId)?.destroy()
    }

    fun joinExtraChannel(session: AgoraLiveSession): Int {
        val engine = requireEngine() as RtcEngineEx
        check(activeSession != null && !leaving) { "Join the main channel before joining PK channels" }
        require(session.channelId.isNotBlank() && session.uid != 0 && session.token.isNotBlank())
        check(session.appId == initializedAppId && (session.role == LiveRole.AUDIENCE ||
            activeSession?.role == LiveRole.HOST && session.uid == activeSession?.uid)) { "PK publication requires the active host identity" }
        check(session.channelId != activeSession?.channelId && !extraChannels.containsKey(session.channelId))
        val connection = RtcConnection(session.channelId, session.uid)
        extraChannels[session.channelId] = connection
        extraSessions[session.channelId] = session
        val code = result("joinChannelEx", engine.joinChannelEx(session.token, connection, createMediaOptions(session), createEventHandler(session.channelId, connection)))
        if (code < 0) {
            extraChannels.remove(session.channelId)
            extraSessions.remove(session.channelId)
        }
        return code
    }

    fun updateExtraChannel(session: AgoraLiveSession): Int {
        checkMainThread()
        check(session.appId == initializedAppId && (session.role == LiveRole.AUDIENCE ||
            activeSession?.role == LiveRole.HOST && session.uid == activeSession?.uid))
        check(session.channelId !in extraChannelsLeaving)
        requireNotNull(extraChannels[session.channelId])
        if (session.channelId !in extraChannelsReady) {
            extraSessions[session.channelId] = session
            return 0
        }
        val code = result("updateChannelMediaOptionsEx", (requireEngine() as RtcEngineEx)
            .updateChannelMediaOptionsEx(createMediaOptions(session).apply { token = session.token },
                requireNotNull(extraChannels[session.channelId])))
        if (code == 0) extraSessions[session.channelId] = session
        return code
    }

    fun leaveExtraChannel(channelId: String): Int {
        val engine = requireEngine() as RtcEngineEx
        val connection = extraChannels[channelId] ?: return 0
        if (channelId in extraChannelsLeaving) return 0
        val code = result("leaveChannelEx", engine.leaveChannelEx(connection))
        if (code == 0) extraChannelsLeaving.add(channelId)
        return code
    }

    @JvmOverloads
    fun attachExtraChannelVideo(channelId: String, uid: Int, view: View?, mode: LiveRenderMode = LiveRenderMode.FILL): Int {
        require(uid != 0)
        val engine = requireEngine() as RtcEngineEx
        val connection = requireNotNull(extraChannels[channelId])
        val code = result("setupRemoteVideoEx", engine.setupRemoteVideoEx(VideoCanvas(view, renderMode(mode), uid).apply {
            mirrorMode = Constants.VIDEO_MIRROR_MODE_DISABLED
        }, connection))
        if (code != 0) return code
        return result("muteRemoteVideoStreamEx", engine.muteRemoteVideoStreamEx(uid, view == null, connection))
    }

    fun setExtraChannelAudioMuted(channelId: String, uid: Int, muted: Boolean): Int {
        require(uid != 0)
        return result("muteRemoteAudioStreamEx", (requireEngine() as RtcEngineEx).muteRemoteAudioStreamEx(uid, muted, requireNotNull(extraChannels[channelId])))
    }

    fun setExtraChannelVideoMuted(channelId: String, uid: Int, muted: Boolean): Int {
        require(uid != 0)
        return result("muteRemoteVideoStreamEx", (requireEngine() as RtcEngineEx).muteRemoteVideoStreamEx(uid, muted, requireNotNull(extraChannels[channelId])))
    }

    fun setExtraChannelPlaybackVolume(channelId: String, uid: Int, volume: Int): Int {
        require(uid != 0 && volume in 0..400)
        return result("adjustUserPlaybackSignalVolumeEx", (requireEngine() as RtcEngineEx).adjustUserPlaybackSignalVolumeEx(uid, volume, requireNotNull(extraChannels[channelId])))
    }

    fun renewExtraChannelToken(channelId: String, token: String): Int {
        require(token.isNotBlank())
        checkMainThread()
        val session = requireNotNull(extraSessions[channelId])
        return updateExtraChannel(session.copy(token = token))
    }

    @Synchronized
    fun leave(): Int {
        checkMainThread()
        val engine = rtcEngine ?: return 0
        stopAudioRecording()
        if (leaving) return 0
        cdnPlayers.keys.toList().forEach(::stopCdnPlayback)
        rtmpUrls.toList().forEach(::removePublishCdnUrl)
        extraChannels.keys.toList().forEach(::leaveExtraChannel)
        if (screenCaptureStarted) {
            val stopped = result("stopScreenCaptureOnLeave", engine.stopScreenCapture())
            if (stopped < 0) return stopped
            screenCaptureStarted = false
            screenAudioEnabled = false
        }
        result("stopPreview", engine.stopPreview())
        result("detachLocalVideo", engine.setupLocalVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, 0)))
        if (activeSession == null) return 0
        val code = result("leaveChannel", engine.leaveChannel())
        if (code == 0) leaving = true
        return code
    }

    @Synchronized
    fun destroy() {
        checkMainThread()
        if (rtcEngine == null) return
        leave()
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        rtcEngine?.registerVideoFrameObserver(null)
        videoFrameObserver?.release()
        videoFrameObserver = null
        allPlaybackVolume = 100
        audioVolumeIndication = null
        audioRecording = false
        rtcEngine = null
        initializedAppId = null
        activeSession = null
        extraChannels.clear()
        extraSessions.clear()
        extraChannelsReady.clear()
        extraChannelsLeaving.clear()
        rtmpUrls.clear()
        leaving = false
        screenCaptureStarted = false
        screenAudioEnabled = false
        microphoneMuted = false
        videoConfig = null
        previousRoleSession = null
        localRenderMode = LiveRenderMode.FILL
        frontCamera = true
        listener = null
        RtcEngine.destroy()
        owner.compareAndSet(this, null)
    }

    fun startAudioRecording(path: String): Int {
        val engine = requireEngine()
        if (audioRecording || activeSession?.role != LiveRole.HOST || leaving) return -1
        // Auxiliary recording failures are returned to the recorder, not emitted as live-room failures.
        val code = engine.startAudioRecording(io.agora.rtc2.internal.AudioRecordingConfiguration().apply {
            filePath = path
            sampleRate = 48000
            codec = true
            fileRecordOption = Constants.AUDIO_FILE_RECORDING_MIXED
            quality = Constants.AUDIO_RECORDING_QUALITY_HIGH
            recordingChannel = 1
        })
        if (code == 0) audioRecording = true
        return code
    }

    fun stopAudioRecording(): Int {
        checkMainThread()
        if (!audioRecording) return 0
        val code = rtcEngine?.stopAudioRecording() ?: 0
        if (code == 0) audioRecording = false
        return code
    }

    private fun requireEngine(): RtcEngine {
        checkMainThread()
        return rtcEngine ?: throw IllegalStateException("Initialize Agora before using media APIs")
    }

    private fun createMediaOptions(session: AgoraLiveSession) = ChannelMediaOptions().apply {
        channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        clientRoleType = if (session.role == LiveRole.HOST) {
            Constants.CLIENT_ROLE_BROADCASTER
        } else {
            Constants.CLIENT_ROLE_AUDIENCE
        }
        publishMicrophoneTrack = session.role == LiveRole.HOST && session.publishAudio
        val host = session.role == LiveRole.HOST
        publishCameraTrack = host && session.publishVideo && session.mode == LiveMode.VIDEO && !screenCaptureStarted
        publishScreenCaptureVideo = host && session.publishVideo && screenCaptureStarted
        publishScreenCaptureAudio = host && session.publishAudio && screenCaptureStarted && screenAudioEnabled
        autoSubscribeAudio = session.autoSubscribeAudio
        autoSubscribeVideo = session.autoSubscribeVideo && session.mode != LiveMode.VOICE
        if (session.role == LiveRole.AUDIENCE) {
            audienceLatencyLevel = if (session.lowLatencyAudience) {
                Constants.AUDIENCE_LATENCY_LEVEL_ULTRA_LOW_LATENCY
            } else {
                Constants.AUDIENCE_LATENCY_LEVEL_LOW_LATENCY
            }
        }
    }

    private fun syncExtraCapture(screen: Boolean, screenAudio: Boolean,
        audio: Boolean? = null, video: Boolean? = null): Int {
        val engine = requireEngine() as RtcEngineEx
        var firstError = 0
        for ((channel, connection) in extraChannels) {
            if (channel in extraChannelsLeaving) continue
            val session = extraSessions[channel] ?: continue
            if (session.role != LiveRole.HOST) continue
            val updated = session.copy(publishAudio = audio ?: session.publishAudio,
                publishVideo = video ?: session.publishVideo)
            if (channel !in extraChannelsReady) {
                extraSessions[channel] = updated
                continue
            }
            val code = result("syncExtraCapture", engine.updateChannelMediaOptionsEx(
                createMediaOptions(updated).apply {
                    publishCameraTrack = updated.publishVideo && updated.mode == LiveMode.VIDEO && !screen
                    publishScreenCaptureVideo = updated.publishVideo && screen
                    publishScreenCaptureAudio = updated.publishAudio && screen && screenAudio
                }, connection))
            if (code == 0) extraSessions[channel] = updated
            else if (firstError == 0) firstError = code
        }
        return firstError
    }

    private fun postEvent(expected: Int = generation, action: AgoraLiveListener.() -> Unit) {
        val expectedListener = listenerGeneration
        mainHandler.post { if (expected == generation && expectedListener == listenerGeneration) listener?.action() }
    }

    private fun createEventHandler(extraChannelId: String? = null, extraConnection: RtcConnection? = null) = object : IRtcEngineEventHandler() {
        private val handlerGeneration = generation
        private fun post(action: AgoraLiveListener.() -> Unit) {
            postEvent(handlerGeneration, action)
        }

        private fun emit(event: AgoraLiveEvent) {
            val channel = extraChannelId ?: activeSession?.channelId.orEmpty()
            post {
                if (extraChannelId != null) {
                    if (extraChannels[channel] === extraConnection) onEvent(channel, event)
                } else if (channel == activeSession?.channelId.orEmpty()) {
                    onEvent(channel, event)
                }
            }
        }

        override fun onRejoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) { emit(AgoraLiveEvent.Joined(uid, true)) }

        override fun onLeaveChannel(stats: RtcStats?) {
            mainHandler.post {
                if (handlerGeneration != generation) return@post
                if (extraChannelId == null) {
                    val channel = activeSession?.channelId.orEmpty()
                    extraChannels.keys.toList().forEach(::leaveExtraChannel)
                    if (screenCaptureStarted && result("stopScreenCaptureOnMainLeft", requireEngine().stopScreenCapture()) == 0) {
                        screenCaptureStarted = false
                        screenAudioEnabled = false
                    }
                    activeSession = null
                    previousRoleSession = null
                    leaving = false
                    listener?.onEvent(channel, AgoraLiveEvent.Left)
                } else if (extraChannels[extraChannelId] === extraConnection) {
                    extraChannels.remove(extraChannelId)
                    extraSessions.remove(extraChannelId)
                    extraChannelsReady.remove(extraChannelId)
                    extraChannelsLeaving.remove(extraChannelId)
                    listener?.onEvent(extraChannelId, AgoraLiveEvent.Left)
                }
            }
        }

        override fun onRequestToken() { emit(AgoraLiveEvent.TokenRequired) }
        override fun onRenewTokenResult(token: String?, code: Constants.RenewTokenErrorCode?) {
            if (code != null) emit(AgoraLiveEvent.TokenRenewed(Constants.RenewTokenErrorCode.getValue(code)))
        }

        override fun onUploadLogResult(requestId: String?, success: Boolean, reason: Int) {
            if (extraChannelId == null) post { onLogUploadResult(requestId.orEmpty(), success, reason) }
        }
        override fun onClientRoleChanged(oldRole: Int, newRole: Int, options: io.agora.rtc2.ClientRoleOptions?) {
            mainHandler.post {
                if (handlerGeneration != generation || extraChannelId != null) return@post
                val session = activeSession ?: return@post
                val role = if (newRole == Constants.CLIENT_ROLE_BROADCASTER) LiveRole.HOST else LiveRole.AUDIENCE
                activeSession = session.copy(role = role)
                previousRoleSession = null
                listener?.onEvent(session.channelId, AgoraLiveEvent.RoleChanged(role))
            }
        }

        override fun onClientRoleChangeFailed(reason: Int, currentRole: Int) {
            mainHandler.post {
                if (handlerGeneration != generation) return@post
                if (extraChannelId != null) {
                    if (extraChannels[extraChannelId] === extraConnection && extraChannelId !in extraChannelsLeaving)
                        listener?.onEvent(extraChannelId, AgoraLiveEvent.RoleChangeFailed(reason))
                    return@post
                }
                val session = activeSession ?: return@post
                val role = if (currentRole == Constants.CLIENT_ROLE_BROADCASTER) LiveRole.HOST else LiveRole.AUDIENCE
                val previous = previousRoleSession?.takeIf { it.role == role } ?: session
                activeSession = previous.copy(role = role)
                previousRoleSession = null
                listener?.onEvent(session.channelId, AgoraLiveEvent.RoleChangeFailed(reason))
            }
        }
        override fun onAudioPublishStateChanged(channel: String?, oldState: Int, newState: Int, elapsed: Int) { emit(AgoraLiveEvent.PublishState(false, newState, oldState)) }
        override fun onVideoPublishStateChanged(source: Constants.VideoSourceType?, channel: String?, oldState: Int, newState: Int, elapsed: Int) { emit(AgoraLiveEvent.PublishState(true, newState, oldState)) }
        override fun onAudioSubscribeStateChanged(channel: String?, uid: Int, oldState: Int, newState: Int, elapsed: Int) { emit(AgoraLiveEvent.SubscribeState(uid, false, newState, oldState)) }
        override fun onVideoSubscribeStateChanged(channel: String?, uid: Int, oldState: Int, newState: Int, elapsed: Int) { emit(AgoraLiveEvent.SubscribeState(uid, true, newState, oldState)) }
        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) { emit(AgoraLiveEvent.RemoteMediaState(uid, false, state, reason)) }
        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) { emit(AgoraLiveEvent.RemoteMediaState(uid, true, state, reason)) }
        override fun onLocalAudioStateChanged(state: Int, reason: Int) { emit(AgoraLiveEvent.LocalMediaState(false, state, reason)) }
        override fun onLocalVideoStateChanged(source: Constants.VideoSourceType?, state: Int, reason: Int) { emit(AgoraLiveEvent.LocalMediaState(true, state, reason)) }
        override fun onVideoSizeChanged(source: Constants.VideoSourceType?, uid: Int, width: Int, height: Int, rotation: Int) { emit(AgoraLiveEvent.VideoSize(uid, width, height, rotation)) }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onFirstRemoteAudioFrame(uid: Int, elapsed: Int) { emit(AgoraLiveEvent.FirstFrame(uid, false)) }
        override fun onFirstLocalAudioFramePublished(elapsed: Int) { emit(AgoraLiveEvent.FirstFrame(0, false)) }
        override fun onFirstLocalVideoFramePublished(source: Constants.VideoSourceType?, elapsed: Int) { emit(AgoraLiveEvent.FirstFrame(0, true)) }
        override fun onRemoteVideoStats(stats: RemoteVideoStats?) { stats?.let { emit(AgoraLiveEvent.VideoQuality(it.uid, it.receivedBitrate, it.decoderOutputFrameRate, it.rendererOutputFrameRate, it.packetLossRate, it.width, it.height)) } }
        override fun onRemoteAudioStats(stats: RemoteAudioStats?) { stats?.let { emit(AgoraLiveEvent.AudioQuality(it.uid, it.receivedBitrate, it.audioLossRate, it.networkTransportDelay)) } }
        override fun onLocalVideoStats(source: Constants.VideoSourceType?, stats: LocalVideoStats?) { stats?.let { emit(AgoraLiveEvent.VideoQuality(0, it.sentBitrate, it.encoderOutputFrameRate, it.rendererOutputFrameRate, it.txPacketLossRate, it.encodedFrameWidth, it.encodedFrameHeight)) } }
        override fun onLocalAudioStats(stats: LocalAudioStats?) { stats?.let { emit(AgoraLiveEvent.AudioQuality(0, it.sentBitrate, it.txPacketLossRate, it.audioDeviceDelay)) } }
        override fun onRtmpStreamingStateChanged(url: String?, state: Int, error: Int) { emit(AgoraLiveEvent.RtmpState(url.orEmpty(), state, error)) }
        override fun onPermissionError(permission: Int) { emit(AgoraLiveEvent.PermissionError(permission)) }
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            if (extraChannelId != null) mainHandler.post {
                if (handlerGeneration != generation || extraChannels[extraChannelId] !== extraConnection ||
                    extraChannelId in extraChannelsLeaving) return@post
                if (activeSession == null || leaving) {
                    leaveExtraChannel(extraChannelId)
                    return@post
                }
                extraChannelsReady.add(extraChannelId)
                extraSessions[extraChannelId]?.let { updateExtraChannel(it) }
            }
            emit(AgoraLiveEvent.Joined(uid, false))
            if (extraChannelId == null) post { onJoined(channel.orEmpty(), uid) }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            emit(AgoraLiveEvent.RemoteUser(uid, true))
            if (extraChannelId == null) post { onRemoteUserJoined(uid) }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            emit(AgoraLiveEvent.RemoteUser(uid, false, reason))
            if (extraChannelId == null) post { onRemoteUserLeft(uid) }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int, elapsed: Int) {
            emit(AgoraLiveEvent.FirstFrame(uid, true))
            if (extraChannelId == null) post { onFirstRemoteVideoFrame(uid, width, height) }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            emit(AgoraLiveEvent.Connection(state, reason))
            if (extraChannelId == null) post { onConnectionStateChanged(state, reason) }
        }

        override fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {
            emit(AgoraLiveEvent.NetworkQuality(uid, txQuality, rxQuality))
            if (extraChannelId == null) post { onNetworkQuality(uid, txQuality, rxQuality) }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            emit(AgoraLiveEvent.TokenRequired)
            if (extraChannelId == null) post { onTokenWillExpire("") }
        }

        override fun onAudioVolumeIndication(speakers: Array<AudioVolumeInfo>?, totalVolume: Int) {
            val values = speakers.orEmpty().map { LiveSpeakerVolume(it.uid, it.volume) }
            emit(AgoraLiveEvent.AudioVolumes(values, totalVolume))
            if (extraChannelId == null) post { onAudioVolumes(values, totalVolume) }
        }

        override fun onFacePositionChanged(imageWidth: Int, imageHeight: Int, faces: Array<AgoraFacePositionInfo>?) {
            if (extraChannelId == null) post { onFaceCountChanged(faces?.size ?: 0) }
        }

        override fun onError(err: Int) {
            emit(AgoraLiveEvent.Error(err))
            if (extraChannelId == null) post { onError(err, "Agora RTC error") }
        }
    }
}
