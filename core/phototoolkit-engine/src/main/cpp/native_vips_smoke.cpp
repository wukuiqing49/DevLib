#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <vips/vips.h>

namespace {
std::once_flag vips_once;
int vips_status = -1;

void apply_runtime_limits(int concurrency, int max_cache_operations, size_t max_cache_memory) {
    vips_concurrency_set(concurrency);
    vips_cache_set_max(max_cache_operations);
    vips_cache_set_max_mem(max_cache_memory);
    vips_cache_set_max_files(64);
}

void ensure_vips() {
    std::call_once(vips_once, [] {
        vips_status = vips_init("PhotoToolkitLibvips");
        if (vips_status == 0) apply_runtime_limits(2, 100, 128U * 1024U * 1024U);
    });
}

jstring result(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

std::string error_or(const char* fallback) {
    const char* error = vips_error_buffer();
    std::string value = error != nullptr && *error != '\0' ? error : fallback;
    vips_error_clear();
    return value;
}

std::string java_path(JNIEnv* env, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    std::string value = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return value;
}

struct AlphaBounds {
    bool valid = false;
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
};

// 只取头肩区域，并只保留最大连通前景，避免孤立噪点影响肩宽与底部定位。
AlphaBounds find_alpha_bounds(
        const jbyte* bytes,
        int width,
        int height,
        int top,
        int bottom,
        int anchor_left,
        int anchor_top,
        int anchor_right,
        unsigned char threshold) {
    const int start_y = std::clamp(top, 0, height);
    const int end_y = std::clamp(bottom, start_y, height);
    AlphaBounds largest;
    AlphaBounds anchored;
    if (start_y >= end_y) return largest;

    const size_t pixel_count = static_cast<size_t>(width) * height;
    std::vector<unsigned char> visited(pixel_count, 0);
    int largest_area = 0;
    int anchored_area = 0;
    constexpr int directions[8][2] = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0},           {1, 0},
            {-1, 1},  {0, 1},  {1, 1},
    };

    for (int y = start_y; y < end_y; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t seed_index = static_cast<size_t>(y) * width + x;
            if (visited[seed_index] != 0 ||
                static_cast<unsigned char>(bytes[seed_index]) <= threshold) {
                continue;
            }

            std::vector<int> pending;
            pending.push_back(static_cast<int>(seed_index));
            visited[seed_index] = 1;
            AlphaBounds component;
            component.valid = true;
            component.left = x;
            component.top = y;
            component.right = x + 1;
            component.bottom = y + 1;
            int area = 0;

            while (!pending.empty()) {
                const int current = pending.back();
                pending.pop_back();
                const int current_x = current % width;
                const int current_y = current / width;
                ++area;
                component.left = std::min(component.left, current_x);
                component.top = std::min(component.top, current_y);
                component.right = std::max(component.right, current_x + 1);
                component.bottom = std::max(component.bottom, current_y + 1);

                for (const auto& direction : directions) {
                    const int next_x = current_x + direction[0];
                    const int next_y = current_y + direction[1];
                    if (next_x < 0 || next_x >= width || next_y < start_y || next_y >= end_y) {
                        continue;
                    }
                    const size_t next_index = static_cast<size_t>(next_y) * width + next_x;
                    if (visited[next_index] != 0 ||
                        static_cast<unsigned char>(bytes[next_index]) <= threshold) {
                        continue;
                    }
                    visited[next_index] = 1;
                    pending.push_back(static_cast<int>(next_index));
                }
            }

            if (area > largest_area) {
                largest_area = area;
                largest = component;
            }
            const bool crosses_anchor = component.left < anchor_right &&
                    component.right > anchor_left &&
                    component.top <= anchor_top &&
                    component.bottom > anchor_top;
            if (crosses_anchor && area > anchored_area) {
                anchored_area = area;
                anchored = component;
            }
        }
    }
    if (!anchored.valid || anchored.left == largest.left && anchored.top == largest.top &&
        anchored.right == largest.right && anchored.bottom == largest.bottom) {
        return largest;
    }
    // Keep the largest body component for width/bottom, but retain the head component's crown.
    largest.valid = true;
    largest.left = std::min(largest.left, anchored.left);
    largest.top = std::min(largest.top, anchored.top);
    largest.right = std::max(largest.right, anchored.right);
    largest.bottom = std::max(largest.bottom, anchored.bottom);
    return largest;
}

int write_id_photo_output(VipsImage* image, const std::string& path) {
    const size_t dot = path.find_last_of('.');
    std::string extension = dot == std::string::npos ? "" : path.substr(dot);
    std::transform(extension.begin(), extension.end(), extension.begin(), [](unsigned char value) {
        return static_cast<char>(std::tolower(value));
    });
    if (extension == ".jpg" || extension == ".jpeg") {
        return vips_jpegsave(
                image, path.c_str(), "Q", 95, "optimize_coding", TRUE, "strip", FALSE, nullptr);
    }
    return vips_image_write_to_file(image, path.c_str(), "strip", FALSE, nullptr);
}

bool create_temporary_output(const std::string& output, std::string* temporary, std::string* error) {
    const size_t slash = output.find_last_of("/\\");
    const size_t dot = output.find_last_of('.');
    const bool has_extension = dot != std::string::npos && (slash == std::string::npos || dot > slash);
    const std::string extension = has_extension ? output.substr(dot) : "";
    const std::string base = has_extension ? output.substr(0, dot) : output;
    std::string pattern = base + ".tmp.XXXXXX" + extension;
    std::vector<char> writable(pattern.begin(), pattern.end());
    writable.push_back('\0');
    const int descriptor = mkstemps(writable.data(), static_cast<int>(extension.size()));
    if (descriptor < 0) {
        *error = "Unable to create temporary output: " + std::string(std::strerror(errno));
        return false;
    }
    close(descriptor);
    *temporary = writable.data();
    return true;
}

bool commit_temporary_output(const std::string& temporary, const std::string& output, std::string* error) {
    struct stat existing {};
    if (stat(output.c_str(), &existing) != 0) {
        if (std::rename(temporary.c_str(), output.c_str()) == 0) return true;
        *error = "Unable to finalize output: " + std::string(std::strerror(errno));
        return false;
    }

    std::string backup_pattern = output + ".bak.XXXXXX";
    std::vector<char> writable(backup_pattern.begin(), backup_pattern.end());
    writable.push_back('\0');
    const int descriptor = mkstemp(writable.data());
    if (descriptor < 0) {
        *error = "Unable to reserve output backup: " + std::string(std::strerror(errno));
        return false;
    }
    close(descriptor);
    unlink(writable.data());
    const std::string backup = writable.data();
    if (std::rename(output.c_str(), backup.c_str()) != 0) {
        *error = "Unable to preserve existing output: " + std::string(std::strerror(errno));
        return false;
    }
    if (std::rename(temporary.c_str(), output.c_str()) == 0) {
        unlink(backup.c_str());
        return true;
    }

    const int finalize_error = errno;
    if (std::rename(backup.c_str(), output.c_str()) != 0) {
        *error = "Unable to finalize output; existing output remains at " + backup;
    } else {
        *error = "Unable to finalize output; existing output was restored: " + std::string(std::strerror(finalize_error));
    }
    return false;
}

template <typename Operation>
jstring with_image(JNIEnv* env, jstring input, jstring output, Operation operation) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    const std::string output_path = java_path(env, output);
    std::string temporary_path;
    std::string file_error;
    if (!create_temporary_output(output_path, &temporary_path, &file_error)) return result(env, file_error);
    VipsImage* source = vips_image_new_from_file(java_path(env, input).c_str(), nullptr);
    if (source == nullptr) {
        unlink(temporary_path.c_str());
        return result(env, error_or("Unable to load image"));
    }
    VipsImage* target = nullptr;
    int status = operation(source, &target);
    if (status == 0) status = vips_image_write_to_file(target, temporary_path.c_str(), nullptr);
    g_object_unref(source);
    if (target != nullptr) g_object_unref(target);
    std::string operation_error;
    if (status != 0) operation_error = error_or("Image operation failed");
    if (status == 0 && !commit_temporary_output(temporary_path, output_path, &file_error)) operation_error = file_error;
    unlink(temporary_path.c_str());
    return result(env, operation_error);
}

template <typename Operation>
jstring with_two_images(JNIEnv* env, jstring input, jstring second, jstring output, Operation operation) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    const std::string output_path = java_path(env, output);
    std::string temporary_path;
    std::string file_error;
    if (!create_temporary_output(output_path, &temporary_path, &file_error)) return result(env, file_error);
    VipsImage* first = vips_image_new_from_file(java_path(env, input).c_str(), nullptr);
    if (first == nullptr) {
        unlink(temporary_path.c_str());
        return result(env, error_or("Unable to load first image"));
    }
    VipsImage* other = vips_image_new_from_file(java_path(env, second).c_str(), nullptr);
    if (other == nullptr) {
        g_object_unref(first);
        unlink(temporary_path.c_str());
        return result(env, error_or("Unable to load second image"));
    }
    VipsImage* target = nullptr;
    int status = operation(first, other, &target);
    if (status == 0) status = vips_image_write_to_file(target, temporary_path.c_str(), nullptr);
    g_object_unref(first);
    g_object_unref(other);
    if (target != nullptr) g_object_unref(target);
    std::string operation_error;
    if (status != 0) operation_error = error_or("Multi-image operation failed");
    if (status == 0 && !commit_temporary_output(temporary_path, output_path, &file_error)) operation_error = file_error;
    unlink(temporary_path.c_str());
    return result(env, operation_error);
}

