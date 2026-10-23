package com.starxen.live.agora

import io.agora.base.VideoFrame
import io.agora.rtc2.video.IVideoFrameObserver

/** Called synchronously on the SDK video thread. Do not retain the borrowed frame or block on UI. */
interface AgoraVideoFrameProcessor {
    fun process(sourceType: Int, frame: VideoFrame): Boolean
}

internal class AgoraVideoFrameObserver(processor: AgoraVideoFrameProcessor) : IVideoFrameObserver {
    private val processorLock = Any()
    private var processor: AgoraVideoFrameProcessor? = processor

    override fun onCaptureVideoFrame(sourceType: Int, frame: VideoFrame): Boolean = synchronized(processorLock) {
        processor?.process(sourceType, frame) ?: true
    }

    // Detaching waits for the current borrowed frame before the caller can release its beauty engine.
    fun release() = synchronized(processorLock) { processor = null }
    override fun onPreEncodeVideoFrame(sourceType: Int, frame: VideoFrame): Boolean = true
    override fun onMediaPlayerVideoFrame(frame: VideoFrame, playerId: Int): Boolean = true
    override fun onRenderVideoFrame(channelId: String?, uid: Int, frame: VideoFrame): Boolean = true
    override fun getVideoFrameProcessMode(): Int = IVideoFrameObserver.PROCESS_MODE_READ_WRITE
    override fun getVideoFormatPreference(): Int = IVideoFrameObserver.VIDEO_TEXTURE_2D
    override fun getRotationApplied(): Boolean = false
    override fun getMirrorApplied(): Boolean = false
    override fun getObservedFramePosition(): Int = IVideoFrameObserver.POSITION_POST_CAPTURER
}
