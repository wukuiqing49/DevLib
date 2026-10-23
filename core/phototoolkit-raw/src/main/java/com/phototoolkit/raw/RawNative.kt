package com.phototoolkit.raw

/**
 * LibRaw JNI entry point. It develops camera RAW data and exports an sRGB JPEG.
 * All paths must be absolute paths.
 */
object RawNative {
    init { System.loadLibrary("raw_jni") }

    /** Returns RAW camera and sensor information, or a descriptive error. */
    @JvmStatic external fun inspect(inputPath: String): String

    /** Returns structured RAW information as JSON, or a descriptive error. */
    @JvmStatic external fun inspectDetails(inputPath: String): String

    /** Extracts the embedded preview and writes a JPEG. Empty means success. */
    @JvmStatic external fun extractPreview(inputPath: String, outputPath: String): String

    /**
     * Develops a camera RAW file into JPEG. White balance mode: 0=camera,
     * 1=automatic, 2=raw. Exposure is expressed in EV. Quality is 1..100.
     * An empty result means success; a non-empty result is a LibRaw/libvips error.
     */
    @JvmStatic external fun developToJpeg(
        inputPath: String,
        outputPath: String,
        quality: Int,
        whiteBalanceMode: Int,
        autoBright: Boolean,
        exposureEv: Double,
        halfSize: Boolean
    ): String

    /** Output format: 0=JPEG, 1=TIFF, 2=WebP. */
    @JvmStatic external fun develop(
        inputPath: String,
        outputPath: String,
        outputFormat: Int,
        quality: Int,
        whiteBalanceMode: Int,
        autoBright: Boolean,
        exposureEv: Double,
        halfSize: Boolean,
        outputBits: Int,
        highlightMode: Int,
        noiseThreshold: Float,
    ): String
}
