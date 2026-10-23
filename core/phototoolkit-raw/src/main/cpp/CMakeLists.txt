cmake_minimum_required(VERSION 3.22.1)
project(phototoolkit_raw LANGUAGES CXX)

foreach(REQUIRED_VAR IN ITEMS RAWSDK_ROOT RAW_JNI_ROOT ENGINE_JNI_ROOT VIPSSDK_ROOT)
    if(NOT DEFINED ${REQUIRED_VAR})
        message(FATAL_ERROR "${REQUIRED_VAR} was not supplied")
    endif()
endforeach()

set(RAW_INCLUDE_ROOT "${RAWSDK_ROOT}/include")
set(RAW_LIBRARY "${RAW_JNI_ROOT}/${CMAKE_ANDROID_ARCH_ABI}/libraw.so")
set(VIPS_INCLUDE_ROOT "${VIPSSDK_ROOT}/include")
set(VIPS_LIBRARY "${ENGINE_JNI_ROOT}/${CMAKE_ANDROID_ARCH_ABI}/libvips.so")

foreach(REQUIRED_FILE IN ITEMS
        "${RAW_INCLUDE_ROOT}/libraw/libraw.h"
        "${RAW_LIBRARY}"
        "${VIPS_INCLUDE_ROOT}/vips/vips.h"
        "${VIPS_LIBRARY}")
    if(NOT EXISTS "${REQUIRED_FILE}")
        message(FATAL_ERROR "Required RAW build input not found: ${REQUIRED_FILE}")
    endif()
endforeach()

add_library(raw SHARED IMPORTED)
set_target_properties(raw PROPERTIES IMPORTED_LOCATION "${RAW_LIBRARY}")
add_library(vips SHARED IMPORTED)
set_target_properties(vips PROPERTIES IMPORTED_LOCATION "${VIPS_LIBRARY}")

foreach(VIPS_RUNTIME_NAME IN ITEMS glib-2.0 gobject-2.0)
    set(VIPS_RUNTIME_LIBRARY "${ENGINE_JNI_ROOT}/${CMAKE_ANDROID_ARCH_ABI}/lib${VIPS_RUNTIME_NAME}.so")
    if(NOT EXISTS "${VIPS_RUNTIME_LIBRARY}")
        message(FATAL_ERROR "Runtime library not found: ${VIPS_RUNTIME_LIBRARY}")
    endif()
    add_library("vips_${VIPS_RUNTIME_NAME}" SHARED IMPORTED)
    set_target_properties("vips_${VIPS_RUNTIME_NAME}" PROPERTIES IMPORTED_LOCATION "${VIPS_RUNTIME_LIBRARY}")
endforeach()

add_library(raw_jni SHARED native_raw.cpp)
target_link_options(raw_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(raw_jni PRIVATE
    "${RAW_INCLUDE_ROOT}"
    "${VIPS_INCLUDE_ROOT}"
    "${VIPS_INCLUDE_ROOT}/glib-2.0")
target_link_libraries(raw_jni PRIVATE raw vips vips_gobject-2.0 vips_glib-2.0 log)
