#!/bin/bash
# Copy GN Framegen Layer files to GameNative assets directory

set -e

echo "=== Copy GN Framegen Layer to GameNative Assets ==="
echo ""

# Determine script location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find GameNative project directory
# gn-native-layer is a worktree, GameNative main checkout is at sibling level
if [ -d "$SCRIPT_DIR/../GameNative/app/src/main/assets" ]; then
    # Called from gn-native-layer, GameNative is sibling
    GAMENATIVE_DIR=$(cd "$SCRIPT_DIR/../GameNative" && pwd)
elif [ -d "$SCRIPT_DIR/../app/src/main/assets" ]; then
    # Called from gn-native-layer, but parent has the structure (weird case)
    GAMENATIVE_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
elif [ -d "$(pwd)/app/src/main/assets" ]; then
    # Called from GameNative directory
    GAMENATIVE_DIR="$(pwd)"
else
    # Try to find GameNative by looking for sibling directory
    GAMENATIVE_DIR="$(cd "$SCRIPT_DIR/../GameNative" 2>/dev/null && pwd)"
    if [ -z "$GAMENATIVE_DIR" ] || [ ! -d "$GAMENATIVE_DIR/app/src/main/assets" ]; then
        echo "ERROR: Cannot find GameNative project directory"
        echo "Expected: $SCRIPT_DIR/../GameNative/app/src/main/assets"
        echo "Make sure GameNative and gn-native-layer are siblings"
        exit 1
    fi
fi

ASSETS_DIR="$GAMENATIVE_DIR/app/src/main/assets/gn_framegen/android_arm64_v8a"

echo "GameNative Directory: $GAMENATIVE_DIR"
echo "Assets Directory: $ASSETS_DIR"
echo ""

# Check if we're in the right place
if [ ! -d "$GAMENATIVE_DIR/app/src/main/assets" ]; then
    echo "ERROR: Cannot find GameNative assets directory"
    echo "Make sure GameNative is the current directory or parent of gn-native-layer"
    exit 1
fi

# Create assets directory if needed
mkdir -p "$ASSETS_DIR"

echo "Created: $ASSETS_DIR"
echo ""

# Check for built library
BUILT_LIB="$SCRIPT_DIR/native_layer/build-android-arm64-v8a/libgn-framegen.so"
MANIFEST_SOURCE="$SCRIPT_DIR/native_layer/VkLayer_GN_gamescope_framegen.json"

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
