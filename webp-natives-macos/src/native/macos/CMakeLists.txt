cmake_minimum_required(VERSION 3.20)
project(webp_natives LANGUAGES C CXX)

if (NOT APPLE)
    message(FATAL_ERROR "webp-natives-macos builds only on macOS.")
endif()

if (NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is required to build the JNI dylib.")
endif()
set(JAVA_HOME $ENV{JAVA_HOME})

if (NOT DEFINED WEBP_JNI_SOURCE)
    message(FATAL_ERROR "WEBP_JNI_SOURCE must be set via -DWEBP_JNI_SOURCE=<path>")
endif()

# Per-arch build (host's default). Universal builds (x86_64;arm64) break
# libwebp 1.6.0's per-file SSE2 flags: the gamma.c sharpyuv reference is
# tagged with -mno-sse2, which clobbers x86_64 ABI requirements during
# the x86_64 slice. Easier to keep each arch as its own dylib and run two
# CI jobs (macos-13 = Intel, macos-14 = Apple Silicon) than to lipo.

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if (NOT CMAKE_OSX_DEPLOYMENT_TARGET)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "" FORCE)
endif()

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ─────────────────────────────────────────────────────────────────
#  libwebp dependency — same trimmed config as windows/linux.
# ─────────────────────────────────────────────────────────────────
include(FetchContent)

set(WEBP_BUILD_ANIM_UTILS    OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP         OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP         OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP      OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP      OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP         OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO      OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX    OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX       OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS        OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBP_JS       OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST      OFF CACHE BOOL "" FORCE)
set(WEBP_LINK_STATIC         ON  CACHE BOOL "" FORCE)
set(WEBP_NEAR_LOSSLESS       ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libwebp
    GIT_REPOSITORY https://chromium.googlesource.com/webm/libwebp
    GIT_TAG        v1.6.0
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(libwebp)

# ─────────────────────────────────────────────────────────────────
#  JNI bridge dylib
# ─────────────────────────────────────────────────────────────────
add_library(webp_natives SHARED "${WEBP_JNI_SOURCE}")

target_compile_features(webp_natives PRIVATE c_std_11)
target_compile_options(webp_natives PRIVATE -fvisibility=hidden -O3)

target_include_directories(webp_natives PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/darwin"
)

target_link_libraries(webp_natives PRIVATE webp webpdemux)

set_target_properties(webp_natives PROPERTIES
    OUTPUT_NAME "webp_natives"
)
