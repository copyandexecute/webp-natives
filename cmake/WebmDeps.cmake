# ─────────────────────────────────────────────────────────────────
#  WebM (VP9) native dependencies — cross-platform.
#
#  Included by each platform CMakeLists only when WEBM_ENABLED is ON
#  (the default). Produces two link targets the JNI library consumes:
#
#    webm_vpx     INTERFACE  libvpx (VP9 encode/decode) — from the vcpkg
#                            toolchain (handles NASM / per-arch targets).
#    webm_static  STATIC     libwebm (Matroska/WebM mux+parse) — built
#                            from a pinned source tag via FetchContent.
#
#  libvpx has no CMake build of its own and needs NASM + per-(os,arch)
#  configure targets; vcpkg's port solves that on every platform, which
#  is why we don't shell out to MSYS2/autotools by hand anymore.
# ─────────────────────────────────────────────────────────────────

if (NOT WEBM_ENABLED)
    return()
endif()

include(FetchContent)

# ── libvpx (vcpkg) ────────────────────────────────────────────────
# The vcpkg toolchain file (wired by the Gradle build) puts the
# installed prefix on CMAKE_PREFIX_PATH. We resolve vpx with a plain
# find_path/find_library pair instead of find_package(CONFIG): the
# vcpkg libvpx port's exported config-target name has drifted between
# versions, but the header + static lib are always discoverable here.
find_path(VPX_INCLUDE_DIR
    NAMES vpx/vpx_encoder.h
    DOC "libvpx include directory (provided by vcpkg)")
find_library(VPX_LIBRARY
    NAMES vpx
    DOC "libvpx static library (provided by vcpkg)")

if (NOT VPX_INCLUDE_DIR OR NOT VPX_LIBRARY)
    message(FATAL_ERROR
        "libvpx not found, but WebM (VP9) is enabled.\n"
        "Pass -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake "
        "(the Gradle build wires this automatically when a vcpkg root is "
        "available), or build WebP-only with -Pwebm=false / -DWEBM_ENABLED=OFF.")
endif()

add_library(webm_vpx INTERFACE)
target_include_directories(webm_vpx INTERFACE "${VPX_INCLUDE_DIR}")
target_link_libraries(webm_vpx INTERFACE "${VPX_LIBRARY}")

# ── libyuv (vcpkg) ────────────────────────────────────────────────
# SIMD ARGB<->I420 conversion for the encode/decode hot path.
find_path(YUV_INCLUDE_DIR
    NAMES libyuv.h
    DOC "libyuv include directory (provided by vcpkg)")
find_library(YUV_LIBRARY
    NAMES yuv libyuv
    DOC "libyuv static library (provided by vcpkg)")

if (NOT YUV_INCLUDE_DIR OR NOT YUV_LIBRARY)
    message(FATAL_ERROR
        "libyuv not found, but WebM (VP9) is enabled. It is resolved from the "
        "same vcpkg manifest as libvpx — re-run the vcpkg install / CMake "
        "configure, or build WebP-only with -Pwebm=false / -DWEBM_ENABLED=OFF.")
endif()

target_include_directories(webm_vpx INTERFACE "${YUV_INCLUDE_DIR}")
target_link_libraries(webm_vpx INTERFACE "${YUV_LIBRARY}")

# libvpx's static archive pulls in libm / pthreads / libdl on POSIX.
if (UNIX)
    find_package(Threads REQUIRED)
    target_link_libraries(webm_vpx INTERFACE Threads::Threads m ${CMAKE_DL_LIBS})
endif()

# ── libwebm (source, pinned tag) ──────────────────────────────────
# No vcpkg port exists; it's a handful of .cc files. SOURCE_SUBDIR points
# at a non-existent dir so FetchContent only *populates* the tree and does
# NOT add_subdirectory() libwebm's own CMakeLists (which would build CLI
# tools we don't need). We compile just the mux+parse sources we use.
FetchContent_Declare(
    libwebm
    GIT_REPOSITORY https://github.com/webmproject/libwebm
    GIT_TAG        libwebm-1.0.0.32
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  do-not-add-subdirectory
)
FetchContent_MakeAvailable(libwebm)

set(LIBWEBM_SOURCES
    "${libwebm_SOURCE_DIR}/common/file_util.cc"
    "${libwebm_SOURCE_DIR}/common/hdr_util.cc"
    "${libwebm_SOURCE_DIR}/mkvmuxer/mkvmuxer.cc"
    "${libwebm_SOURCE_DIR}/mkvmuxer/mkvmuxerutil.cc"
    "${libwebm_SOURCE_DIR}/mkvmuxer/mkvwriter.cc"
    "${libwebm_SOURCE_DIR}/mkvparser/mkvparser.cc"
    "${libwebm_SOURCE_DIR}/mkvparser/mkvreader.cc"
)

add_library(webm_static STATIC ${LIBWEBM_SOURCES})
target_include_directories(webm_static PUBLIC "${libwebm_SOURCE_DIR}")
target_compile_features(webm_static PRIVATE cxx_std_11)
# PIC so the static archive links into the shared JNI .so/.dylib.
set_target_properties(webm_static PROPERTIES POSITION_INDEPENDENT_CODE ON)
if (MSVC)
    # CRT linkage (/MT) is inherited from the parent's CMAKE_MSVC_RUNTIME_LIBRARY.
    target_compile_definitions(webm_static PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
endif()