jstring encode_image(
    JNIEnv* env,
    jstring input,
    jstring output,
    int quality,
    int metadata_policy
) {
    if (quality < 1 || quality > 100) return result(env, "quality must be between 1 and 100");
    if (metadata_policy < 0 || metadata_policy > 2) return result(env, "metadataPolicy must be 0 through 2");
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    const std::string output_path = java_path(env, output);
    std::string temporary_path;
    std::string file_error;
    if (!create_temporary_output(output_path, &temporary_path, &file_error)) return result(env, file_error);
    VipsImage* source = vips_image_new_from_file(java_path(env, input).c_str(), nullptr);
    if (source == nullptr) {
        unlink(temporary_path.c_str());
        return result(env, error_or("Unable to load image"));
    }
    VipsImage* target = nullptr;
    int status = metadata_policy == 0
        ? (target = VIPS_IMAGE(g_object_ref(source)), 0)
        : vips_autorot(source, &target, nullptr);
    if (status == 0 && metadata_policy == 1) {
        // 隐私清理保留 ICC，删除可能包含定位、设备、日期或作者信息的 metadata 块。
        const char* private_fields[] = {
            "exif-data", "xmp-data", "iptc-data", "orientation", "image-description"
        };
        for (const char* field : private_fields) vips_image_remove(target, field);
        char** fields = vips_image_get_fields(target);
        if (fields != nullptr) {
            for (int index = 0; fields[index] != nullptr; ++index) {
                const std::string field = fields[index];
                if (field.rfind("exif-", 0) == 0 || field.rfind("xmp-", 0) == 0 ||
                    field.rfind("iptc-", 0) == 0) {
                    vips_image_remove(target, fields[index]);
                }
            }
            g_strfreev(fields);
        }
    }
    if (status == 0) status = vips_image_write_to_file(
        target,
        temporary_path.c_str(),
        "Q", quality,
        "strip", metadata_policy == 2,
        nullptr
    );
    g_object_unref(source);
    if (target != nullptr) g_object_unref(target);
    std::string operation_error;
    if (status != 0) operation_error = error_or("Image encode failed");
    if (status == 0 && !commit_temporary_output(temporary_path, output_path, &file_error)) {
        operation_error = file_error;
    }
    unlink(temporary_path.c_str());
    return result(env, operation_error);
}

int as_grayscale(VipsImage* source, VipsImage** target) {
    return source->Bands == 1 ? (*target = VIPS_IMAGE(g_object_ref(source)), 0)
                              : vips_colourspace(source, target, VIPS_INTERPRETATION_B_W, nullptr);
}

int as_three_bands(VipsImage* source, VipsImage** target) {
    if (source->Bands >= 3) return vips_extract_band(source, target, 0, "n", 3, nullptr);
    VipsImage* mono = nullptr;
    int status = source->Bands == 1 ? (mono = VIPS_IMAGE(g_object_ref(source)), 0)
                                    : vips_extract_band(source, &mono, 0, nullptr);
    if (status == 0) {
        VipsImage* bands[] = {mono, mono, mono};
        status = vips_bandjoin(bands, target, 3, nullptr);
    }
    if (mono) g_object_unref(mono);
    return status;
}

int tag_as_rgb(VipsImage* source, VipsImage** target) {
    // 只声明三通道像素为 RGB，不执行 colourspace 转换。
    return vips_copy(source, target, "interpretation", VIPS_INTERPRETATION_RGB, nullptr);
}

int tag_as_srgb(VipsImage* source, VipsImage** target) {
    // 合成前只修正像素解释，避免让 libvips 对 multiband 尝试不存在的色彩空间转换。
    return vips_copy(source, target, "interpretation", VIPS_INTERPRETATION_sRGB, nullptr);
}

int as_rgb_uchar(VipsImage* source, VipsImage** target) {
    VipsImage* three_bands = nullptr;
    VipsImage* uchar = nullptr;
    int status = as_three_bands(source, &three_bands);
    if (status == 0) status = vips_cast(three_bands, &uchar, VIPS_FORMAT_UCHAR, nullptr);
    if (status == 0) status = tag_as_rgb(uchar, target);
    if (three_bands) g_object_unref(three_bands);
    if (uchar) g_object_unref(uchar);
    return status;
}

struct JpegCompressionOutcome {
    std::string error;
    off_t output_bytes = 0;
    off_t target_bytes = 0;
    int quality = 0;
    int width = 0;
    int height = 0;
};

JpegCompressionOutcome compress_jpeg(
    const std::string& input,
    const std::string& output,
    off_t target_bytes,
    int max_quality
) {
    JpegCompressionOutcome outcome;
    if (target_bytes <= 0) {
        outcome.error = "targetBytes must be positive";
        return outcome;
    }
    if (max_quality < 1 || max_quality > 100) {
        outcome.error = "maxQuality must be between 1 and 100";
        return outcome;
    }
    outcome.target_bytes = target_bytes;
    ensure_vips();
    if (vips_status != 0) {
        outcome.error = error_or("vips_init failed");
        return outcome;
    }

    std::string temporary;
    if (!create_temporary_output(output, &temporary, &outcome.error)) return outcome;
    VipsImage* source = vips_image_new_from_file(input.c_str(), nullptr);
    if (source == nullptr) {
        outcome.error = error_or("Unable to load image");
        unlink(temporary.c_str());
        return outcome;
    }
    VipsImage* oriented = nullptr;
    int status = vips_autorot(source, &oriented, nullptr);
    g_object_unref(source);
    if (status != 0) {
        outcome.error = error_or("Unable to normalize image orientation");
        unlink(temporary.c_str());
        return outcome;
    }

    VipsImage* working = oriented;
    for (int pass = 0; pass < 24; ++pass) {
        int low = std::min(30, max_quality);
        int high = max_quality;
        int best_quality = -1;
        off_t candidate_bytes = 0;
        while (low <= high) {
            const int quality = low + (high - low) / 2;
            status = vips_jpegsave(
                working,
                temporary.c_str(),
                "Q", quality,
                "strip", TRUE,
                "optimize-coding", TRUE,
                nullptr
            );
            if (status != 0) break;
            struct stat info {};
            if (stat(temporary.c_str(), &info) != 0) {
                status = -1;
                outcome.error = "Unable to inspect compressed JPEG";
                break;
            }
            candidate_bytes = info.st_size;
            if (candidate_bytes <= outcome.target_bytes) {
                best_quality = quality;
                low = quality + 1;
            } else {
                high = quality - 1;
            }
        }
        if (status != 0) {
            if (outcome.error.empty()) outcome.error = error_or("JPEG compression failed");
            break;
        }
        if (best_quality >= 0) {
            status = vips_jpegsave(
                working,
                temporary.c_str(),
                "Q", best_quality,
                "strip", TRUE,
                "optimize-coding", TRUE,
                nullptr
            );
            struct stat info {};
            if (status != 0 || stat(temporary.c_str(), &info) != 0 || info.st_size > outcome.target_bytes) {
                outcome.error = status == 0 ? "Compressed JPEG did not satisfy target size" : error_or("JPEG compression failed");
                break;
            }
            outcome.output_bytes = info.st_size;
            outcome.quality = best_quality;
            outcome.width = working->Xsize;
            outcome.height = working->Ysize;
            if (!commit_temporary_output(temporary, output, &outcome.error)) break;
            outcome.error.clear();
            break;
        }
        if (working->Xsize <= 128 || working->Ysize <= 128) {
            outcome.error = "Unable to reach target size without reducing image below 128 pixels";
            break;
        }

        const double estimated = candidate_bytes > 0
            ? std::sqrt(static_cast<double>(outcome.target_bytes) / candidate_bytes) * 0.95
            : 0.85;
        const double scale = std::clamp(estimated, 0.50, 0.90);
        VipsImage* smaller = nullptr;
        status = vips_resize(working, &smaller, scale, nullptr);
        if (status != 0) {
            outcome.error = error_or("Unable to resize JPEG during compression");
            break;
        }
        g_object_unref(working);
        working = smaller;
    }
    if (outcome.error.empty() && outcome.output_bytes == 0) {
        outcome.error = "Unable to reach target size within compression limits";
    }
    g_object_unref(working);
    unlink(temporary.c_str());
    return outcome;
}

