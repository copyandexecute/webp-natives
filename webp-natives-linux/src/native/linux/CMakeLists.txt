cmake_minimum_required(VERSION 3.20)
project(webp_natives LANGUAGES C CXX)

if (NOT UNIX OR APPLE)
    message(FATAL_ERROR "webp-natives-linux builds only on Linux.")
endif()

if (NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is required to build the JNI shared object.")
endif()
set(JAVA_HOME $ENV{JAVA_HOME})

if (NOT DEFINED WEBP_JNI_SOURCE)
    message(FATAL_ERROR "WEBP_JNI_SOURCE must be set via -DWEBP_JNI_SOURCE=<path>")
endif()

set(WEBP_CORE_NATIVE "${CMAKE_CURRENT_LIST_DIR}/../../../../webp-natives-core/src/native")
set(WEBP_NATIVE_SOURCES
    "${WEBP_JNI_SOURCE}"
    "${WEBP_CORE_NATIVE}/webm/webm_jni_stub.c"
)

# Position-Independent Code is mandatory for .so on Linux.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Default to Release if not specified — affects libwebp's own optimizations too.
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ─────────────────────────────────────────────────────────────────
#  libwebp dependency — built from source via FetchContent.
#  Same trimmed config as the Windows module: only encode+decode libs.
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
#  JNI bridge .so
# ─────────────────────────────────────────────────────────────────
add_library(webp_natives SHARED ${WEBP_NATIVE_SOURCES})

target_compile_features(webp_natives PRIVATE c_std_11)
target_compile_options(webp_natives PRIVATE -fvisibility=hidden -O3)

target_include_directories(webp_natives PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/linux"
)

target_link_libraries(webp_natives PRIVATE webp webpdemux)

# Strip unneeded symbols to keep the .so small (saves ~30% on stripped builds).
set_target_properties(webp_natives PROPERTIES
    OUTPUT_NAME "webp_natives"
    LINK_FLAGS_RELEASE "-Wl,--gc-sections -Wl,--strip-all"
)
