package com.starxen.live.media

/** Borrowed GL_TEXTURE_2D on the capture thread; do not retain it or wait for the UI thread.
 * Transform includes texture orientation/mirroring. Output keeps dimensions and transform.
 * Output owns a lease until release is called by the adapter. Detach before releasing beauty.
 */
data class TextureFrame(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampNs: Long,
    val nativeEglContext: Long,
    val transform: FloatArray
)

interface TextureBeautyProcessor {
    /** Return the input texture for bypass. Invalid IDs fall back to the input frame.
     * A changed output must be independently owned; reusing one FBO across queued frames is unsafe.
     */
    fun process(frame: TextureFrame): BeautyTexture
    /** Called on the capture thread when the adapter cannot provide the required texture format. */
    fun unavailable()
}

data class BeautyTexture(val textureId: Int, val discard: Boolean = false, val release: () -> Unit = {})
