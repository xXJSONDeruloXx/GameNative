#!/bin/bash
# Package GN Framegen Layer for release distribution

set -e

# Configuration
VERSION="1.0.0"
ABI=${1:-arm64-v8a}
PLATFORM=${2:-android-30}
BUILD_TYPE=${3:-Release}

echo "=== GN Framegen Layer - Release Packaging ==="
echo ""
echo "Version: $VERSION"
echo "ABI: $ABI"
echo "Platform: $PLATFORM"
echo "Build Type: $BUILD_TYPE"
echo ""

# Check if build exists
BUILD_DIR="build-android-$ABI"
LAYER_SO="$BUILD_DIR/libgn-framegen.so"
MANIFEST="VkLayer_GN_gamescope_framegen.json"

if [ ! -f "$LAYER_SO" ]; then
    echo "ERROR: Layer library not found: $LAYER_SO"
    echo "Please build first: ./build-android.sh"
    exit 1
fi

if [ ! -f "$MANIFEST" ]; then
    echo "ERROR: Layer manifest not found: $MANIFEST"
    exit 1
fi

# Create release directory
RELEASE_NAME="gn-framegen-v${VERSION}-${ABI}-${PLATFORM}"
RELEASE_DIR="releases/$RELEASE_NAME"
mkdir -p "$RELEASE_DIR"

echo "Creating release package: $RELEASE_NAME"
echo ""

# Copy binaries
echo "Copying binaries..."
cp "$LAYER_SO" "$RELEASE_DIR/"
cp "$MANIFEST" "$RELEASE_DIR/"

# Copy documentation
echo "Copying documentation..."
for doc in ../README.md ../QUICKSTART.md ../CHANGELOG.md ../IMPLEMENTATION_NOTES.md; do
    if [ -f "$doc" ]; then
        cp "$doc" "$RELEASE_DIR/"
        echo "  ✓ $(basename $doc)"
    fi
done

# Create install script
echo "Creating install script..."
cat > "$RELEASE_DIR/install.sh" << 'EOF'
#!/bin/bash
# Install GN Framegen Layer

set -e

LAYER_NAME="libgn-framegen.so"
MANIFEST_NAME="VkLayer_GN_gamescope_framegen.json"

# Check for root or app-specific install
echo "=== GN Framegen Layer Installer ==="
echo ""
echo "Choose installation method:"
echo "  1) System-wide (requires root)"
echo "  2) App-specific (for GameNative)"
echo "  3) Cancel"
echo ""
read -p "Enter choice [1-3]: " choice

case $choice in
    1)
        echo "Installing system-wide..."
        if ! adb shell "su -c 'id'" 2>/dev/null | grep -q uid; then
            echo "ERROR: Root access required for system-wide install"
            exit 1
        fi
        
        # Mount system as read-write
        adb shell "su -c 'mount -o remount,rw /system'"
        
        # Push files
        adb push "$LAYER_NAME" "/system/lib64/"
        adb shell "mkdir -p /system/share/vulkan/explicit_layer.d/"
        adb push "$MANIFEST_NAME" "/system/share/vulkan/explicit_layer.d/"
        
        # Set permissions
        adb shell "chmod 644 /system/lib64/$LAYER_NAME"
        adb shell "chmod 644 /system/share/vulkan/explicit_layer.d/$MANIFEST_NAME"
        
        echo "✓ System-wide installation complete"
        echo "Reboot may be required"
        ;;
        
    2)
        echo "Installing for GameNative..."
        
        # Check if GameNative is installed
        if ! adb shell pm list packages | grep -q "app.gamenative"; then
            echo "WARNING: GameNative not found on device"
            read -p "Continue anyway? [y/N]: " cont
            if [[ ! $cont =~ ^[Yy]$ ]]; then
                exit 0
            fi
        fi
        
        # Get app path
        APP_PATH=$(adb shell pm path app.gamenative 2>/dev/null | head -1 | cut -d: -f2)
        if [ -z "$APP_PATH" ]; then
            echo "ERROR: Cannot find GameNative installation path"
            exit 1
        fi
        
        echo "Found GameNative at: $APP_PATH"
        
        # Create local install directory
        INSTALL_DIR="/data/data/app.gamenative/lib"
        adb shell "mkdir -p $INSTALL_DIR"
        
        # Push files
        adb push "$LAYER_NAME" "$INSTALL_DIR/"
        adb shell "mkdir -p /data/data/app.gamenative/shared_prefs/"
        adb push "$MANIFEST_NAME" "/data/data/app.gamenative/shared_prefs/"
        
        # Set permissions
        adb shell "chmod 755 $INSTALL_DIR/$LAYER_NAME"
        adb shell "chmod 644 /data/data/app.gamenative/shared_prefs/$MANIFEST_NAME"
        
        echo "✓ GameNative-specific installation complete"
        echo ""
        echo "Next steps:"
        echo "  1. Enable frame generation in GameNative settings"
        echo "  2. Launch a game"
        echo "  3. Check logs: adb logcat -s 'GN-Framegen'"
        ;;
        
    3)
        echo "Cancelled"
        exit 0
        ;;
        
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac

