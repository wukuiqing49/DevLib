package com.starxen.live.agora

import android.view.View
import io.agora.mediaplayer.Constants
import io.agora.mediaplayer.IMediaPlayer
import io.agora.mediaplayer.IMediaPlayerObserver
import io.agora.mediaplayer.IMediaPlayerAudioFrameObserver
import io.agora.base.AudioFrame
import io.agora.mediaplayer.data.CacheStatistics
import io.agora.mediaplayer.data.PlayerPlaybackStats
import io.agora.mediaplayer.data.PlayerUpdatedInfo
import io.agora.mediaplayer.data.SrcInfo

internal class AgoraCdnPlayer(
    private val player: IMediaPlayer,
    private val bindView: (IMediaPlayer, View?) -> Int,
    private val id: String,
    private val stateCallback: (String, Int, Int) -> Unit,
    private val eventCallback: (String, Int) -> Unit,
    private val firstFrameCallback: (String) -> Unit,
    private val volumeCallback: (String, Int) -> Unit,
    private val videoSizeCallback: (String, Int, Int) -> Unit,
    private val qualityCallback: (String, Long, Long, Long) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private val operationResult: (String, Int) -> Int
) {
    @Volatile private var released = false
    private var observingAudio = false
    private val audioDecoded = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastVolumeNanos = 0L
    private val audioObserver = object : IMediaPlayerAudioFrameObserver {
        override fun onFrame(frame: AudioFrame): AudioFrame {
            if (!released && audioDecoded.compareAndSet(false, true)) firstFrameCallback(id)
            val now = System.nanoTime()
            if (!released && now - lastVolumeNanos >= 200_000_000L) {
                lastVolumeNanos = now
                volumeCallback(id, pcmVolume(frame.buffer, frame.bytesPerSample))
            }
            return frame
        }
    }
    private val observer = object : IMediaPlayerObserver {
        override fun onPlayerStateChanged(state: Constants.MediaPlayerState, reason: Constants.MediaPlayerReason) {
            if (released) return
            if (state == Constants.MediaPlayerState.PLAYER_STATE_OPEN_COMPLETED) {
                dispatch {
                    if (!released) {
                        var hasVideo = false
                        var hasAudio = false
                        for (index in 0 until player.streamCount) {
                            val stream = player.getStreamInfo(index)
                            if (stream != null && stream.videoWidth > 0 && stream.videoHeight > 0) {
                                hasVideo = true
                                videoSizeCallback(id, stream.videoWidth, stream.videoHeight)
                            }
                            if (stream != null && stream.audioSampleRate > 0) hasAudio = true
                        }
                        if (!hasVideo && hasAudio && !observingAudio) {
                            val code = operationResult("cdnAudioObserver", player.registerAudioFrameObserver(audioObserver,
                                io.agora.rtc2.Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY))
                            if (code < 0) return@dispatch
                            observingAudio = true
                        }
                        operationResult("cdnPlay", player.play())
                    }
                }
            }
            stateCallback(id, Constants.MediaPlayerState.getValue(state), Constants.MediaPlayerReason.getValue(reason))
        }

        override fun onPlayerEvent(event: Constants.MediaPlayerEvent, elapsed: Long, message: String?) {
            if (!released) {
                eventCallback(id, Constants.MediaPlayerEvent.getValue(event))
                if (event == Constants.MediaPlayerEvent.PLAYER_EVENT_FIRST_DISPLAYED) firstFrameCallback(id)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPositionChanged(position: Long, timestamp: Long) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onMetaData(type: Constants.MediaPlayerMetadataType?, data: ByteArray?) {}
        override fun onPlayBufferUpdated(cached: Long) {}
        override fun onPreloadEvent(src: String?, event: Constants.MediaPlayerPreloadEvent?) {}
        override fun onAgoraCDNTokenWillExpire() {}
        override fun onPlayerSrcInfoChanged(from: SrcInfo?, to: SrcInfo?) {}
        override fun onPlayerInfoUpdated(info: PlayerUpdatedInfo?) {
            if (!released && info != null && info.videoWidth > 0 && info.videoHeight > 0) {
                videoSizeCallback(id, info.videoWidth.toInt(), info.videoHeight.toInt())
            }
        }
        override fun onPlayerCacheStats(stats: CacheStatistics?) {}
        override fun onPlayerPlaybackStats(stats: PlayerPlaybackStats?) {
            if (!released && stats != null) qualityCallback(id, stats.videoFps, stats.videoBitrate, stats.audioBitrate)
        }
        override fun onAudioVolumeIndication(volume: Int) {}
    }

    fun open(url: String, view: View?): Int {
        var code = operationResult("cdnRegisterObserver", player.registerPlayerObserver(observer))
        if (code < 0) return code
        if (view != null) {
            code = operationResult("cdnSetView", bindView(player, view))
            if (code < 0) return code
        }
        return operationResult("cdnOpen", player.open(url, 0))
    }

    fun setVolume(volume: Int): Int = player.adjustPlayoutVolume(volume)
    fun setView(view: View?): Int = bindView(player, view)

    fun destroy() {
        released = true
        if (observingAudio) {
            operationResult("cdnAudioObserver", player.registerAudioFrameObserver(null,
                io.agora.rtc2.Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY))
            observingAudio = false
        }
        operationResult("cdnUnregisterObserver", player.unRegisterPlayerObserver(observer))
        operationResult("cdnStop", player.stop())
        operationResult("cdnDetachView", bindView(player, null))
        operationResult("cdnDestroy", player.destroy())
    }
}

/** Read-only PCM16 sampling; never change the SDK buffer position or retain its memory. */
internal fun pcmVolume(buffer: java.nio.ByteBuffer?, bytesPerSample: Int): Int {
    if (buffer == null || bytesPerSample != 2 || buffer.remaining() < 2) return 0
    val samples = buffer.asReadOnlyBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var squareSum = 0.0
    var count = 0
    // Bound work on the audio callback thread even if the SDK supplies an unusually large frame.
    val end = samples.position() + minOf(samples.remaining() / 2, 4096) * 2
    for (offset in samples.position() until end step 2) {
        val value = samples.getShort(offset).toDouble()
        squareSum += value * value
        count++
    }
    return (kotlin.math.sqrt(squareSum / count) * 255.0 / 32768.0).toInt().coerceIn(0, 255)
}
