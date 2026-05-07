#!/bin/bash
# Build script for Android NDK

set -e

# Configuration
ANDROID_NDK=${ANDROID_NDK:-$ANDROID_NDK_HOME}
ANDROID_ABI=${ANDROID_ABI:-arm64-v8a}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-30}
BUILD_TYPE=${BUILD_TYPE:-Release}

# Check for NDK
if [ -z "$ANDROID_NDK" ]; then
    echo "Error: ANDROID_NDK not set. Set ANDROID_NDK environment variable or ANDROID_NDK_HOME"
    exit 1
fi

echo "Building with:"
echo "  NDK: $ANDROID_NDK"
echo "  ABI: $ANDROID_ABI"
echo "  Platform: $ANDROID_PLATFORM"
echo "  Build Type: $BUILD_TYPE"

# Create build directory
BUILD_DIR="build-android-$ANDROID_ABI"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Configure with CMake
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DBUILD_FOR_ANDROID=ON

# Build
make -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

echo ""
echo "Build complete. Output:"
ls -la libgn-framegen.so 2>/dev/null || ls -la *.so 2>/dev/null || echo "No .so files found"
