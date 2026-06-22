# Overlay triplet: arm64-linux with -fPIC (see x64-linux.cmake for why).
# Built natively on an aarch64 host (CI uses an ubuntu-*-arm runner), so no
# cross-toolchain is configured here.
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Linux)

set(VCPKG_C_FLAGS "-fPIC")
set(VCPKG_CXX_FLAGS "-fPIC")
