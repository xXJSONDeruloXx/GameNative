#!/bin/bash
# Debug script for GN Framegen Layer - Check layer status on connected Android device

set -e

echo "=== GN Framegen Layer - Device Debug Script ==="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No Android device connected"
    echo "Please connect a device or start an emulator"
    exit 1
fi

echo "Device connected: $(adb shell getprop ro.product.model)"
echo ""

# Check for log messages
echo "Recent GN-Framegen log messages:"
echo "----------------------------------------"
adb logcat -d -t 100 | grep -i "GN-Framegen" | tail -20 || echo "No log messages found"
echo ""

# Check if layer library exists in common locations
echo "Checking for layer library..."
echo "----------------------------------------"

check_paths=(
    "/data/data/app.gamenative/lib/libgn-framegen.so"
    "/system/lib64/libgn-framegen.so"
    "/vendor/lib64/libgn-framegen.so"
)

found=false
for path in "${check_paths[@]}"; do
    if adb shell "test -f $path" 2>/dev/null; then
        echo "  ✓ Found: $path"
        size=$(adb shell ls -l $path 2>/dev/null | awk '{print $5}')
        echo "    Size: $size bytes"
        found=true
    fi
done

if [ "$found" = false ]; then
    echo "  ✗ Layer library not found in standard locations"
    echo "    Layer may not be installed or in non-standard location"
fi
echo ""

# Check layer manifest
echo "Checking for layer manifest..."
echo "----------------------------------------"
manifest_paths=(
    "/data/data/app.gamenatic/shared_prefs/VkLayer_GN_gamescope_framegen.json"
    "/system/share/vulkan/explicit_layer.d/VkLayer_GN_gamescope_framegen.json"
)

for path in "${manifest_paths[@]}"; do
    if adb shell "test -f $path" 2>/dev/null; then
        echo "  ✓ Found: $path"
    fi
done
echo ""

# Check environment variables for GameNative process
echo "Checking GameNative process environment..."
echo "----------------------------------------"
gamenative_pid=$(adb shell ps | grep -i gamenative | awk '{print $2}' | head -1)
if [ -n "$gamenative_pid" ]; then
    echo "  ✓ GameNative running (PID: $gamenative_pid)"
    
    # Check environment
    env=$(adb shell cat /proc/$gamenative_pid/environ 2>/dev/null | tr '\0' '\n' | grep -E "VK_LAYER|GN_FG" || true)
    if [ -n "$env" ]; then
        echo "  Layer environment variables:"
        echo "$env" | while read line; do
            echo "    $line"
        done
    else
        echo "  ✗ No layer environment variables found"
        echo "    Layer not enabled or not loaded via environment"
    fi
else
    echo "  ✗ GameNative not running"
fi
echo ""

# Check Vulkan loader debug
echo "Vulkan loader debug info:"
echo "----------------------------------------"
adb shell getprop log.tag.vulkan 2>/dev/null || echo "  vulkan tag not set"
adb shell getprop log.tag.VK_LAYER 2>/dev/null || echo "  VK_LAYER tag not set"
echo ""

# Check for layer loading errors
echo "Recent layer loading errors:"
echo "----------------------------------------"
adb logcat -d -t 100 | grep -iE "(vulkan|layer).*error" | tail -10 || echo "No errors found"
echo ""

# Capture real-time logs if requested
if [ "$1" = "--watch" ] || [ "$1" = "-w" ]; then
    echo "Watching for new log messages (Ctrl+C to stop)..."
    echo "----------------------------------------"
    adb logcat -v threadtime | grep -i "GN-Framegen"
fi

echo "=== Debug Script Complete ==="
echo ""
echo "Tips:"
echo "  - Run with --watch to monitor live logs: ./debug-layer.sh --watch"
echo "  - Check layer is installed: ./install-to-apk.sh /path/to/GameNative.apk"
echo "  - Clear logs: adb logcat -c"
echo "  - Get full log: adb logcat -d > gn-framegen.log"
