#!/bin/bash
# Copy GN Framegen Layer files to GameNative assets directory

set -e

echo "=== Copy GN Framegen Layer to GameNative Assets ==="
echo ""

# Find GameNative project directory (parent of gn-native-layer)
GAMENATIVE_DIR=$(cd "$(dirname "$0")" && cd .. && pwd)
ASSETS_DIR="$GAMENATIVE_DIR/app/src/main/assets/gn_framegen/android_arm64_v8a"

echo "GameNative Directory: $GAMENATIVE_DIR"
echo "Assets Directory: $ASSETS_DIR"
echo ""

# Check if we're in the right place
if [ ! -d "$GAMENATIVE_DIR/app/src/main/assets" ]; then
    echo "ERROR: Cannot find GameNative assets directory"
    echo "Make sure this script is run from gn-native-layer directory"
    exit 1
fi

# Create assets directory if needed
mkdir -p "$ASSETS_DIR"

echo "Created: $ASSETS_DIR"
echo ""

# Check for built library
NATIVE_LAYER_DIR="$(dirname "$0")/native_layer"
BUILT_LIB="$NATIVE_LAYER_DIR/build-android-arm64-v8a/libgn-framegen.so"
MANIFEST_SOURCE="$NATIVE_LAYER_DIR/VkLayer_GN_gamescope_framegen.json"

if [ -f "$BUILT_LIB" ]; then
    echo "Found built library: $BUILT_LIB"
    echo "Copying to assets..."
    cp "$BUILT_LIB" "$ASSETS_DIR/"
    echo "  ✓ $(basename $BUILT_LIB)"
else
    echo "WARNING: Built library not found: $BUILT_LIB"
    echo "  Run: cd native_layer && ./build-android.sh"
    echo "  Or the library will be copied when built"
fi

if [ -f "$MANIFEST_SOURCE" ]; then
    echo ""
    echo "Copying manifest..."
    cp "$MANIFEST_SOURCE" "$ASSETS_DIR/"
    echo "  ✓ VkLayer_GN_gamescope_framegen.json"
else
    echo "ERROR: Layer manifest not found: $MANIFEST_SOURCE"
    exit 1
fi

echo ""
echo "=== Copy Complete ==="
echo ""
echo "Files in $ASSETS_DIR:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "Next steps:"
echo "  1. Build the Android app: ./gradlew assembleDebug"
echo "  2. Install to device: adb install app/build/outputs/apk/debug/app-debug.apk"
echo "  3. Or use install-to-apk.sh to inject into existing APK"
echo ""
echo "The layer will be bundled in the APK and installed at runtime"
echo "by GNFramegenManager.ensureRuntimeInstalled()"
