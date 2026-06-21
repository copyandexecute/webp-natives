#!/usr/bin/env bash
set -euo pipefail

export PATH="/c/Users/jakub/IdeaProjects/nrc/webp-natives/tools/nasm/nasm-3.01:/c/Program Files (x86)/Microsoft Visual Studio/18/BuildTools/MSBuild/Current/Bin:/c/msys64/usr/bin:/c/msys64/bin:$PATH"

BUILD="/c/Users/jakub/IdeaProjects/nrc/webp-natives/webp-natives-windows/build/native/windows-x64/_deps/libvpx-build"
SRC="/c/Users/jakub/IdeaProjects/nrc/webp-natives/webp-natives-windows/build/native/windows-x64/_deps/libvpx-src"

rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$BUILD"
"$SRC/configure" --target=x86_64-win64-vs18 --disable-shared --enable-static --enable-static-msvcrt --enable-vp8 --enable-vp9 --disable-examples --disable-tools --disable-unit-tests --disable-docs --as=nasm
make -j4
