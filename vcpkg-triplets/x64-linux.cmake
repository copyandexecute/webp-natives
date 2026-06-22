# Overlay triplet: same as the stock x64-linux (static lib, dynamic CRT) but
# forces -fPIC so libvpx's static archive can be linked into our shared
# libwebp_natives.so. Without PIC the link fails with "recompile with -fPIC".
set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Linux)

set(VCPKG_C_FLAGS "-fPIC")
set(VCPKG_CXX_FLAGS "-fPIC")
