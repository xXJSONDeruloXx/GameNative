#!/bin/bash
# Install the Vulkan layer to a GameNative APK

set -e

# Configuration
APK_PATH=${1:-""}
ABI=${2:-arm64-v8a}

if [ -z "$APK_PATH" ]; then
    echo "Usage: $0 <path-to-apk> [abi]"
    echo "Example: $0 /path/to/GameNative.apk arm64-v8a"
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found: $APK_PATH"
    exit 1
fi

BUILD_DIR="build-android-$ABI"
if [ ! -d "$BUILD_DIR" ]; then
    echo "Error: Build directory not found: $BUILD_DIR"
    echo "Run build-android.sh first"
    exit 1
fi

LAYER_SO="$BUILD_DIR/libgn-framegen.so"
if [ ! -f "$LAYER_SO" ]; then
    echo "Error: Layer library not found: $LAYER_SO"
    exit 1
fi

LAYER_JSON="VkLayer_GN_gamescope_framegen.json"
if [ ! -f "$LAYER_JSON" ]; then
    echo "Error: Layer manifest not found: $LAYER_JSON"
    exit 1
fi

echo "Installing to APK: $APK_PATH"
echo "  ABI: $ABI"
echo "  Layer: $LAYER_SO"
echo "  Manifest: $LAYER_JSON"

# Create temp directory
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Extract APK
echo "Extracting APK..."
cd "$TEMP_DIR"
unzip -q "$APK_PATH"

# Create lib directory if it doesn't exist
mkdir -p "lib/$ABI"

# Copy layer library
echo "Installing layer library..."
cp "$OLDPWD/$LAYER_SO" "lib/$ABI/"

# Copy layer manifest
echo "Installing layer manifest..."
cp "$OLDPWD/$LAYER_JSON" "lib/$ABI/"

# Repack APK
echo "Repacking APK..."
OUTPUT_APK="${APK_PATH%.apk}-with-framegen.apk"
zip -rq "$OUTPUT_APK" .

# Sign APK (optional - if apksigner is available)
if command -v apksigner &> /dev/null; then
    echo "Signing APK with apksigner..."
    SIGNED_APK="${APK_PATH%.apk}-with-framegen-signed.apk"
    apksigner sign --in "$OUTPUT_APK" --out "$SIGNED_APK"
    OUTPUT_APK="$SIGNED_APK"
else
    echo "Warning: apksigner not found, APK is unsigned"
fi

echo ""
echo "Layer installed successfully!"
echo "Output: $OUTPUT_APK"
echo ""
echo "To use the layer:"
echo "  1. Set environment variables in GameNative:"
echo "     - GN_FG_ENABLE=1"
echo "     - GN_FG_MULTIPLIER=2  # or 3, 4"
echo "     - GN_FG_FLOW_SCALE=0.5"
echo "  2. Install the APK to device"
echo "  3. Enable Vulkan layer in GameNative settings"
