#!/bin/bash
# All-in-one script: Build GN Framegen Layer, copy to GameNative, build APK, and deploy to device

set -e

# Configuration
ANDROID_NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
GAMENATIVE_DIR=$(cd "$(dirname "$0")" && cd .. && pwd)
BUILD_TYPE=${1:-Release}
ABI=${2:-arm64-v8a}
INSTALL=${3:-false}

echo "========================================="
echo "  GN Framegen Layer - Build & Deploy"
echo "========================================="
echo ""
echo "Configuration:"
echo "  Build Type: $BUILD_TYPE"
echo "  ABI: $ABI"
echo "  GameNative Directory: $GAMENATIVE_DIR"
echo "  Auto-install: $INSTALL"
echo ""

# Step 1: Verify NDK
if [ -z "$ANDROID_NDK" ]; then
    echo "ERROR: ANDROID_NDK not set"
    echo "Set ANDROID_NDK or ANDROID_NDK_HOME environment variable"
    exit 1
fi

if [ ! -d "$ANDROID_NDK" ]; then
    echo "ERROR: NDK directory not found: $ANDROID_NDK"
    exit 1
fi

echo "✓ NDK found: $ANDROID_NDK"
echo ""

# Step 2: Build the layer
echo "=== Step 1: Building Layer ==="
cd "$(dirname "$0")/native_layer"
echo "Running: make build ABI=$ABI BUILD_TYPE=$BUILD_TYPE"
make build ABI="$ABI" BUILD_TYPE="$BUILD_TYPE"

BUILT_LIB="build-android-$ABI/libgn-framegen.so"
if [ ! -f "$BUILT_LIB" ]; then
    echo "ERROR: Build failed - library not found: $BUILT_LIB"
    exit 1
fi

echo "✓ Layer built successfully"
echo "  Library: $BUILT_LIB ($(stat -f%z "$BUILT_LIB" 2>/dev/null || stat -c%s "$BUILT_LIB" 2>/dev/null) bytes)"
echo ""

# Step 3: Copy to assets
echo "=== Step 2: Copying to GameNative Assets ==="
cd "$(dirname "$0")"
./copy-to-assets.sh

# Check if assets were copied
ASSETS_DIR="$GAMENATIVE_DIR/app/src/main/assets/gn_framegen/android_arm64_v8a"
if [ ! -f "$ASSETS_DIR/libgn-framegen.so" ]; then
    echo "ERROR: Failed to copy layer to assets"
    exit 1
fi

echo "✓ Assets ready"
echo ""

# Step 4: Build GameNative APK
echo "=== Step 3: Building GameNative APK ==="
cd "$GAMENATIVE_DIR"

# Check for Gradle
if [ ! -f "./gradlew" ]; then
    echo "ERROR: Gradle wrapper not found. Make sure you're in the GameNative directory."
    exit 1
fi

echo "Running: ./gradlew assembleDebug"
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK build failed - file not found: $APK_PATH"
    exit 1
fi

echo "✓ GameNative APK built"
echo "  APK: $APK_PATH"
echo ""

# Step 5: Install to device (if requested)
if [ "$INSTALL" = "install" ] || [ "$INSTALL" = "true" ] || [ "$INSTALL" = "1" ]; then
    echo "=== Step 4: Installing to Device ==="
    
    # Check for device
    if ! adb devices | grep -E "device$" > /dev/null; then
        echo "ERROR: No Android device connected"
        echo "Connect a device or skip install step"
        exit 1
    fi
    
    echo "Uninstalling existing GameNative (if present)..."
    adb uninstall app.gamenative 2>/dev/null || true
    
    echo "Installing new APK..."
    adb install "$APK_PATH"
    
    echo ""
    echo "✓ Installation complete"
    echo ""
    echo "Next steps:"
    echo "  1. Open GameNative on your device"
    echo "  2. Create or edit a container"
    echo "  3. Go to Graphics settings"
    echo "  4. Enable 'GN Framegen Layer'"
    echo "  5. Launch a game"
    echo ""
    echo "Monitor with: adb logcat -s 'GN-Framegen'"
fi

echo "========================================="
echo "  Build & Deploy Complete!"
echo "========================================="
echo ""
if [ "$INSTALL" != "install" ] && [ "$INSTALL" != "true" ] && [ "$INSTALL" != "1" ]; then
    echo "APK ready at: $APK_PATH"
    echo ""
    echo "To install:"
    echo "  adb install $APK_PATH"
    echo ""
    echo "Or run this script with 'install' argument:"
    echo "  ./build-and-deploy.sh Release arm64-v8a install"
fi