echo ""
echo "Installation complete!"
EOF
chmod +x "$RELEASE_DIR/install.sh"

# Create README for release
cat > "$RELEASE_DIR/INSTALL.txt" << EOF
GN Framegen Layer v${VERSION}
============================

Android ABI: ${ABI}
Platform: ${PLATFORM}
Build Type: ${BUILD_TYPE}

FILES
-----
- libgn-framegen.so          - The Vulkan layer library
- VkLayer_GN_gamescope_framegen.json - Layer manifest
- install.sh                  - Interactive install script
- *.md                        - Documentation

INSTALLATION
------------

Method 1: GameNative Integration (Recommended)
1. Use ./install.sh and select option 2
2. Or manually copy files to GameNative lib directory

Method 2: APK Injection
1. Use ../install-to-apk.sh GameNative.apk
2. Install modified APK

Method 3: System-wide (Root required)
1. Use ./install.sh and select option 1
2. Or manually push to /system/lib64/

CONFIGURATION
-------------

Environment variables:
  GN_FG_ENABLE=1              - Enable frame generation
  GN_FG_MULTIPLIER=2          - Frame multiplier (2-4)
  GN_FG_FLOW_SCALE=0.6        - Optical flow scale (0.2-1.0)
  GN_FG_MODEL=0               - Model variant (0/1)
  GN_FG_FPS_LIMIT=0           - FPS limit (0=unlimited)

VERIFICATION
------------

Check layer is loaded:
  adb logcat -s "GN-Framegen" | grep "Instance created"

Monitor frame generation:
  adb logcat -s "GN-Framegen"

Expected output:
  GN-Framegen: Instance created successfully (VK_LAYER_GN_gamescope_framegen ${VERSION}...)
  GN-Framegen: Successfully generated N frames
  GN-Framegen: Presented frame N (captured history, generated M frames...)

TROUBLESHOOTING
---------------

See QUICKSTART.md and README.md for detailed troubleshooting.

Common issues:
- Layer not loading: Check VK_LAYER_PATH and VK_INSTANCE_LAYERS
- Shaders not found: Verify libgn-framegen.so size (~6MB for embedded shaders)
- Performance issues: Reduce GN_FG_MULTIPLIER or increase GN_FG_FLOW_SCALE

For support, see: https://github.com/xXJSONDeruloXx/GameNative
EOF

# Create archive
echo ""
echo "Creating archives..."
cd releases
tar -czf "${RELEASE_NAME}.tar.gz" "$RELEASE_NAME"
zip -rq "${RELEASE_NAME}.zip" "$RELEASE_NAME"
cd ..

echo ""
echo "=== Release Packaging Complete ==="
echo ""
echo "Release: $RELEASE_NAME"
echo ""
echo "Output files:"
echo "  releases/${RELEASE_NAME}/          - Unpacked release directory"
echo "  releases/${RELEASE_NAME}.tar.gz     - Tar.gz archive"
echo "  releases/${RELEASE_NAME}.zip        - ZIP archive"
echo ""
echo "Contents:"
ls -lh "$RELEASE_DIR/"
echo ""
echo "To install:"
echo "  cd releases/$RELEASE_NAME"
echo "  ./install.sh"
echo ""
