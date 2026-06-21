
include(FetchContent)

find_program(WEBM_NASM nasm.exe
    HINTS
        "${CMAKE_CURRENT_LIST_DIR}/../tools/nasm/nasm-3.01"
        "C:/Program Files/NASM"
)
if (NOT WEBM_NASM)
    message(FATAL_ERROR
        "NASM not found. Install from https://www.nasm.us/ (add to PATH), "
        "or extract nasm-3.01-win64.zip to tools/nasm/nasm-3.01/ (gitignored).")
endif()
get_filename_component(WEBM_NASM_DIR "${WEBM_NASM}" DIRECTORY)

if (NOT EXISTS "C:/msys64/usr/bin/make.exe")
    message(FATAL_ERROR "MSYS2 with 'make' and 'diffutils' is required. Install: winget install MSYS2.MSYS2, then run: pacman -Sy make diffutils")
endif()

find_program(WEBM_GIT git REQUIRED)

if (EXISTS "C:/msys64/usr/bin/bash.exe")
    set(WEBM_BASH "C:/msys64/usr/bin/bash.exe")
elseif (EXISTS "C:/Program Files/Git/bin/bash.exe")
    set(WEBM_BASH "C:/Program Files/Git/bin/bash.exe")
elseif (EXISTS "C:/Program Files (x86)/Git/bin/bash.exe")
    set(WEBM_BASH "C:/Program Files (x86)/Git/bin/bash.exe")
else()
    message(FATAL_ERROR "MSYS2 or Git Bash is required to build libvpx")
endif()

get_filename_component(WEBM_BASH_BIN "${WEBM_BASH}" DIRECTORY)
get_filename_component(WEBM_BASH_ROOT "${WEBM_BASH_BIN}" DIRECTORY)
set(WEBM_BASH_USR_BIN "${WEBM_BASH_ROOT}/usr/bin")

if (EXISTS "${WEBM_BASH_USR_BIN}/make.exe")
    set(WEBM_MAKE "${WEBM_BASH_USR_BIN}/make.exe")
else()
    find_program(WEBM_MAKE make.exe
        PATHS "${WEBM_BASH_USR_BIN}"
              "$ENV{LOCALAPPDATA}/Microsoft/WinGet/Packages/ezwinports.make_Microsoft.Winget.Source_8wekyb3d8bbwe/bin"
        NO_DEFAULT_PATH)
    if (NOT WEBM_MAKE)
        find_program(WEBM_MAKE make.exe REQUIRED)
    endif()
endif()
get_filename_component(WEBM_MAKE_DIR "${WEBM_MAKE}" DIRECTORY)

find_program(WEBM_MSBUILD MSBuild.exe
    PATHS
        "C:/Program Files (x86)/Microsoft Visual Studio/18/BuildTools/MSBuild/Current/Bin"
        "C:/Program Files/Microsoft Visual Studio/2022/Community/MSBuild/Current/Bin"
        "C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/MSBuild/Current/Bin"
    REQUIRED)

set(WEBM_VPX_SRC "${CMAKE_BINARY_DIR}/_deps/libvpx-src")
set(WEBM_VPX_BUILD "${CMAKE_BINARY_DIR}/_deps/libvpx-build")

function(webm_cmake_to_msys in_path out_var)
    string(REPLACE "\\" "/" _p "${in_path}")
    if (_p MATCHES "^([A-Za-z]):/(.*)$")
        string(TOLOWER "${CMAKE_MATCH_1}" _drive)
        set(_msys "/${_drive}/${CMAKE_MATCH_2}")
    else()
        set(_msys "${_p}")
    endif()
    set(${out_var} "${_msys}" PARENT_SCOPE)
endfunction()

