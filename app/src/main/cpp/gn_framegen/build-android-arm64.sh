#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
NDK_ROOT="${ANDROID_NDK_ROOT:-/opt/homebrew/share/android-commandlinetools/ndk/27.3.13750724}"
BUILD_DIR="$ROOT_DIR/build-android-arm64"
OUT_LIB="$ROOT_DIR/../../jniLibs/arm64-v8a/libgn_framegen.so"

rm -rf "$BUILD_DIR"
cmake -S "$ROOT_DIR" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29
cmake --build "$BUILD_DIR" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
mkdir -p "$(dirname "$OUT_LIB")"
cp "$BUILD_DIR/libgn_framegen.so" "$OUT_LIB"
echo "Wrote $OUT_LIB"
