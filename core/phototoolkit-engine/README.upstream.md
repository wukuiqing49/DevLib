# PhotoToolkit Engine

`phototoolkit-engine` is the reusable Android image-processing module. It
packages the libvips runtime, the JNI bridge, and all runtime shared libraries
needed to process JPEG, PNG, WebP, TIFF, and HEIC images locally.

## Supported Formats

Input formats are JPEG, PNG, WebP, TIFF, and HEIC/HEIF. Processed images are
exported as JPEG, PNG, WebP, or TIFF. HEIC is decode-only: this module does not ship
an HEVC encoder and never exports HEIC files.

The module exposes `VipsNative` for native operations such as compression,
resize, crop, format conversion, metadata stripping, and image compositing.
All paths passed to the JNI API must be application-readable absolute paths.

## ID Photo Processing

`IdPhotoProcessor` combines ML Kit face detection, the on-demand MODNet
portrait mask, and libvips compositing to generate a single-person ID photo.
It normalizes EXIF orientation, rejects zero or multiple faces, requires both
eyes to be visible, and rejects excessive yaw or roll before producing output.

The processor does not include UI, image picking, or manual face adjustment.
Call it from a worker thread with application-readable file paths:

```kotlin
val result = IdPhotoProcessor.create(
    context,
    IdPhotoRequest(
        inputPath = sourceFile.absolutePath,
        outputPath = outputFile.absolutePath,
        spec = IdPhotoSpec.STANDARD_413_X_591,
        backgroundColor = IdPhotoColor.BLUE
    )
)
```

`IdPhotoProcessor.createPrintSheet()` arranges a generated ID photo in a
caller-defined grid on a white sheet. The caller owns print dimensions,
resolution, export destination, and any legal validation for a local document
photo specification.

## Native Runtime

Android runtime support is limited to `armeabi-v7a` and `arm64-v8a`.
Desktop/emulator `x86` and `x86_64` ABIs are not built or packaged.

Each ABI directory under `src/main/jniLibs` contains the complete runtime
dependency closure for `libvips.so`, including `libheif.so` and
`libde265.so`. All 64-bit libraries are built and validated with 16 KB ELF
alignment. The 32-bit `armeabi-v7a` runtime keeps the alignment supplied by
the official NDK because 16 KB Android devices use 64-bit ABIs.

The module deliberately does not include FFTW, Exiv2, x265, AVIF codecs, or
libimagequant. This keeps the shipped feature set aligned with the Batch Photo
plan and avoids GPL-only dependencies. HEIC/HEIF decoding uses libheif and
libde265; commercial release still requires an independent review of HEVC
patent obligations for the intended distribution regions.

## Build

Build one ABI and synchronize its runtime into this module:

```powershell
.\phototoolkit-engine\build-native.ps1 -Abi arm64-v8a -Rebuild
```

Build and synchronize all supported ABIs:

```powershell
.\phototoolkit-engine\build-native.ps1 -AllAbis -Rebuild
```

The build requires Android NDK r27 or newer, CMake, Meson, Ninja, and Conan
2. The script applies `-Wl,-z,max-page-size=16384` to native dependencies,
then uses `llvm-readelf` to verify every packaged 64-bit JNI library.

Build the distributable AAR:

```powershell
.\gradlew.bat :phototoolkit-engine:assembleRelease
```

Run the project-wide native build when an APK-level check is required:

```powershell
.\build-all-native.ps1 -Rebuild
```

That command verifies every merged APK shared library and runs
`zipalign -c -P 16` against the debug APK.

## Use

```kotlin
import com.phototoolkit.engine.VipsNative

val initError = VipsNative.initialize()
val error = VipsNative.convert(inputPath, outputPath)
```

`VipsNative` returns an empty string on success and an error message on
failure. Call it from a background thread or WorkManager worker, not the main
thread.

All image-producing native operations write to a temporary file in the output
directory and replace the destination only after a successful encode. Existing
output is preserved when processing or finalization fails.

Use the typed resize API when the caller owns an explicit output-size policy:

```kotlin
val error = VipsNative.resizeTo(
    inputPath,
    outputPath,
    ImageResizeSpec(1080, 1080, ImageResizeMode.COVER_CROP)
)
```

`CONTAIN` preserves the aspect ratio inside the requested bounds,
`COVER_CROP` produces the exact requested dimensions by center-cropping, and
`STRETCH` produces the exact dimensions without preserving the aspect ratio.

For target-size JPEG compression, prefer the detailed result when quality and
final dimensions need to be displayed or recorded:

```kotlin
val result = VipsNative.compressJpegDetailed(inputPath, outputPath, targetKb = 500)
check(result.isSuccess && result.targetMet) { result.errorMessage }
```
