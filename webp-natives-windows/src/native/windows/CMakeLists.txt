cmake_minimum_required(VERSION 3.20)
project(webp_natives LANGUAGES C CXX)

if (NOT WIN32)
    message(FATAL_ERROR "webp-natives-windows builds only on Windows.")
endif()

if (NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is required to build the JNI DLL.")
endif()
set(JAVA_HOME $ENV{JAVA_HOME})

if (NOT DEFINED WEBP_JNI_SOURCE)
    message(FATAL_ERROR "WEBP_JNI_SOURCE must be set via -DWEBP_JNI_SOURCE=<path>")
endif()

# VP9/WebM stack is on by default; -DWEBM_ENABLED=OFF builds WebP only.
option(WEBM_ENABLED "Build the VP9/WebM encode+decode stack (needs vcpkg/libvpx)" ON)

set(WEBP_CORE_NATIVE "${CMAKE_CURRENT_LIST_DIR}/../../../../webp-natives-core/src/native")
set(WEBP_NATIVE_SOURCES "${WEBP_JNI_SOURCE}")

# Statically link the MSVC C++ runtime (/MT) so the DLL doesn't depend on
# msvcp140 / vcruntime140 being present on the user's machine. (Pattern
# borrowed from endlesssource/mediainterface — without this the DLL
# crashed with EXCEPTION_ACCESS_VIOLATION on certain runtime mismatches.)
# Set before any add_library() so webm_static (built in WebmDeps) inherits it.
cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded" CACHE STRING "" FORCE)

# WebM (VP9) deps + sources — libvpx via vcpkg, libwebm via FetchContent.
if (WEBM_ENABLED)
    include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/WebmDeps.cmake")
    list(APPEND WEBP_NATIVE_SOURCES
        "${WEBP_CORE_NATIVE}/webm/webm_codec.cpp"
        "${WEBP_CORE_NATIVE}/webm/webm_jni.cpp"
    )
endif()

# ─────────────────────────────────────────────────────────────────
#  libwebp dependency — built from source via FetchContent.
#  We only need the static encoder/decoder libs; everything else
#  (cwebp/dwebp CLI tools, gif2webp, examples) is disabled.
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
#  JNI bridge DLL
# ─────────────────────────────────────────────────────────────────
add_library(webp_natives SHARED ${WEBP_NATIVE_SOURCES})

target_compile_features(webp_natives PRIVATE c_std_11 cxx_std_11)
target_compile_definitions(webp_natives PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)

target_include_directories(webp_natives PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32"
)

target_link_libraries(webp_natives PRIVATE webp webpdemux)
if (WEBM_ENABLED)
    target_link_libraries(webp_natives PRIVATE webm_static webm_vpx)
endif()

set_target_properties(webp_natives PROPERTIES
    OUTPUT_NAME "webp_natives"
    PREFIX ""
)