jobject jpeg_compression_result(JNIEnv* env, const JpegCompressionOutcome& outcome) {
    jclass type = env->FindClass("com/phototoolkit/engine/JpegCompressionResult");
    if (type == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;JJIII)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(type);
        return nullptr;
    }
    jstring error = result(env, outcome.error);
    jobject value = env->NewObject(
        type,
        constructor,
        error,
        static_cast<jlong>(outcome.output_bytes),
        static_cast<jlong>(outcome.target_bytes),
        static_cast<jint>(outcome.quality),
        static_cast<jint>(outcome.width),
        static_cast<jint>(outcome.height)
    );
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(type);
    return value;
}
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_initialize(JNIEnv* env, jclass) {
    ensure_vips();
    return result(env, vips_status == 0 ? "" : error_or("vips_init failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_configureRuntimeNative(
        JNIEnv* env, jclass, jint concurrency, jint max_cache_operations, jlong max_cache_memory_bytes) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    if (concurrency < 1 || concurrency > 16 || max_cache_operations < 0 || max_cache_operations > 10000 ||
        max_cache_memory_bytes < 0 || max_cache_memory_bytes > 2LL * 1024LL * 1024LL * 1024LL) {
        return result(env, "Invalid native runtime limits");
    }
    apply_runtime_limits(concurrency, max_cache_operations, static_cast<size_t>(max_cache_memory_bytes));
    return result(env, "");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_version(JNIEnv* env, jclass) {
    ensure_vips();
    const char* version = vips_status == 0 ? vips_version_string() : nullptr;
    __android_log_print(ANDROID_LOG_INFO, "PhotoToolkitVips", "libvips=%s", version ? version : "unavailable");
    return result(env, version ? version : error_or("vips_init failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_imageInfo(JNIEnv* env, jclass, jstring input) {
    ensure_vips();
    VipsImage* image = vips_status == 0 ? vips_image_new_from_file(java_path(env, input).c_str(), nullptr) : nullptr;
    if (!image) return result(env, error_or("Unable to load image"));
    std::string value = std::to_string(image->Xsize) + "x" + std::to_string(image->Ysize) + ", bands=" + std::to_string(image->Bands) + ", format=" + vips_enum_nick(VIPS_TYPE_BAND_FORMAT, image->BandFmt);
    g_object_unref(image);
    return result(env, value);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_resize(JNIEnv* env, jclass, jstring in, jstring out, jdouble scale) { return with_image(env, in, out, [scale](VipsImage* s, VipsImage** d) { return vips_resize(s, d, scale, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_resizeToNative(JNIEnv* env, jclass, jstring in, jstring out, jint width, jint height, jint mode) {
    if (width < 1 || height < 1) return result(env, "width and height must be positive");
    if (mode < 0 || mode > 2) return result(env, "resize mode must be 0 through 2");
    return with_image(env, in, out, [=](VipsImage* source, VipsImage** target) {
        VipsImage* normalized = nullptr;
        VipsImage* oriented = nullptr;
        // resize/autrot 需要真实的 RGB 像素，不能只修改 multiband 的 interpretation 标签。
        int status = as_rgb_uchar(source, &normalized);
        if (status == 0) status = vips_autorot(normalized, &oriented, nullptr);
        if (status == 0 && mode == 0) {
            const double horizontal = static_cast<double>(width) / oriented->Xsize;
            const double vertical = static_cast<double>(height) / oriented->Ysize;
            status = vips_resize(oriented, target, std::min(horizontal, vertical), nullptr);
        } else if (status == 0 && mode == 1) {
            status = vips_thumbnail_image(
                oriented,
                target,
                width,
                "height", height,
                "crop", VIPS_INTERESTING_CENTRE,
                "size", VIPS_SIZE_FORCE,
                nullptr
            );
        } else if (status == 0) {
            const double horizontal = static_cast<double>(width) / oriented->Xsize;
            const double vertical = static_cast<double>(height) / oriented->Ysize;
            status = vips_resize(oriented, target, horizontal, "vscale", vertical, nullptr);
        }
        if (normalized != nullptr) g_object_unref(normalized);
        if (oriented != nullptr) g_object_unref(oriented);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_thumbnail(JNIEnv* env, jclass, jstring in, jstring out, jint width) { return with_image(env, in, out, [width](VipsImage* s, VipsImage** d) { return vips_thumbnail_image(s, d, width, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_crop(JNIEnv* env, jclass, jstring in, jstring out, jint left, jint top, jint width, jint height) { return with_image(env, in, out, [=](VipsImage* s, VipsImage** d) { return vips_extract_area(s, d, left, top, width, height, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_rotate(JNIEnv* env, jclass, jstring in, jstring out, jint angle) {
    VipsAngle rotation = angle == 90 ? VIPS_ANGLE_D90 : angle == 180 ? VIPS_ANGLE_D180 : angle == 270 ? VIPS_ANGLE_D270 : VIPS_ANGLE_D0;
    if (angle != 90 && angle != 180 && angle != 270) return result(env, "angle must be 90, 180, or 270");
    return with_image(env, in, out, [rotation](VipsImage* s, VipsImage** d) { return vips_rot(s, d, rotation, nullptr); });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_convert(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { *d = VIPS_IMAGE(g_object_ref(s)); return 0; }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_encode(JNIEnv* env, jclass, jstring in, jstring out, jint quality, jint metadata_policy) {
    return encode_image(env, in, out, quality, metadata_policy);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_flip(JNIEnv* env, jclass, jstring in, jstring out, jboolean horizontal) {
    VipsDirection direction = horizontal ? VIPS_DIRECTION_HORIZONTAL : VIPS_DIRECTION_VERTICAL;
    return with_image(env, in, out, [direction](VipsImage* s, VipsImage** d) { return vips_flip(s, d, direction, nullptr); });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_blur(JNIEnv* env, jclass, jstring in, jstring out, jdouble sigma) { return with_image(env, in, out, [sigma](VipsImage* s, VipsImage** d) { return vips_gaussblur(s, d, sigma, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_sharpen(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { return vips_sharpen(s, d, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_autoEnhance(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        VipsImage* color = nullptr;
        VipsImage* gray = nullptr;
        VipsImage* adjusted = nullptr;
        VipsImage* extras = nullptr;
        double average = 128.0;
        double deviation = 48.0;
        const int color_bands = s->Bands >= 3 ? 3 : 1;
        int status = s->Bands == color_bands
            ? (color = VIPS_IMAGE(g_object_ref(s)), 0)
            : vips_extract_band(s, &color, 0, "n", color_bands, nullptr);
        if (status == 0) status = as_grayscale(color, &gray);
        if (status == 0) status = vips_avg(gray, &average, nullptr);
        if (status == 0) status = vips_deviate(gray, &deviation, nullptr);
        if (status == 0) {
            const double contrast = std::clamp(52.0 / std::max(1.0, deviation), 0.96, 1.18);
            const double target_average = average < 96.0 ? 108.0 : (average > 176.0 ? 160.0 : average);
            const double brightness = std::clamp(target_average - average * contrast, -18.0, 18.0);
            status = vips_linear1(color, &adjusted, contrast, brightness, nullptr);
        }
        if (status == 0 && s->Bands > color_bands) {
            status = vips_extract_band(s, &extras, color_bands, "n", s->Bands - color_bands, nullptr);
            if (status == 0) status = vips_bandjoin2(adjusted, extras, d, nullptr);
        } else if (status == 0) {
            *d = VIPS_IMAGE(g_object_ref(adjusted));
        }
        if (color) g_object_unref(color);
        if (gray) g_object_unref(gray);
        if (adjusted) g_object_unref(adjusted);
        if (extras) g_object_unref(extras);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_adjust(JNIEnv* env, jclass, jstring in, jstring out, jdouble brightness, jdouble contrast) { return with_image(env, in, out, [brightness, contrast](VipsImage* s, VipsImage** d) { return vips_linear1(s, d, contrast, brightness, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_invert(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { return vips_invert(s, d, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_grayscale(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { return vips_colourspace(s, d, VIPS_INTERPRETATION_B_W, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_average(JNIEnv* env, jclass, jstring input) {
    ensure_vips();
    VipsImage* image = vips_status == 0 ? vips_image_new_from_file(java_path(env, input).c_str(), nullptr) : nullptr;
    if (image == nullptr) return result(env, error_or("Unable to load image"));
    double value = 0.0;
    int status = vips_avg(image, &value, nullptr);
    g_object_unref(image);
    return result(env, status == 0 ? "average=" + std::to_string(value) : error_or("Unable to calculate average"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_statistics(JNIEnv* env, jclass, jstring input) {
    ensure_vips();
    VipsImage* image = vips_status == 0 ? vips_image_new_from_file(java_path(env, input).c_str(), nullptr) : nullptr;
    if (image == nullptr) return result(env, error_or("Unable to load image"));
    double average = 0.0, minimum = 0.0, maximum = 0.0;
    int status = vips_avg(image, &average, nullptr);
    if (status == 0) status = vips_min(image, &minimum, nullptr);
    if (status == 0) status = vips_max(image, &maximum, nullptr);
    g_object_unref(image);
    return result(env, status == 0 ? "average=" + std::to_string(average) + ", min=" + std::to_string(minimum) + ", max=" + std::to_string(maximum) : error_or("Unable to calculate statistics"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_autoRotate(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { return vips_autorot(s, d, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_autoRotateWithOrientation(
        JNIEnv* env, jclass, jstring in, jstring out, jint orientation) {
    if (orientation < 1 || orientation > 8) return result(env, "orientation must be 1 through 8");
    return with_image(env, in, out, [orientation](VipsImage* source, VipsImage** target) {
        VipsImage* tagged = nullptr;
        VipsImage* oriented = nullptr;
        int status = vips_copy(source, &tagged, nullptr);
        if (status == 0) {
            // 部分 Android JPEG 的 EXIF 未被当前 libvips loader 映射为 orientation；显式注入后再旋正像素。
            vips_image_set_int(tagged, "orientation", orientation);
            status = vips_autorot(tagged, &oriented, nullptr);
        }
        if (status == 0) {
            status = vips_copy(oriented, target, nullptr);
        }
        if (status == 0 && *target != nullptr) {
            // 像素已经旋正，清掉原始方向元数据，避免 Android/ML Kit 再旋转一次。
            vips_image_remove(*target, "orientation");
            vips_image_remove(*target, "exif-ifd0-Orientation");
            vips_image_remove(*target, "exif-data");
        }
        if (oriented != nullptr) g_object_unref(oriented);
        if (tagged != nullptr) g_object_unref(tagged);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_smartCrop(JNIEnv* env, jclass, jstring in, jstring out, jint width, jint height) { return with_image(env, in, out, [=](VipsImage* s, VipsImage** d) { return vips_smartcrop(s, d, width, height, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_freeRotate(JNIEnv* env, jclass, jstring in, jstring out, jdouble angle) { return with_image(env, in, out, [angle](VipsImage* s, VipsImage** d) { return vips_rotate(s, d, angle, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_median(JNIEnv* env, jclass, jstring in, jstring out, jint size) {
    if (size < 3 || size % 2 == 0) return result(env, "median size must be an odd number >= 3");
    return with_image(env, in, out, [size](VipsImage* s, VipsImage** d) { return vips_median(s, d, size, nullptr); });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_sobel(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { VipsImage* gray = nullptr; int status = as_grayscale(s, &gray); if (status == 0) status = vips_sobel(gray, d, nullptr); if (gray) g_object_unref(gray); return status; });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_canny(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { VipsImage* gray = nullptr; int status = as_grayscale(s, &gray); if (status == 0) status = vips_canny(gray, d, nullptr); if (gray) g_object_unref(gray); return status; });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_histEqual(JNIEnv* env, jclass, jstring in, jstring out) { return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { return vips_hist_equal(s, d, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_histogram(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        VipsImage* gray = nullptr; VipsImage* hist = nullptr;
        int status = as_grayscale(s, &gray);
        if (status == 0) status = vips_hist_find(gray, &hist, nullptr);
        if (status == 0) status = vips_hist_plot(hist, d, nullptr);
        if (gray) g_object_unref(gray);
        if (hist) g_object_unref(hist);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_gamma(JNIEnv* env, jclass, jstring in, jstring out, jdouble exponent) { return with_image(env, in, out, [exponent](VipsImage* s, VipsImage** d) { return vips_gamma(s, d, "exponent", exponent, nullptr); }); }
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_falseColour(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) { VipsImage* gray = nullptr; int status = as_grayscale(s, &gray); if (status == 0) status = vips_falsecolour(gray, d, nullptr); if (gray) g_object_unref(gray); return status; });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_extractBand(JNIEnv* env, jclass, jstring in, jstring out, jint band) {
    return with_image(env, in, out, [band](VipsImage* s, VipsImage** d) { return band >= 0 && band < s->Bands ? vips_extract_band(s, d, band, nullptr) : -1; });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_join(JNIEnv* env, jclass, jstring in, jstring second, jstring out, jboolean horizontal) {
    VipsDirection direction = horizontal ? VIPS_DIRECTION_HORIZONTAL : VIPS_DIRECTION_VERTICAL;
    return with_two_images(env, in, second, out, [direction](VipsImage* a, VipsImage* b, VipsImage** d) { return vips_join(a, b, d, direction, "expand", TRUE, nullptr); });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_pictureInPicture(JNIEnv* env, jclass, jstring in, jstring second, jstring out) {
    return with_two_images(env, in, second, out, [](VipsImage* base, VipsImage* overlay, VipsImage** d) {
        const double scale_x = static_cast<double>(base->Xsize) * 0.40 / overlay->Xsize;
        const double scale_y = static_cast<double>(base->Ysize) * 0.40 / overlay->Ysize;
        VipsImage* resized = nullptr;
        int status = vips_resize(overlay, &resized, scale_x < scale_y ? scale_x : scale_y, nullptr);
        if (status == 0) status = vips_insert(base, resized, d, base->Xsize - resized->Xsize - 16, base->Ysize - resized->Ysize - 16, nullptr);
        if (resized) g_object_unref(resized);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_blend(JNIEnv* env, jclass, jstring in, jstring second, jstring out, jint mode) {
    const VipsBlendMode blend_modes[] = {VIPS_BLEND_MODE_OVER, VIPS_BLEND_MODE_MULTIPLY, VIPS_BLEND_MODE_SCREEN, VIPS_BLEND_MODE_DIFFERENCE};
    if (mode < 0 || mode >= 4) return result(env, "blend mode must be 0 through 3");
    return with_two_images(env, in, second, out, [mode, blend_modes](VipsImage* first, VipsImage* other, VipsImage** d) {
        const int width = first->Xsize < other->Xsize ? first->Xsize : other->Xsize;
        const int height = first->Ysize < other->Ysize ? first->Ysize : other->Ysize;
        VipsImage* base = nullptr;
        VipsImage* overlay = nullptr;
        int status = vips_extract_area(first, &base, 0, 0, width, height, nullptr);
        if (status == 0) status = vips_extract_area(other, &overlay, 0, 0, width, height, nullptr);
        if (status == 0) status = vips_composite2(base, overlay, d, blend_modes[mode], nullptr);
        if (base) g_object_unref(base);
        if (overlay) g_object_unref(overlay);
        return status;
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_composePoster(
        JNIEnv* env, jclass, jstring background_path, jobjectArray layer_paths,
        jintArray layer_left, jintArray layer_top, jintArray layer_width,
        jintArray layer_height, jintArray layer_sampling, jstring output_path,
        jint canvas_width, jint canvas_height, jint background_red,
        jint background_green, jint background_blue) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    const jsize layer_count = env->GetArrayLength(layer_paths);
    if (canvas_width < 1 || canvas_height < 1 || canvas_width > 32768 || canvas_height > 32768 ||
        layer_count < 1 || layer_count > 16 ||
        env->GetArrayLength(layer_left) != layer_count || env->GetArrayLength(layer_top) != layer_count ||
        env->GetArrayLength(layer_width) != layer_count || env->GetArrayLength(layer_height) != layer_count ||
        env->GetArrayLength(layer_sampling) != layer_count ||
        background_red < 0 || background_red > 255 || background_green < 0 || background_green > 255 ||
        background_blue < 0 || background_blue > 255) {
        return result(env, "Invalid poster composition parameters");
    }

    jint* left = env->GetIntArrayElements(layer_left, nullptr);
    jint* top = env->GetIntArrayElements(layer_top, nullptr);
    jint* width = env->GetIntArrayElements(layer_width, nullptr);
    jint* height = env->GetIntArrayElements(layer_height, nullptr);
    jint* sampling = env->GetIntArrayElements(layer_sampling, nullptr);
    if (!left || !top || !width || !height || !sampling) {
        if (left) env->ReleaseIntArrayElements(layer_left, left, JNI_ABORT);
        if (top) env->ReleaseIntArrayElements(layer_top, top, JNI_ABORT);
        if (width) env->ReleaseIntArrayElements(layer_width, width, JNI_ABORT);
        if (height) env->ReleaseIntArrayElements(layer_height, height, JNI_ABORT);
        if (sampling) env->ReleaseIntArrayElements(layer_sampling, sampling, JNI_ABORT);
        return result(env, "Unable to read poster layer parameters");
    }

    VipsImage* black = nullptr;
    VipsImage* canvas_rgb = nullptr;
    VipsImage* canvas_rgba = nullptr;
    VipsImage* canvas = nullptr;
    int status = vips_black(&black, canvas_width, canvas_height, "bands", 3, nullptr);
    const double multipliers[] = {1.0, 1.0, 1.0};
    const double offsets[] = {static_cast<double>(background_red), static_cast<double>(background_green), static_cast<double>(background_blue)};
    if (status == 0) status = vips_linear(black, &canvas_rgb, multipliers, offsets, 3, nullptr);
    if (status == 0) status = vips_addalpha(canvas_rgb, &canvas_rgba, nullptr);
    if (status == 0) status = tag_as_srgb(canvas_rgba, &canvas);

    if (status == 0 && background_path != nullptr) {
        const std::string background_value = java_path(env, background_path);
        if (!background_value.empty()) {
            VipsImage* source = vips_image_new_from_file(background_value.c_str(), "access", VIPS_ACCESS_SEQUENTIAL, nullptr);
            VipsImage* rotated = nullptr; VipsImage* rgb = nullptr; VipsImage* resized = nullptr;
            VipsImage* cropped = nullptr; VipsImage* with_alpha = nullptr; VipsImage* with_alpha_srgb = nullptr; VipsImage* next = nullptr;
            if (!source) status = -1;
            if (status == 0) status = vips_autorot(source, &rotated, nullptr);
            if (status == 0) status = as_rgb_uchar(rotated, &rgb);
            const double scale_x = status == 0 ? static_cast<double>(canvas_width) / rgb->Xsize : 1.0;
            const double scale_y = status == 0 ? static_cast<double>(canvas_height) / rgb->Ysize : 1.0;
            const double scale = std::max(scale_x, scale_y);
            if (status == 0) status = vips_resize(rgb, &resized, scale, "kernel", VIPS_KERNEL_LANCZOS3, nullptr);
            const int crop_left = status == 0 ? std::max(0, (resized->Xsize - canvas_width) / 2) : 0;
            const int crop_top = status == 0 ? std::max(0, (resized->Ysize - canvas_height) / 2) : 0;
            if (status == 0) status = vips_extract_area(resized, &cropped, crop_left, crop_top, canvas_width, canvas_height, nullptr);
            if (status == 0) status = vips_addalpha(cropped, &with_alpha, nullptr);
            if (status == 0) status = tag_as_srgb(with_alpha, &with_alpha_srgb);
            if (status == 0) status = vips_composite2(canvas, with_alpha_srgb, &next, VIPS_BLEND_MODE_OVER, nullptr);
            if (status == 0) { g_object_unref(canvas); canvas = next; next = nullptr; }
            if (source) g_object_unref(source); if (rotated) g_object_unref(rotated); if (rgb) g_object_unref(rgb);
            if (resized) g_object_unref(resized); if (cropped) g_object_unref(cropped); if (with_alpha) g_object_unref(with_alpha);
            if (with_alpha_srgb) g_object_unref(with_alpha_srgb);
            if (next) g_object_unref(next);
        }
    }

    for (jsize index = 0; status == 0 && index < layer_count; ++index) {
        if (left[index] < 0 || top[index] < 0 || width[index] < 1 || height[index] < 1 ||
            left[index] + width[index] > canvas_width || top[index] + height[index] > canvas_height ||
            (sampling[index] != 0 && sampling[index] != 1)) {
            status = -1;
            break;
        }
        auto path_value = static_cast<jstring>(env->GetObjectArrayElement(layer_paths, index));
        const std::string path = java_path(env, path_value);
        env->DeleteLocalRef(path_value);
        VipsImage* source = vips_image_new_from_file(path.c_str(), "access", VIPS_ACCESS_SEQUENTIAL, nullptr);
        VipsImage* uchar = nullptr; VipsImage* resized = nullptr; VipsImage* rgba = nullptr;
        VipsImage* positioned = nullptr; VipsImage* positioned_srgb = nullptr; VipsImage* next = nullptr;
        if (!source) status = -1;
        if (status == 0) status = vips_cast(source, &uchar, VIPS_FORMAT_UCHAR, nullptr);
        const double scale_x = status == 0 ? static_cast<double>(width[index]) / uchar->Xsize : 1.0;
        const double scale_y = status == 0 ? static_cast<double>(height[index]) / uchar->Ysize : 1.0;
        const VipsKernel kernel = sampling[index] == 0 ? VIPS_KERNEL_NEAREST : VIPS_KERNEL_LANCZOS3;
        if (status == 0) status = vips_resize(uchar, &resized, scale_x, "vscale", scale_y, "kernel", kernel, nullptr);
        if (status == 0) {
            if (resized->Bands == 4) status = vips_copy(resized, &rgba, "interpretation", VIPS_INTERPRETATION_sRGB, nullptr);
            else {
                VipsImage* rgb = nullptr;
                status = as_rgb_uchar(resized, &rgb);
                if (status == 0) status = vips_addalpha(rgb, &rgba, nullptr);
                if (rgb) g_object_unref(rgb);
            }
        }
        if (status == 0) status = vips_embed(rgba, &positioned, left[index], top[index], canvas_width, canvas_height,
                                             "extend", VIPS_EXTEND_BLACK, nullptr);
        if (status == 0) status = tag_as_srgb(positioned, &positioned_srgb);
        if (status == 0) status = vips_composite2(canvas, positioned_srgb, &next, VIPS_BLEND_MODE_OVER, nullptr);
        if (status == 0) { g_object_unref(canvas); canvas = next; next = nullptr; }
        if (source) g_object_unref(source); if (uchar) g_object_unref(uchar); if (resized) g_object_unref(resized);
        if (rgba) g_object_unref(rgba); if (positioned) g_object_unref(positioned); if (positioned_srgb) g_object_unref(positioned_srgb); if (next) g_object_unref(next);
    }

    VipsImage* flattened = nullptr;
    if (status == 0) status = vips_flatten(canvas, &flattened, nullptr);
    if (status == 0) status = vips_image_write_to_file(flattened, java_path(env, output_path).c_str(), "strip", TRUE, nullptr);
    env->ReleaseIntArrayElements(layer_left, left, JNI_ABORT);
    env->ReleaseIntArrayElements(layer_top, top, JNI_ABORT);
    env->ReleaseIntArrayElements(layer_width, width, JNI_ABORT);
    env->ReleaseIntArrayElements(layer_height, height, JNI_ABORT);
    env->ReleaseIntArrayElements(layer_sampling, sampling, JNI_ABORT);
    if (black) g_object_unref(black); if (canvas_rgb) g_object_unref(canvas_rgb); if (canvas_rgba) g_object_unref(canvas_rgba); if (canvas) g_object_unref(canvas);
    if (flattened) g_object_unref(flattened);
    return result(env, status == 0 ? "" : error_or("Poster composition failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_compressJpeg(JNIEnv* env, jclass, jstring in, jstring out, jint target_kb) {
    const JpegCompressionOutcome outcome = compress_jpeg(
        java_path(env, in), java_path(env, out), static_cast<off_t>(target_kb) * 1024, 95
    );
    return result(env, outcome.error);
}

extern "C" JNIEXPORT jobject JNICALL Java_com_phototoolkit_engine_VipsNative_compressJpegDetailed(JNIEnv* env, jclass, jstring in, jstring out, jint target_kb) {
    const JpegCompressionOutcome outcome = compress_jpeg(
        java_path(env, in), java_path(env, out), static_cast<off_t>(target_kb) * 1024, 95
    );
    return jpeg_compression_result(env, outcome);
}

extern "C" JNIEXPORT jobject JNICALL Java_com_phototoolkit_engine_VipsNative_compressJpegToBytes(
    JNIEnv* env,
    jclass,
    jstring in,
    jstring out,
    jlong target_bytes,
    jint max_quality
) {
    JpegCompressionOutcome outcome;
    if (target_bytes > 0 && static_cast<unsigned long long>(target_bytes) <=
            static_cast<unsigned long long>(std::numeric_limits<off_t>::max())) {
        outcome = compress_jpeg(
            java_path(env, in), java_path(env, out), static_cast<off_t>(target_bytes), max_quality
        );
    } else {
        outcome.error = "targetBytes is outside the supported range";
    }
    return jpeg_compression_result(env, outcome);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_productSquare(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        VipsImage* rgb_uchar = nullptr; VipsImage* scaled = nullptr; VipsImage* black = nullptr; VipsImage* white = nullptr; VipsImage* white_rgb = nullptr; VipsImage* inserted = nullptr;
        int status = as_rgb_uchar(s, &rgb_uchar);
        if (status == 0) {
            const double horizontal_scale = 1000.0 / rgb_uchar->Xsize;
            const double vertical_scale = 1000.0 / rgb_uchar->Ysize;
            const double scale = horizontal_scale < vertical_scale ? horizontal_scale : vertical_scale;
            status = vips_resize(rgb_uchar, &scaled, scale > 1.0 ? 1.0 : scale, nullptr);
        }
        if (status == 0) status = vips_black(&black, 1080, 1080, "bands", 3, nullptr);
        if (status == 0) status = vips_linear1(black, &white, 1.0, 255.0, nullptr);
        if (status == 0) status = tag_as_rgb(white, &white_rgb);
        if (status == 0) status = vips_insert(white_rgb, scaled, &inserted, (1080 - scaled->Xsize) / 2, (1080 - scaled->Ysize) / 2, nullptr);
        if (status == 0) status = tag_as_rgb(inserted, d);
        if (rgb_uchar) g_object_unref(rgb_uchar); if (scaled) g_object_unref(scaled); if (black) g_object_unref(black); if (white) g_object_unref(white); if (white_rgb) g_object_unref(white_rgb); if (inserted) g_object_unref(inserted);
        return status;
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_ocrEnhance(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        VipsImage* color = nullptr; VipsImage* gray = nullptr; VipsImage* equalized = nullptr;
        int status = as_three_bands(s, &color);
        if (status == 0) status = as_grayscale(color, &gray);
        if (status == 0) status = vips_hist_equal(gray, &equalized, nullptr);
        if (status == 0) status = vips_sharpen(equalized, d, nullptr);
        if (color) g_object_unref(color);
        if (gray) g_object_unref(gray);
        if (equalized) g_object_unref(equalized);
        return status;
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_blackWhite(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        VipsImage* color = nullptr; VipsImage* gray = nullptr; VipsImage* sharp = nullptr;
        VipsImage* local_mean = nullptr; VipsImage* threshold = nullptr;
        int status = as_three_bands(s, &color);
        if (status == 0) status = as_grayscale(color, &gray);
        if (status == 0) status = vips_sharpen(gray, &sharp, nullptr);
        if (status == 0) status = vips_gaussblur(sharp, &local_mean, 8.0, nullptr);
        if (status == 0) status = vips_linear1(local_mean, &threshold, 1.0, -8.0, nullptr);
        if (status == 0) status = vips_more(sharp, threshold, d, nullptr);
        if (color) g_object_unref(color);
        if (gray) g_object_unref(gray);
        if (sharp) g_object_unref(sharp);
        if (local_mean) g_object_unref(local_mean);
        if (threshold) g_object_unref(threshold);
        return status;
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_idPhotoCrop(JNIEnv* env, jclass, jstring in, jstring out) {
    return with_image(env, in, out, [](VipsImage* s, VipsImage** d) {
        constexpr int target_width = 413;
        constexpr int target_height = 591;
        const double horizontal_scale = static_cast<double>(target_width) / s->Xsize;
        const double vertical_scale = static_cast<double>(target_height) / s->Ysize;
        const double scale = horizontal_scale > vertical_scale ? horizontal_scale : vertical_scale;
        VipsImage* rgb_uchar = nullptr; VipsImage* resized = nullptr; VipsImage* cropped = nullptr;
        int status = as_rgb_uchar(s, &rgb_uchar);
        if (status == 0) status = vips_resize(rgb_uchar, &resized, scale, nullptr);
        if (status == 0) status = vips_extract_area(resized, &cropped, (resized->Xsize - target_width) / 2, (resized->Ysize - target_height) / 2, target_width, target_height, nullptr);
        if (status == 0) status = tag_as_rgb(cropped, d);
        if (rgb_uchar) g_object_unref(rgb_uchar);
        if (resized) g_object_unref(resized);
        if (cropped) g_object_unref(cropped);
        return status;
    });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_stripMetadata(JNIEnv* env, jclass, jstring in, jstring out) {
    ensure_vips();
    VipsImage* source = vips_status == 0 ? vips_image_new_from_file(java_path(env, in).c_str(), nullptr) : nullptr;
    if (source == nullptr) return result(env, error_or("Unable to load image"));
    int status = vips_image_write_to_file(source, java_path(env, out).c_str(), "strip", TRUE, nullptr);
    g_object_unref(source);
    return result(env, status == 0 ? "" : error_or("Metadata removal failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_visualSimilarity(JNIEnv* env, jclass, jstring in, jstring second) {
    ensure_vips();
    VipsImage* first = vips_status == 0 ? vips_image_new_from_file(java_path(env, in).c_str(), nullptr) : nullptr;
    VipsImage* other = vips_status == 0 ? vips_image_new_from_file(java_path(env, second).c_str(), nullptr) : nullptr;
    if (first == nullptr || other == nullptr) {
        if (first) g_object_unref(first);
        if (other) g_object_unref(other);
        return result(env, error_or("Unable to load images"));
    }
    VipsImage* gray_a = nullptr; VipsImage* gray_b = nullptr; VipsImage* thumb_a = nullptr; VipsImage* thumb_b = nullptr;
    int status = as_grayscale(first, &gray_a);
    if (status == 0) status = as_grayscale(other, &gray_b);
    if (status == 0) status = vips_thumbnail_image(gray_a, &thumb_a, 64, "height", 64, "crop", VIPS_INTERESTING_CENTRE, nullptr);
    if (status == 0) status = vips_thumbnail_image(gray_b, &thumb_b, 64, "height", 64, "crop", VIPS_INTERESTING_CENTRE, nullptr);
    size_t size_a = 0, size_b = 0;
    void* memory_a = status == 0 ? vips_image_write_to_memory(thumb_a, &size_a) : nullptr;
    void* memory_b = status == 0 ? vips_image_write_to_memory(thumb_b, &size_b) : nullptr;
    if (memory_a == nullptr || memory_b == nullptr || size_a != size_b) status = -1;
    double difference = 0.0;
    if (status == 0) {
        const auto* pixels_a = static_cast<const unsigned char*>(memory_a);
        const auto* pixels_b = static_cast<const unsigned char*>(memory_b);
        for (size_t index = 0; index < size_a; ++index) difference += std::abs(static_cast<int>(pixels_a[index]) - static_cast<int>(pixels_b[index]));
    }
    if (memory_a) g_free(memory_a);
    if (memory_b) g_free(memory_b);
    if (first) g_object_unref(first); if (other) g_object_unref(other);
    if (gray_a) g_object_unref(gray_a); if (gray_b) g_object_unref(gray_b);
    if (thumb_a) g_object_unref(thumb_a); if (thumb_b) g_object_unref(thumb_b);
    if (status != 0) return result(env, error_or("Unable to calculate similarity"));
    const double score = 100.0 * (1.0 - difference / (255.0 * size_a));
    return result(env, "similarity=" + std::to_string(score) + "%");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_replaceBackground(JNIEnv* env, jclass, jstring in, jstring out, jbyteArray alpha_mask, jint mask_width, jint mask_height, jint red, jint green, jint blue) {
    ensure_vips();
    if (mask_width < 1 || mask_height < 1 || alpha_mask == nullptr || red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) return result(env, "Invalid portrait mask or background color");
    const jsize expected_size = mask_width * mask_height;
    if (env->GetArrayLength(alpha_mask) != expected_size) return result(env, "Portrait mask size does not match dimensions");
    VipsImage* source = vips_status == 0 ? vips_image_new_from_file(java_path(env, in).c_str(), nullptr) : nullptr;
    if (source == nullptr) return result(env, error_or("Unable to load image"));
    jbyte* bytes = env->GetByteArrayElements(alpha_mask, nullptr);
    VipsImage* mask = bytes == nullptr ? nullptr : vips_image_new_from_memory_copy(bytes, static_cast<size_t>(expected_size), mask_width, mask_height, 1, VIPS_FORMAT_UCHAR);
    if (bytes != nullptr) env->ReleaseByteArrayElements(alpha_mask, bytes, JNI_ABORT);
    if (mask == nullptr) { g_object_unref(source); return result(env, error_or("Unable to create portrait mask")); }
    VipsImage* foreground_rgb = nullptr; VipsImage* scaled_mask = nullptr; VipsImage* foreground = nullptr;
    VipsImage* black = nullptr; VipsImage* background_values = nullptr; VipsImage* background_rgb = nullptr; VipsImage* background = nullptr; VipsImage* composed = nullptr; VipsImage* target = nullptr;
    int status = as_rgb_uchar(source, &foreground_rgb);
    const double scale = static_cast<double>(source->Xsize) / mask_width;
    const double vscale = static_cast<double>(source->Ysize) / mask_height;
    if (status == 0) status = vips_resize(mask, &scaled_mask, scale, "vscale", vscale, nullptr);
    if (status == 0) status = vips_bandjoin2(foreground_rgb, scaled_mask, &foreground, nullptr);
    if (status == 0) status = vips_black(&black, source->Xsize, source->Ysize, "bands", 3, nullptr);
    const double a[] = {1.0, 1.0, 1.0};
    const double b[] = {static_cast<double>(red), static_cast<double>(green), static_cast<double>(blue)};
    if (status == 0) status = vips_linear(black, &background_values, a, b, 3, nullptr);
    if (status == 0) status = tag_as_rgb(background_values, &background_rgb);
    if (status == 0) status = vips_addalpha(background_rgb, &background, nullptr);
    if (status == 0) status = vips_composite2(background, foreground, &composed, VIPS_BLEND_MODE_OVER, nullptr);
    if (status == 0) status = vips_flatten(composed, &target, nullptr);
    if (status == 0) status = vips_image_write_to_file(target, java_path(env, out).c_str(), nullptr);
    g_object_unref(source); g_object_unref(mask);
    if (foreground_rgb) g_object_unref(foreground_rgb); if (scaled_mask) g_object_unref(scaled_mask); if (foreground) g_object_unref(foreground);
    if (black) g_object_unref(black); if (background_values) g_object_unref(background_values); if (background_rgb) g_object_unref(background_rgb); if (background) g_object_unref(background); if (composed) g_object_unref(composed); if (target) g_object_unref(target);
    return result(env, status == 0 ? "" : error_or("Background replacement failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_extractPortrait(JNIEnv* env, jclass, jstring in, jstring out, jbyteArray alpha_mask, jint mask_width, jint mask_height) {
    ensure_vips();
    if (mask_width < 1 || mask_height < 1 || alpha_mask == nullptr) return result(env, "Invalid portrait mask");
    const jsize expected_size = mask_width * mask_height;
    if (env->GetArrayLength(alpha_mask) != expected_size) return result(env, "Portrait mask size does not match dimensions");
    VipsImage* source = vips_status == 0 ? vips_image_new_from_file(java_path(env, in).c_str(), nullptr) : nullptr;
    if (source == nullptr) return result(env, error_or("Unable to load image"));
    jbyte* bytes = env->GetByteArrayElements(alpha_mask, nullptr);
    VipsImage* mask = bytes == nullptr ? nullptr : vips_image_new_from_memory_copy(bytes, static_cast<size_t>(expected_size), mask_width, mask_height, 1, VIPS_FORMAT_UCHAR);
    if (bytes != nullptr) env->ReleaseByteArrayElements(alpha_mask, bytes, JNI_ABORT);
    if (mask == nullptr) { g_object_unref(source); return result(env, error_or("Unable to create portrait mask")); }
    VipsImage* rgb_uchar = nullptr; VipsImage* scaled_mask = nullptr; VipsImage* portrait = nullptr; VipsImage* tagged = nullptr;
    int status = as_rgb_uchar(source, &rgb_uchar);
    if (status == 0) status = vips_resize(mask, &scaled_mask, static_cast<double>(source->Xsize) / mask_width, "vscale", static_cast<double>(source->Ysize) / mask_height, nullptr);
    if (status == 0) status = vips_bandjoin2(rgb_uchar, scaled_mask, &portrait, nullptr);
    if (status == 0) status = tag_as_rgb(portrait, &tagged);
    if (status == 0) status = vips_image_write_to_file(tagged, java_path(env, out).c_str(), nullptr);
    g_object_unref(source); g_object_unref(mask);
    if (rgb_uchar) g_object_unref(rgb_uchar); if (scaled_mask) g_object_unref(scaled_mask); if (portrait) g_object_unref(portrait); if (tagged) g_object_unref(tagged);
    return result(env, status == 0 ? "" : error_or("Portrait extraction failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_createIdPhoto(
        JNIEnv* env, jclass, jstring in, jstring out, jbyteArray alpha_mask, jint mask_width, jint mask_height,
        jdouble face_left, jdouble face_top, jdouble face_width, jdouble face_height,
        jint output_width, jint output_height, jint output_dpi, jdouble face_height_ratio, jdouble face_top_ratio,
        jboolean auto_composition,
        jdouble face_height_min_ratio, jdouble face_height_max_ratio,
        jdouble face_top_min_ratio, jdouble face_top_max_ratio, jdouble face_center_x_ratio,
        jdouble portrait_bottom_ratio, jdouble max_portrait_width_ratio, jboolean prefer_bottom_alignment,
        jdoubleArray composition_metrics, jboolean strict_composition, jboolean preserve_background,
        jint red, jint green, jint blue) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    if (alpha_mask == nullptr || composition_metrics == nullptr || env->GetArrayLength(composition_metrics) < 2 ||
        mask_width < 1 || mask_height < 1 || output_width < 1 || output_height < 1 || output_dpi < 72 || output_dpi > 1200 ||
        !std::isfinite(face_left) || !std::isfinite(face_top) || !std::isfinite(face_width) || !std::isfinite(face_height) ||
        face_width <= 0.0 || face_height <= 0.0 || face_height_ratio <= 0.0 || face_height_ratio >= 1.0 ||
        face_top_ratio < 0.0 || face_top_ratio >= 1.0 || face_height_min_ratio <= 0.0 ||
        face_height_max_ratio < face_height_min_ratio || face_height_ratio < face_height_min_ratio ||
        face_height_ratio > face_height_max_ratio || face_top_min_ratio < 0.0 ||
        face_top_max_ratio < face_top_min_ratio || face_top_ratio < face_top_min_ratio ||
        face_top_ratio > face_top_max_ratio || face_center_x_ratio < 0.0 || face_center_x_ratio > 1.0 ||
        portrait_bottom_ratio < 0.5 || portrait_bottom_ratio > 1.0 ||
        max_portrait_width_ratio < 0.5 || max_portrait_width_ratio > 3.0 ||
        red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
        return result(env, "Invalid ID photo parameters");
    }
    const jlong expected_size = static_cast<jlong>(mask_width) * mask_height;
    if (expected_size > std::numeric_limits<jsize>::max() || env->GetArrayLength(alpha_mask) != expected_size) {
        return result(env, "Portrait mask size does not match dimensions");
    }

    VipsImage* source = vips_image_new_from_file(java_path(env, in).c_str(), nullptr);
    if (source == nullptr) return result(env, error_or("Unable to load image"));
    if (face_left < 0.0 || face_top < 0.0 || face_left + face_width > source->Xsize || face_top + face_height > source->Ysize) {
        g_object_unref(source);
        return result(env, "Face rectangle is outside the image");
    }

    const int head_shoulder_top = static_cast<int>(std::floor(
            (face_top - face_height * 0.45) * mask_height / source->Ysize));
    const int head_shoulder_bottom = static_cast<int>(std::ceil(
            (face_top + face_height * 2.75) * mask_height / source->Ysize));
    const int anchor_left = static_cast<int>(std::floor(face_left * mask_width / source->Xsize));
    const int anchor_top = static_cast<int>(std::floor(face_top * mask_height / source->Ysize));
    const int anchor_right = static_cast<int>(std::ceil(
            (face_left + face_width) * mask_width / source->Xsize));
    jbyte* bytes = env->GetByteArrayElements(alpha_mask, nullptr);
    const AlphaBounds alpha_bounds = bytes == nullptr ? AlphaBounds{} : find_alpha_bounds(
            bytes, mask_width, mask_height, head_shoulder_top, head_shoulder_bottom,
            anchor_left, anchor_top, anchor_right, 24);
    VipsImage* mask = bytes == nullptr ? nullptr : vips_image_new_from_memory_copy(bytes, static_cast<size_t>(expected_size), mask_width, mask_height, 1, VIPS_FORMAT_UCHAR);
    if (bytes != nullptr) env->ReleaseByteArrayElements(alpha_mask, bytes, JNI_ABORT);
    if (mask == nullptr) {
        g_object_unref(source);
        return result(env, error_or("Unable to create portrait mask"));
    }

    VipsImage* rgb = nullptr; VipsImage* rgb_uchar = nullptr; VipsImage* srgb = nullptr; VipsImage* scaled_rgb = nullptr; VipsImage* sharpened_rgb = nullptr;
    VipsImage* scaled_mask = nullptr; VipsImage* foreground = nullptr; VipsImage* visible = nullptr; VipsImage* positioned = nullptr; VipsImage* positioned_srgb = nullptr;
    VipsImage* bottom_line = nullptr; VipsImage* extruded = nullptr; VipsImage* full_visible = nullptr;
    VipsImage* black = nullptr; VipsImage* transparent = nullptr; VipsImage* background_rgb = nullptr; VipsImage* background = nullptr; VipsImage* background_srgb = nullptr; VipsImage* composed = nullptr; VipsImage* target = nullptr; VipsImage* tagged = nullptr;
    const double mask_to_source_x = static_cast<double>(source->Xsize) / mask_width;
    const double mask_to_source_y = static_cast<double>(source->Ysize) / mask_height;
    const double portrait_left = alpha_bounds.valid ? alpha_bounds.left * mask_to_source_x : face_left;
    const double portrait_top = alpha_bounds.valid ? alpha_bounds.top * mask_to_source_y : face_top;
    const double portrait_right = alpha_bounds.valid ? alpha_bounds.right * mask_to_source_x : face_left + face_width;
    const double portrait_bottom = alpha_bounds.valid ? alpha_bounds.bottom * mask_to_source_y : face_top + face_height;
    const double portrait_width = std::max(1.0, portrait_right - portrait_left);
    const double face_scale = output_height * face_height_ratio / face_height;
    const double min_face_scale = output_height * face_height_min_ratio / face_height;
    const double max_face_scale = output_height * face_height_max_ratio / face_height;
    const double desired_face_top = output_height * face_top_ratio;
    // 以脸中心为锚点，分别计算左右人物轮廓铺满画布所需的缩放，避免人物被横向拉伸。
    const double source_face_center = face_left + face_width * 0.5;
    const double left_fill_scale = output_width * max_portrait_width_ratio * face_center_x_ratio /
            std::max(1.0, source_face_center - portrait_left);
    const double right_fill_scale = output_width * max_portrait_width_ratio * (1.0 - face_center_x_ratio) /
            std::max(1.0, portrait_right - source_face_center);
    const double width_fill_scale = std::max(left_fill_scale, right_fill_scale);
    const double bottom_fill_scale = (output_height * portrait_bottom_ratio - desired_face_top) /
            std::max(1.0, portrait_bottom - face_top);
    const bool fill_portrait = prefer_bottom_alignment &&
            alpha_bounds.valid && !strict_composition;
    const double requested_scale = fill_portrait
            ? std::max({face_scale, width_fill_scale, bottom_fill_scale})
            : face_scale;
    // 铺满宽度和贴底不能突破证件照允许的头部高度区间；无法同时满足时优先保证头部合规。
    double scale = std::clamp(requested_scale, min_face_scale, max_face_scale);
    if (!fill_portrait) {
        const double max_width_scale = output_width * max_portrait_width_ratio / portrait_width;
        if (!preserve_background && max_width_scale >= min_face_scale) {
            scale = std::min(scale, max_width_scale);
        }
    }
    int status = std::isfinite(scale) && scale > 0.0 ? 0 : -1;
    if (status == 0) status = as_three_bands(source, &rgb);
    if (status == 0) status = vips_cast(rgb, &rgb_uchar, VIPS_FORMAT_UCHAR, nullptr);
    if (status == 0) status = vips_copy(rgb_uchar, &srgb, "interpretation", VIPS_INTERPRETATION_sRGB, nullptr);
    if (status == 0) status = vips_resize(
            srgb, &scaled_rgb, scale, "kernel", VIPS_KERNEL_LANCZOS3, nullptr);
    if (status == 0) status = vips_sharpen(
            scaled_rgb, &sharpened_rgb, "sigma", 0.6, nullptr);
    if (!preserve_background) {
        if (status == 0) status = vips_resize(mask, &scaled_mask, scale * source->Xsize / mask_width,
                                                "vscale", scale * source->Ysize / mask_height, nullptr);
        if (status == 0) status = vips_bandjoin2(sharpened_rgb, scaled_mask, &foreground, nullptr);
    }

    int desired_left = static_cast<int>(std::lround(output_width * face_center_x_ratio -
                                                          (face_left + face_width * 0.5) * scale));
    const double face_aligned_top = desired_face_top - face_top * scale;
    const double min_positioned_top = output_height * face_top_min_ratio - face_top * scale;
    const double max_positioned_top = output_height * face_top_max_ratio - face_top * scale;
    const double bottom_aligned_top = output_height * portrait_bottom_ratio - portrait_bottom * scale;
    // 按传入规格处理人物底部，同时将头顶位置限制在同一规格的合法区间内。
    const double bottom_aligned_face_top_ratio =
            (face_top * scale + bottom_aligned_top) / output_height;
    // 底部贴齐只能作为次要目标，不能把头顶推到规则区间边缘。
    const bool bottom_alignment_valid = fill_portrait &&
            bottom_aligned_face_top_ratio >= face_top_min_ratio &&
            bottom_aligned_face_top_ratio <= face_top_max_ratio;
    const double auto_positioned_top = output_height * 0.5 -
            (portrait_top + portrait_bottom) * scale * 0.5;
    const double requested_top = auto_composition
                                 ? auto_positioned_top
                                 : bottom_alignment_valid
                                 ? bottom_aligned_top
                                 : face_aligned_top;
    const double auto_min_top = -portrait_top * scale;
    const double auto_max_top = output_height - portrait_bottom * scale;
    const double positioned_top = auto_composition
            ? std::clamp(requested_top, std::min(auto_min_top, auto_max_top),
                         std::max(auto_min_top, auto_max_top))
            : std::clamp(requested_top, min_positioned_top, max_positioned_top);
    int desired_top = static_cast<int>(std::lround(positioned_top));
    if (preserve_background) {
        // 限制编辑模式只能从原图内部裁剪，不能用纯色或生成像素填补缺失背景。
        const int max_crop_left = sharpened_rgb == nullptr ? -1 : sharpened_rgb->Xsize - output_width;
        const int max_crop_top = sharpened_rgb == nullptr ? -1 : sharpened_rgb->Ysize - output_height;
        const bool crop_size_fits = status == 0 && max_crop_left >= 0 && max_crop_top >= 0;
        const int crop_left = crop_size_fits ? std::clamp(-desired_left, 0, max_crop_left) : 0;
        const int crop_top = crop_size_fits ? std::clamp(-desired_top, 0, max_crop_top) : 0;
        desired_left = -crop_left;
        desired_top = -crop_top;
        const bool crop_fits = crop_size_fits && crop_left + output_width <= sharpened_rgb->Xsize &&
                               crop_top + output_height <= sharpened_rgb->Ysize;
        const bool insufficient_background = status == 0 && !crop_fits;
        if (insufficient_background) status = -1;
        const jdouble actual_metrics[] = {
                face_height * scale / output_height,
                (face_top * scale + desired_top) / output_height,
        };
        env->SetDoubleArrayRegion(composition_metrics, 0, 2, actual_metrics);
        if (status == 0) status = vips_extract_area(
                sharpened_rgb, &target, crop_left, crop_top, output_width, output_height, nullptr);
        const double pixels_per_millimeter = output_dpi / 25.4;
        if (status == 0) status = vips_copy(target, &tagged,
                                            "interpretation", VIPS_INTERPRETATION_sRGB,
                                            "xres", pixels_per_millimeter,
                                            "yres", pixels_per_millimeter,
                                            nullptr);
        if (status == 0) status = write_id_photo_output(tagged, java_path(env, out));
        g_object_unref(source); g_object_unref(mask);
        if (rgb) g_object_unref(rgb); if (rgb_uchar) g_object_unref(rgb_uchar); if (srgb) g_object_unref(srgb); if (scaled_rgb) g_object_unref(scaled_rgb); if (sharpened_rgb) g_object_unref(sharpened_rgb);
        if (target) g_object_unref(target); if (tagged) g_object_unref(tagged);
        return result(env, status == 0 ? "" : insufficient_background
                ? "Source image does not contain enough background for the required crop"
                : error_or("ID photo source-preserving crop failed"));
    }
    const jdouble actual_metrics[] = {
            face_height * scale / output_height,
            (face_top * scale + desired_top) / output_height,
    };
    env->SetDoubleArrayRegion(composition_metrics, 0, 2, actual_metrics);
    const int source_left = std::max(0, -desired_left);
    const int source_top = std::max(0, -desired_top);
    const int destination_left = std::max(0, desired_left);
    const int destination_top = std::max(0, desired_top);
    const int visible_width = std::min(foreground == nullptr ? 0 : foreground->Xsize - source_left, output_width - destination_left);
    const int visible_height = std::min(foreground == nullptr ? 0 : foreground->Ysize - source_top, output_height - destination_top);
    if (status == 0 && (visible_width < 1 || visible_height < 1)) status = -1;
    if (status == 0) status = vips_extract_area(foreground, &visible, source_left, source_top, visible_width, visible_height, nullptr);

    VipsImage* insert_subject = visible;
    const int bottom_gap = output_height - (destination_top + visible_height);
    // 当人像下边缘未触达画布底边时，垂直向下延展最底部的衣服像素，彻底消除悬空断层。
    if (status == 0 && bottom_gap > 0 && visible != nullptr && visible_height > 0) {
        if (vips_extract_area(visible, &bottom_line, 0, visible_height - 1, visible_width, 1, nullptr) == 0) {
            if (vips_replicate(bottom_line, &extruded, 1, bottom_gap, nullptr) == 0) {
                if (vips_join(visible, extruded, &full_visible, VIPS_DIRECTION_VERTICAL, nullptr) == 0) {
                    insert_subject = full_visible;
                }
            }
        }
    }

    // 先放入透明 RGBA 画布，再与背景合成，避免画布外扩的黑色像素污染底部和左右边缘。
    if (status == 0) status = vips_black(&transparent, output_width, output_height, "bands", 4, nullptr);
    if (status == 0) status = vips_insert(transparent, insert_subject, &positioned, destination_left, destination_top, nullptr);
    // bandjoin/embed 会将解释标记降为 multiband；合成前恢复实际的 sRGB 语义。
    if (status == 0) status = vips_copy(positioned, &positioned_srgb, "interpretation", VIPS_INTERPRETATION_sRGB, nullptr);
    if (status == 0) status = vips_black(&black, output_width, output_height, "bands", 3, nullptr);
    const double a[] = {1.0, 1.0, 1.0};
    const double b[] = {static_cast<double>(red), static_cast<double>(green), static_cast<double>(blue)};
    if (status == 0) status = vips_linear(black, &background_rgb, a, b, 3, nullptr);
    if (status == 0) status = vips_addalpha(background_rgb, &background, nullptr);
    if (status == 0) status = vips_copy(background, &background_srgb, "interpretation", VIPS_INTERPRETATION_sRGB, nullptr);
    if (status == 0) status = vips_composite2(background_srgb, positioned_srgb, &composed, VIPS_BLEND_MODE_OVER, nullptr);
    if (status == 0) status = vips_flatten(composed, &target, nullptr);
    const double pixels_per_millimeter = output_dpi / 25.4;
    if (status == 0) status = vips_copy(target, &tagged,
                                        "interpretation", VIPS_INTERPRETATION_sRGB,
                                        "xres", pixels_per_millimeter,
                                        "yres", pixels_per_millimeter,
                                        nullptr);
    if (status == 0) status = write_id_photo_output(tagged, java_path(env, out));

    g_object_unref(source); g_object_unref(mask);
    if (rgb) g_object_unref(rgb); if (rgb_uchar) g_object_unref(rgb_uchar); if (srgb) g_object_unref(srgb); if (scaled_rgb) g_object_unref(scaled_rgb); if (sharpened_rgb) g_object_unref(sharpened_rgb);
    if (scaled_mask) g_object_unref(scaled_mask); if (foreground) g_object_unref(foreground); if (visible) g_object_unref(visible);
    if (bottom_line) g_object_unref(bottom_line); if (extruded) g_object_unref(extruded); if (full_visible) g_object_unref(full_visible);
    if (positioned) g_object_unref(positioned); if (positioned_srgb) g_object_unref(positioned_srgb);
    if (black) g_object_unref(black); if (transparent) g_object_unref(transparent); if (background_rgb) g_object_unref(background_rgb); if (background) g_object_unref(background); if (background_srgb) g_object_unref(background_srgb); if (composed) g_object_unref(composed); if (target) g_object_unref(target); if (tagged) g_object_unref(tagged);
    return result(env, status == 0 ? "" : error_or("ID photo composition failed"));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_phototoolkit_engine_VipsNative_createIdPhotoSheet(
        JNIEnv* env, jclass, jstring in, jstring out, jint paper_width, jint paper_height, jint columns, jint rows,
        jint copies, jint margin, jint gap) {
    ensure_vips();
    if (vips_status != 0) return result(env, error_or("vips_init failed"));
    if (paper_width < 1 || paper_height < 1 || columns < 1 || rows < 1 || copies < 1 || margin < 0 || gap < 0 ||
        columns > 20 || rows > 20 || copies > columns * rows) return result(env, "Invalid ID photo sheet parameters");
    const int available_width = paper_width - margin * 2 - gap * (columns - 1);
    const int available_height = paper_height - margin * 2 - gap * (rows - 1);
    const int cell_width = available_width / columns;
    const int cell_height = available_height / rows;
    if (cell_width < 1 || cell_height < 1) return result(env, "ID photo sheet has no usable cell area");

    VipsImage* source = vips_image_new_from_file(java_path(env, in).c_str(), nullptr);
    if (source == nullptr) return result(env, error_or("Unable to load ID photo"));
    VipsImage* rgb = nullptr; VipsImage* rgb_uchar = nullptr; VipsImage* black = nullptr; VipsImage* canvas = nullptr; VipsImage* tagged = nullptr;
    int status = as_three_bands(source, &rgb);
    if (status == 0) status = vips_cast(rgb, &rgb_uchar, VIPS_FORMAT_UCHAR, nullptr);
    // 冲印排版必须保持单张照片的原始像素尺寸；放不下时失败，不能静默缩放物理规格。
    if (status == 0 && (rgb_uchar->Xsize > cell_width || rgb_uchar->Ysize > cell_height)) status = -1;
    if (status == 0) status = vips_black(&black, paper_width, paper_height, "bands", 3, nullptr);
    if (status == 0) status = vips_linear1(black, &canvas, 1.0, 255.0, nullptr);
    for (int index = 0; status == 0 && index < copies; ++index) {
        const int column = index % columns;
        const int row = index / columns;
        const int x = margin + column * (cell_width + gap) + (cell_width - rgb_uchar->Xsize) / 2;
        const int y = margin + row * (cell_height + gap) + (cell_height - rgb_uchar->Ysize) / 2;
        VipsImage* next = nullptr;
        status = vips_insert(canvas, rgb_uchar, &next, x, y, nullptr);
        if (status == 0) {
            g_object_unref(canvas);
            canvas = next;
        }
    }
    const double default_resolution = 300.0 / 25.4;
    const double x_resolution = source->Xres > 0.0 ? source->Xres : default_resolution;
    const double y_resolution = source->Yres > 0.0 ? source->Yres : default_resolution;
    if (status == 0) status = vips_copy(canvas, &tagged,
                                        "interpretation", VIPS_INTERPRETATION_sRGB,
                                        "xres", x_resolution,
                                        "yres", y_resolution,
                                        nullptr);
    if (status == 0) status = vips_image_write_to_file(tagged, java_path(env, out).c_str(), "strip", FALSE, nullptr);
    g_object_unref(source);
    if (rgb) g_object_unref(rgb); if (rgb_uchar) g_object_unref(rgb_uchar); if (black) g_object_unref(black); if (canvas) g_object_unref(canvas); if (tagged) g_object_unref(tagged);
    return result(env, status == 0 ? "" : error_or("ID photo sheet composition failed"));
}