file(TO_CMAKE_PATH "${WEBM_NASM_DIR}" WEBM_NASM_DIR_CMAKE)
file(TO_CMAKE_PATH "${WEBM_MSBUILD}" WEBM_MSBUILD_CMAKE)
file(TO_CMAKE_PATH "${WEBM_BASH_USR_BIN}" WEBM_BASH_USR_BIN_CMAKE)
file(TO_CMAKE_PATH "${WEBM_MAKE_DIR}" WEBM_MAKE_DIR_CMAKE)
file(TO_CMAKE_PATH "${WEBM_BASH_BIN}" WEBM_BASH_BIN_CMAKE)
file(TO_CMAKE_PATH "${WEBM_VPX_SRC}" WEBM_VPX_SRC_CMAKE)
file(TO_CMAKE_PATH "${WEBM_VPX_BUILD}" WEBM_VPX_BUILD_CMAKE)

webm_cmake_to_msys("${WEBM_NASM_DIR_CMAKE}" WEBM_NASM_MSYS)
webm_cmake_to_msys("${WEBM_MSBUILD_CMAKE}" WEBM_MSBUILD_MSYS)
webm_cmake_to_msys("${WEBM_BASH_USR_BIN_CMAKE}" WEBM_BASH_USR_MSYS)
webm_cmake_to_msys("${WEBM_MAKE_DIR_CMAKE}" WEBM_MAKE_MSYS)
webm_cmake_to_msys("${WEBM_BASH_BIN_CMAKE}" WEBM_BASH_BIN_MSYS)
webm_cmake_to_msys("${WEBM_VPX_SRC_CMAKE}" WEBM_VPX_SRC_MSYS)
webm_cmake_to_msys("${WEBM_VPX_BUILD_CMAKE}" WEBM_VPX_BUILD_MSYS)

set(WEBM_TOOL_PATH "${WEBM_NASM_MSYS}:${WEBM_MSBUILD_MSYS}:${WEBM_BASH_USR_MSYS}:${WEBM_MAKE_MSYS}:${WEBM_BASH_BIN_MSYS}")

if (NOT EXISTS "${WEBM_VPX_BUILD}/x64/Release/vpxmt.lib" AND NOT EXISTS "${WEBM_VPX_BUILD}/x64/Release/vpxmd.lib")
    message(STATUS "[webm] Fetching libvpx...")
    if (NOT EXISTS "${WEBM_VPX_SRC}/configure")
        file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/_deps")
        execute_process(
            COMMAND ${WEBM_GIT} clone --depth 1 https://chromium.googlesource.com/webm/libvpx "${WEBM_VPX_SRC}"
            RESULT_VARIABLE _VPX_CLONE_RC)
        if (NOT _VPX_CLONE_RC EQUAL 0)
            message(FATAL_ERROR "libvpx clone failed (${_VPX_CLONE_RC})")
        endif()
    endif()

    file(MAKE_DIRECTORY "${WEBM_VPX_BUILD}")
    message(STATUS "[webm] Configuring libvpx (x86_64-win64-vs18)...")
    execute_process(
        COMMAND ${WEBM_BASH} -lc
            "export PATH=\"${WEBM_TOOL_PATH}:$PATH\" && cd \"${WEBM_VPX_BUILD_MSYS}\" && \"${WEBM_VPX_SRC_MSYS}/configure\" --target=x86_64-win64-vs18 --disable-shared --enable-static --enable-static-msvcrt --enable-vp8 --enable-vp9 --disable-examples --disable-tools --disable-unit-tests --disable-docs --as=nasm"
        RESULT_VARIABLE _VPX_CFG_RC
        OUTPUT_VARIABLE _VPX_CFG_OUT
        ERROR_VARIABLE _VPX_CFG_ERR)
    if (NOT _VPX_CFG_RC EQUAL 0)
        message(FATAL_ERROR "libvpx configure failed (${_VPX_CFG_RC}):\n${_VPX_CFG_OUT}\n${_VPX_CFG_ERR}")
    endif()

    message(STATUS "[webm] Building libvpx Release (this takes ~1 min)...")
    execute_process(
        COMMAND ${WEBM_BASH} -lc
            "export PATH=\"${WEBM_TOOL_PATH}:$PATH\" && cd \"${WEBM_VPX_BUILD_MSYS}\" && make -j4 && make Release_only -j4 2>&1"
        RESULT_VARIABLE _VPX_BUILD_RC
        OUTPUT_VARIABLE _VPX_BUILD_OUT
        ERROR_VARIABLE _VPX_BUILD_ERR)
    if (NOT _VPX_BUILD_RC EQUAL 0)
        message(FATAL_ERROR "libvpx build failed (${_VPX_BUILD_RC}):\n${_VPX_BUILD_OUT}\n${_VPX_BUILD_ERR}")
    endif()
