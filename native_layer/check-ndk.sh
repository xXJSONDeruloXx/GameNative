#!/bin/bash
# Check Android NDK setup for building the Vulkan layer

set -e

echo "=== GN Framegen Layer - Android NDK Check ==="
echo ""

# Check ANDROID_NDK environment variable
if [ -z "$ANDROID_NDK" ] && [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: ANDROID_NDK or ANDROID_NDK_HOME environment variable not set"
    echo ""
    echo "Please set one of these before building:"
    echo "  export ANDROID_NDK=/path/to/android-ndk"
    echo "  export ANDROID_NDK_HOME=/path/to/android-ndk"
    exit 1
fi

# Use whichever is set
NDK=${ANDROID_NDK:-$ANDROID_NDK_HOME}
echo "NDK Path: $NDK"

# Verify NDK exists
if [ ! -d "$NDK" ]; then
    echo "ERROR: NDK directory not found: $NDK"
    exit 1
fi

# Check for key NDK components
echo ""
echo "Checking NDK components..."

# CMake toolchain
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
if [ -f "$TOOLCHAIN" ]; then
    echo "  ✓ CMake toolchain: $TOOLCHAIN"
else
    echo "  ✗ CMake toolchain not found: $TOOLCHAIN"
    exit 1
fi

# Check for cmake
if command -v cmake &> /dev/null; then
    CMAKE_VERSION=$(cmake --version | head -1)
    echo "  ✓ CMake: $CMAKE_VERSION"
else
    echo "  ✗ CMake not found in PATH"
    exit 1
fi

# Check for NDK cmake (optional)
NDK_CMAKE="$NDK/cmake/bin/cmake"
if [ -f "$NDK_CMAKE" ]; then
    NDK_CMAKE_VERSION=$($NDK_CMAKE --version | head -1)
    echo "  ✓ NDK CMake: $NDK_CMAKE_VERSION"
fi

# Check for make
if command -v make &> /dev/null; then
    echo "  ✓ Make: available"
else
    echo "  ✗ Make not found in PATH"
    exit 1
fi

# Check for Vulkan headers
VULKAN_HEADERS=(
    "$NDK/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan/vulkan.h"
    "$NDK/sources/third_party/vulkan/src/include/vulkan/vulkan.h"
)

VULKAN_FOUND=false
for header in "${VULKAN_HEADERS[@]}"; do
    if ls $header 1> /dev/null 2>&1; then
        echo "  ✓ Vulkan headers found"
        VULKAN_FOUND=true
        break
    fi
done

if [ "$VULKAN_FOUND" = false ]; then
    echo "  ⚠ Vulkan headers not found in standard locations"
    echo "    (This is OK - NDK should provide them via sysroot)"
fi

# Check NDK version
if [ -f "$NDK/source.properties" ]; then
    NDK_VERSION=$(grep "Pkg.Revision" "$NDK/source.properties" | cut -d= -f2 | tr -d ' ')
    echo "  ✓ NDK Version: $NDK_VERSION"
elif [ -f "$NDK/CHANGELOG.md" ]; then
    NDK_VERSION=$(head -20 "$NDK/CHANGELOG.md" | grep -o "r[0-9][0-9]*[a-z]?" | head -1)
    echo "  ✓ NDK Version: $NDK_VERSION (from CHANGELOG)"
else
    echo "  ⚠ Could not determine NDK version"
fi

# Check Android API levels
API_LEVELS=$(ls -d "$NDK/platforms/android-*" 2>/dev/null | grep -o "android-[0-9]*" | sort -t- -k2 -n | tr '\n' ' ')
if [ -n "$API_LEVELS" ]; then
    echo "  ✓ Available API levels: $API_LEVELS"
else
    echo "  ⚠ No API levels found (using default android-30)"
fi

# Summary
echo ""
echo "=== NDK Check Complete ==="
echo ""
echo "Environment variables for build:"
echo "  ANDROID_NDK=$NDK"
echo "  ANDROID_ABI=arm64-v8a (or armeabi-v7a, x86_64, x86)"
echo "  ANDROID_PLATFORM=android-30 (or higher)"
echo ""
echo "To build, run:"
echo "  cd native_layer"
echo "  ./build-android.sh"
echo ""
echo "All checks passed! ✓"
