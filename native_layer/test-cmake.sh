#!/bin/bash
# Test CMake configuration for GN Framegen Layer

set -e

echo "=== GN Framegen Layer - CMake Configuration Test ==="
echo ""

# Configuration
ANDROID_NDK=${ANDROID_NDK:-$ANDROID_NDK_HOME}
ANDROID_ABI=${ANDROID_ABI:-arm64-v8a}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-30}

if [ -z "$ANDROID_NDK" ]; then
    echo "ERROR: ANDROID_NDK or ANDROID_NDK_HOME not set"
    exit 1
fi

# Create test build directory
BUILD_DIR="build-test-$(date +%s)"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "Testing CMake configuration..."
echo "  NDK: $ANDROID_NDK"
echo "  ABI: $ANDROID_ABI"
echo "  Platform: $ANDROID_PLATFORM"
echo ""

# Run CMake configuration only (don't build)
echo "Running cmake..."
if cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DBUILD_FOR_ANDROID=ON \
    2>&1 | tee cmake_output.txt; then
    echo ""
    echo "✓ CMake configuration successful"
else
    echo ""
    echo "✗ CMake configuration failed"
    echo ""
    echo "Error output:"
    tail -50 cmake_output.txt
    exit 1
fi

# Check for key generated files
echo ""
echo "Checking generated files..."
if [ -f "CMakeCache.txt" ]; then
    echo "  ✓ CMakeCache.txt generated"
else
    echo "  ✗ CMakeCache.txt not found"
fi

if [ -f "Makefile" ] || [ -f "build.ninja" ]; then
    echo "  ✓ Build system files generated"
else
    echo "  ✗ Build system files not found"
fi

# Extract useful info from CMakeCache.txt
if [ -f "CMakeCache.txt" ]; then
    echo ""
    echo "Configuration summary:"
    grep "CMAKE_C_COMPILER" CMakeCache.txt | head -1 || true
    grep "CMAKE_CXX_COMPILER" CMakeCache.txt | head -1 || true
    grep "ANDROID_ABI" CMakeCache.txt | head -1 || true
    grep "ANDROID_PLATFORM" CMakeCache.txt | head -1 || true
    grep "CMAKE_CXX_FLAGS" CMakeCache.txt | head -1 || true
fi

# Cleanup
cd ..
rm -rf "$BUILD_DIR"

echo ""
echo "=== CMake Test Complete ==="
echo "Configuration validated successfully!"
echo ""
echo "Ready for full build:"
echo "  ./build-android.sh"