endif()

set(WEBM_VPX_LIB "${WEBM_VPX_BUILD}/x64/Release/vpxmt.lib")
set(WEBM_VPX_RC_LIB "${WEBM_VPX_BUILD}/x64/Release/vpxrcmt.lib")
if (NOT EXISTS "${WEBM_VPX_LIB}")
    # Fallback for builds without --enable-static-msvcrt
    set(WEBM_VPX_LIB "${WEBM_VPX_BUILD}/x64/Release/vpxmd.lib")
    set(WEBM_VPX_RC_LIB "${WEBM_VPX_BUILD}/x64/Release/vpxrcmd.lib")
endif()
if (NOT EXISTS "${WEBM_VPX_LIB}")
    message(FATAL_ERROR "libvpx library not found at ${WEBM_VPX_LIB}")
endif()

set(WEBM_LIBWEBM_SRC "${CMAKE_BINARY_DIR}/_deps/libwebm-src")
if (NOT EXISTS "${WEBM_LIBWEBM_SRC}/mkvmuxer/mkvmuxer.h")
    if (EXISTS "${WEBM_LIBWEBM_SRC}")
        file(REMOVE_RECURSE "${WEBM_LIBWEBM_SRC}")
    endif()
    message(STATUS "[webm] Fetching libwebm...")
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/_deps")
    execute_process(
        COMMAND ${WEBM_GIT} clone --depth 1 https://chromium.googlesource.com/webm/libwebm "${WEBM_LIBWEBM_SRC}"
        RESULT_VARIABLE _WEBM_CLONE_RC)
    if (NOT _WEBM_CLONE_RC EQUAL 0)
        message(FATAL_ERROR "libwebm clone failed (${_WEBM_CLONE_RC})")
    endif()
endif()

set(WEBM_LIBWEBM_SOURCES
    ${WEBM_LIBWEBM_SRC}/common/file_util.cc
    ${WEBM_LIBWEBM_SRC}/common/hdr_util.cc
    ${WEBM_LIBWEBM_SRC}/mkvmuxer/mkvmuxer.cc
    ${WEBM_LIBWEBM_SRC}/mkvmuxer/mkvmuxerutil.cc
    ${WEBM_LIBWEBM_SRC}/mkvmuxer/mkvwriter.cc
    ${WEBM_LIBWEBM_SRC}/mkvparser/mkvparser.cc
    ${WEBM_LIBWEBM_SRC}/mkvparser/mkvreader.cc
)

add_library(webm_static STATIC ${WEBM_LIBWEBM_SOURCES})
target_include_directories(webm_static PUBLIC "${WEBM_LIBWEBM_SRC}")
target_compile_features(webm_static PRIVATE cxx_std_11)
target_compile_definitions(webm_static PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
if (MSVC)
    target_compile_options(webm_static PRIVATE /MT)
endif()

add_library(webm_vpx INTERFACE)
target_include_directories(webm_vpx INTERFACE "${WEBM_VPX_SRC}")
target_link_libraries(webm_vpx INTERFACE
    "${WEBM_VPX_LIB}"
    "${WEBM_VPX_RC_LIB}"
)

set(WEBM_DEPS_INCLUDE_DIRS "${WEBM_LIBWEBM_SRC}" "${WEBM_VPX_SRC}")
