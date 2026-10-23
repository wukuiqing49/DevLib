#include <jni.h>
#include <cmath>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <libraw/libraw.h>
#include <vips/vips.h>

namespace {
std::once_flag vips_once;
int vips_status = -1;

void ensure_vips() { std::call_once(vips_once, [] { vips_status = vips_init("PhotoToolkitRaw"); }); }

jstring result(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

std::string java_path(JNIEnv* env, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    std::string value = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return value;
}

std::string libraw_error(int code) {
    const char* value = libraw_strerror(code);
    return value == nullptr ? "LibRaw processing failed" : value;
}

std::string json_escape(const char* raw_value) {
    const std::string value = raw_value == nullptr ? "" : raw_value;
    std::string escaped;
    escaped.reserve(value.size());
    for (const char character : value) {
        switch (character) {
            case '\\': escaped += "\\\\"; break;
            case '"': escaped += "\\\""; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default: escaped += character; break;
        }
    }
    return escaped;
}

std::string develop_raw(
        const std::string& input_path,
        const std::string& output_path,
        int output_format,
        int quality,
        int white_balance_mode,
        bool auto_bright,
        double exposure_ev,
        bool half_size,
        int output_bits,
        int highlight_mode,
        float noise_threshold) {
    if (quality < 1 || quality > 100) return "Output quality must be in the 1..100 range";
    if (white_balance_mode < 0 || white_balance_mode > 2) return "Unknown white balance mode";
    if (output_format < 0 || output_format > 2) return "Unknown RAW output format";
    if (output_bits != 8 && output_bits != 16) return "RAW output bit depth must be 8 or 16";
    if (output_bits == 16 && output_format != 1) return "16-bit RAW output requires TIFF";
    if (highlight_mode < 0 || highlight_mode > 9) return "Highlight recovery must be in the 0..9 range";
    if (!std::isfinite(noise_threshold) || noise_threshold < 0.0f || noise_threshold > 1000.0f) {
        return "Noise threshold must be in the 0..1000 range";
    }
    if (!std::isfinite(exposure_ev) || exposure_ev < -5.0 || exposure_ev > 5.0) {
        return "Exposure must be in the -5..5 EV range";
    }
    ensure_vips();
    if (vips_status != 0) return "Unable to initialize libvips for RAW output";

    LibRaw raw;
    int status = raw.open_file(input_path.c_str());
    if (status != LIBRAW_SUCCESS) return libraw_error(status);
    raw.imgdata.params.use_camera_wb = white_balance_mode == 0 ? 1 : 0;
    raw.imgdata.params.use_auto_wb = white_balance_mode == 1 ? 1 : 0;
    raw.imgdata.params.no_auto_bright = auto_bright ? 0 : 1;
    raw.imgdata.params.exp_correc = std::abs(exposure_ev) > 0.001 ? 1 : 0;
    raw.imgdata.params.exp_shift = std::pow(2.0, exposure_ev);
    raw.imgdata.params.half_size = half_size ? 1 : 0;
    raw.imgdata.params.output_bps = output_bits;
    raw.imgdata.params.output_color = 1;
    raw.imgdata.params.highlight = highlight_mode;
    raw.imgdata.params.threshold = noise_threshold;

    status = raw.unpack();
    if (status == LIBRAW_SUCCESS) status = raw.dcraw_process();
    if (status != LIBRAW_SUCCESS) {
        raw.recycle();
        return libraw_error(status);
    }

    int image_status = LIBRAW_SUCCESS;
    libraw_processed_image_t* processed = raw.dcraw_make_mem_image(&image_status);
    if (processed == nullptr || image_status != LIBRAW_SUCCESS) {
        raw.recycle();
        return libraw_error(image_status);
    }
    if (processed->type != LIBRAW_IMAGE_BITMAP || processed->bits != output_bits || processed->colors < 3) {
        LibRaw::dcraw_clear_mem(processed);
        raw.recycle();
        return "RAW decoder returned an unsupported pixel layout";
    }

    const VipsBandFormat band_format = output_bits == 16 ? VIPS_FORMAT_USHORT : VIPS_FORMAT_UCHAR;
    VipsImage* decoded = vips_image_new_from_memory_copy(
            processed->data, processed->data_size, processed->width, processed->height,
            processed->colors, band_format);
    LibRaw::dcraw_clear_mem(processed);
    raw.recycle();
    if (decoded == nullptr) return vips_error_buffer();

    VipsImage* rgb = decoded;
    if (decoded->Bands > 3) {
        if (vips_extract_band(decoded, &rgb, 0, "n", 3, nullptr) != 0) {
            g_object_unref(decoded);
            std::string error = vips_error_buffer();
            vips_error_clear();
            return error.empty() ? "Unable to convert RAW pixels to RGB" : error;
        }
        g_object_unref(decoded);
    }

    int write_status = -1;
    if (output_format == 0) {
        write_status = vips_jpegsave(
                rgb, output_path.c_str(), "Q", quality, "strip", TRUE, "optimize_coding", TRUE, nullptr);
    } else if (output_format == 1) {
        write_status = vips_tiffsave(
                rgb, output_path.c_str(), "compression", VIPS_FOREIGN_TIFF_COMPRESSION_LZW,
                "tile", TRUE, "strip", TRUE, nullptr);
    } else {
        write_status = vips_webpsave(rgb, output_path.c_str(), "Q", quality, "strip", TRUE, nullptr);
    }
    g_object_unref(rgb);
    if (write_status == 0) return "";
    std::string error = vips_error_buffer();
    vips_error_clear();
    return error.empty() ? "Unable to write RAW output" : error;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phototoolkit_raw_RawNative_inspect(JNIEnv* env, jclass, jstring input) {
    LibRaw raw;
    const int status = raw.open_file(java_path(env, input).c_str());
    if (status != LIBRAW_SUCCESS) return result(env, libraw_error(status));
    const auto& id = raw.imgdata.idata;
    const auto& size = raw.imgdata.sizes;
    std::string info = "camera=" + std::string(id.make) + " " + id.model +
                       ", raw=" + std::to_string(size.raw_width) + "x" + std::to_string(size.raw_height) +
                       ", image=" + std::to_string(size.width) + "x" + std::to_string(size.height);
    raw.recycle();
    return result(env, info);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phototoolkit_raw_RawNative_inspectDetails(JNIEnv* env, jclass, jstring input) {
    LibRaw raw;
    const int status = raw.open_file(java_path(env, input).c_str());
    if (status != LIBRAW_SUCCESS) return result(env, libraw_error(status));
    const auto& id = raw.imgdata.idata;
    const auto& size = raw.imgdata.sizes;
    const auto& other = raw.imgdata.other;
    std::ostringstream json;
    json << "{\"make\":\"" << json_escape(id.make)
         << "\",\"model\":\"" << json_escape(id.model)
         << "\",\"rawWidth\":" << size.raw_width
         << ",\"rawHeight\":" << size.raw_height
         << ",\"imageWidth\":" << size.width
         << ",\"imageHeight\":" << size.height
         << ",\"iso\":" << other.iso_speed
         << ",\"shutterSeconds\":" << other.shutter
         << ",\"aperture\":" << other.aperture
         << ",\"focalLengthMm\":" << other.focal_len
         << ",\"timestampSeconds\":" << static_cast<long long>(other.timestamp)
         << "}";
    raw.recycle();
    return result(env, json.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phototoolkit_raw_RawNative_extractPreview(
        JNIEnv* env, jclass, jstring input, jstring output) {
    ensure_vips();
    if (vips_status != 0) return result(env, "Unable to initialize libvips for RAW preview");
    LibRaw raw;
    int status = raw.open_file(java_path(env, input).c_str());
    if (status == LIBRAW_SUCCESS) status = raw.unpack_thumb();
    if (status != LIBRAW_SUCCESS) {
        raw.recycle();
        return result(env, libraw_error(status));
    }
    int image_status = LIBRAW_SUCCESS;
    libraw_processed_image_t* preview = raw.dcraw_make_mem_thumb(&image_status);
    if (preview == nullptr || image_status != LIBRAW_SUCCESS) {
        raw.recycle();
        return result(env, libraw_error(image_status));
    }
    const std::string output_path = java_path(env, output);
    std::string error;
    if (preview->type == LIBRAW_IMAGE_JPEG) {
        std::ofstream stream(output_path, std::ios::binary | std::ios::trunc);
        stream.write(reinterpret_cast<const char*>(preview->data), static_cast<std::streamsize>(preview->data_size));
        if (!stream.good()) error = "Unable to write embedded RAW preview";
    } else if (preview->type == LIBRAW_IMAGE_BITMAP && preview->bits == 8 && preview->colors >= 3) {
        VipsImage* image = vips_image_new_from_memory_copy(
                preview->data, preview->data_size, preview->width, preview->height,
                preview->colors, VIPS_FORMAT_UCHAR);
        if (image == nullptr || vips_jpegsave(image, output_path.c_str(), "Q", 92, "strip", TRUE, nullptr) != 0) {
            error = vips_error_buffer();
            vips_error_clear();
        }
        if (image != nullptr) g_object_unref(image);
    } else {
        error = "RAW embedded preview has an unsupported format";
    }
    LibRaw::dcraw_clear_mem(preview);
    raw.recycle();
    return result(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phototoolkit_raw_RawNative_developToJpeg(
        JNIEnv* env, jclass, jstring input, jstring output, jint quality,
        jint white_balance_mode, jboolean auto_bright, jdouble exposure_ev, jboolean half_size) {
    if (quality < 1 || quality > 100) return result(env, "JPEG quality must be in the 1..100 range");
    if (white_balance_mode < 0 || white_balance_mode > 2) return result(env, "Unknown white balance mode");
    if (!std::isfinite(exposure_ev) || exposure_ev < -5.0 || exposure_ev > 5.0) return result(env, "Exposure must be in the -5..5 EV range");
    ensure_vips();
    if (vips_status != 0) return result(env, "Unable to initialize libvips for JPEG output");

    LibRaw raw;
    int status = raw.open_file(java_path(env, input).c_str());
    if (status != LIBRAW_SUCCESS) return result(env, libraw_error(status));
    raw.imgdata.params.use_camera_wb = white_balance_mode == 0 ? 1 : 0;
    raw.imgdata.params.use_auto_wb = white_balance_mode == 1 ? 1 : 0;
    raw.imgdata.params.no_auto_bright = auto_bright ? 0 : 1;
    raw.imgdata.params.exp_correc = std::abs(exposure_ev) > 0.001 ? 1 : 0;
    raw.imgdata.params.exp_shift = std::pow(2.0, exposure_ev);
    raw.imgdata.params.half_size = half_size ? 1 : 0;
    raw.imgdata.params.output_bps = 8;
    raw.imgdata.params.output_color = 1;  // sRGB

    status = raw.unpack();
    if (status == LIBRAW_SUCCESS) status = raw.dcraw_process();
    if (status != LIBRAW_SUCCESS) {
        raw.recycle();
        return result(env, libraw_error(status));
    }

    int image_status = LIBRAW_SUCCESS;
    libraw_processed_image_t* processed = raw.dcraw_make_mem_image(&image_status);
    if (processed == nullptr || image_status != LIBRAW_SUCCESS) {
        raw.recycle();
        return result(env, libraw_error(image_status));
    }
    if (processed->type != LIBRAW_IMAGE_BITMAP || processed->bits != 8 || processed->colors < 3) {
        LibRaw::dcraw_clear_mem(processed);
        raw.recycle();
        return result(env, "RAW decoder did not produce an 8-bit RGB image");
    }

    VipsImage* decoded = vips_image_new_from_memory_copy(
            processed->data, processed->data_size, processed->width, processed->height,
            processed->colors, VIPS_FORMAT_UCHAR);
    LibRaw::dcraw_clear_mem(processed);
    raw.recycle();
    if (decoded == nullptr) return result(env, vips_error_buffer());

    VipsImage* rgb = decoded;
    if (decoded->Bands > 3) {
        if (vips_extract_band(decoded, &rgb, 0, "n", 3, nullptr) != 0) {
            g_object_unref(decoded);
            std::string error = vips_error_buffer();
            vips_error_clear();
            return result(env, error.empty() ? "Unable to convert RAW pixels to RGB" : error);
        }
        g_object_unref(decoded);
    }
    const std::string output_path = java_path(env, output);
    const int write_status = vips_jpegsave(rgb, output_path.c_str(), "Q", quality, "strip", TRUE, "optimize_coding", TRUE, nullptr);
    g_object_unref(rgb);
    if (write_status == 0) return result(env, "");
    std::string error = vips_error_buffer();
    vips_error_clear();
    return result(env, error.empty() ? "Unable to write JPEG" : error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phototoolkit_raw_RawNative_develop(
        JNIEnv* env, jclass, jstring input, jstring output, jint output_format, jint quality,
        jint white_balance_mode, jboolean auto_bright, jdouble exposure_ev, jboolean half_size,
        jint output_bits, jint highlight_mode, jfloat noise_threshold) {
    return result(env, develop_raw(
            java_path(env, input),
            java_path(env, output),
            output_format,
            quality,
            white_balance_mode,
            auto_bright,
            exposure_ev,
            half_size,
            output_bits,
            highlight_mode,
            noise_threshold));
}
