# GN Framegen Layer - Usage Guide

Complete guide for building, deploying, and using the GN Framegen Layer with GameNative.

## Quick Start

For the impatient - one command to build and deploy:

```bash
# Set your Android NDK path
export ANDROID_NDK=/path/to/android-ndk

# Build everything and prepare APK
cd gn-native-layer
./build-and-deploy.sh Release arm64-v8a

# The APK will be at ../GameNative/app/build/outputs/apk/debug/app-debug.apk
```

## Full Build Process

### Prerequisites

1. **Android NDK** (r21 or later)
   - Download from [Android Developer](https://developer.android.com/ndk/downloads)
   - Or use Android Studio SDK Manager
   - Set environment variable: `export ANDROID_NDK=/path/to/ndk`

2. **CMake** (3.16+)
   - macOS: `brew install cmake`
   - Ubuntu: `sudo apt-get install cmake`

3. **ADB** (Android Debug Bridge)
   - Comes with Android SDK platform-tools
   - `adb devices` should show your device

### Step 1: Build the Layer

```bash
cd gn-native-layer/native_layer

# Check environment
./check-ndk.sh

# Build
make build
# or
./build-android.sh
```

Output: `build-android-arm64-v8a/libgn-framegen.so` (~5.2MB with 49 shaders)

### Step 2: Copy to GameNative Assets

```bash
cd gn-native-layer
./copy-to-assets.sh
```

This copies the built layer to GameNative's assets directory.

### Step 3: Build GameNative APK

```bash
cd ../GameNative
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Step 4: Install and Test

```bash
# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Or uninstall first if already installed
adb uninstall app.gamenative
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Using GN Framegen in GameNative

### Enable Frame Generation

1. Open GameNative
2. Create or edit a Container
3. Go to **Graphics** settings
4. Find **GN Framegen Layer** section
5. Toggle **Enable GN Framegen Layer**
6. Configure:
   - **Multiplier**: 2×, 3×, or 4× frame rate
   - **Flow Scale**: 0.2-1.0 (lower = fewer artifacts)
   - **Model**: Default (balanced) or Clear (higher quality)

### Launch a Game

1. Launch any Vulkan or DXVK game
2. The layer automatically intercepts presentation
3. Frame generation begins after 2 frames are captured

### Verify It's Working

```bash
# Watch logs
adb logcat -s "GN-Framegen" -s "BionicProgramLauncherComponent"

# Expected output:
# GN-Framegen: Instance created successfully (VK_LAYER_GN_gamescope_framegen 1.0.0)
# GN-Framegen: Successfully generated N frames
# GN-Framegen: Presented frame N (captured history, generated M frames...)
```

## Configuration Options

### Environment Variables

Set these in GameNative Container settings (Advanced → Environment Variables):

| Variable | Default | Range | Description |
|----------|---------|-------|-------------|
| `GN_FG_ENABLE` | 0 | 0/1 | Enable frame generation |
| `GN_FG_MULTIPLIER` | 2 | 2-4 | Frame multiplier |
| `GN_FG_FLOW_SCALE` | 0.6 | 0.2-1.0 | Optical flow sensitivity |
| `GN_FG_MODEL` | 0 | 0/1 | Model variant |
| `GN_FG_FPS_LIMIT` | 0 | 0+ | FPS limit (0=unlimited) |

### Performance Tuning

For different scenarios:

**High-end device (Snapdragon 8 Gen 3)**:
```
Multiplier: 3× or 4×
Flow Scale: 0.4-0.6
Model: 0 (Default)
```

**Mid-range device (Snapdragon 7+ Gen 2)**:
```
Multiplier: 2×
Flow Scale: 0.5-0.7
Model: 0 (Default)
```

**Fidelity mode (slower but clearer)**:
```
Multiplier: 2×
Flow Scale: 0.3-0.4
Model: 1 (Clear)
```

## Troubleshooting

### Layer Not Loading

**Symptom**: No "GN-Framegen" log messages

**Check**:
```bash
# 1. Verify APK has the layer
unzip -l app-debug.apk | grep gn-framegen

# 2. Check container has the layer files
adb shell ls -la /data/data/app.gamenative/.../.local/lib/
adb shell ls -la /data/data/app.gamenative/.../.local/share/vulkan/explicit_layer.d/

# 3. Check environment variables
adb logcat -s "BionicProgramLauncherComponent" | grep GN_FG
```

### Shaders Not Found

**Symptom**: "No suitable optical flow/warp/blend shader found"

**Cause**: Layer library wasn't built with embedded shaders

**Fix**: Rebuild with proper headers, ensure `shaders_embedded.hpp` is included.

### Black Screen or Crashes

**Symptom**: Game shows black screen or crashes

**Troubleshooting**:
1. Try 2× multiplier first (lower resource usage)
2. Increase flow scale (0.7-1.0)
3. Check device supports compute shaders
4. Try with simpler games first

### Performance Issues

**Symptom**: Low FPS, stuttering

**Solutions**:
1. Lower multiplier (2× instead of 4×)
2. Increase flow scale
3. Disable other heavy features (DLSS, FSR)
4. Lower game graphics settings

## Development & Debugging

### Debug Build

```bash
cd gn-native-layer/native_layer
make build BUILD_TYPE=Debug
```

More verbose logging enabled.

### Clean Build

```bash
cd gn-native-layer/native_layer
make clean
make build
```

### Validate Shaders

```bash
cd gn-native-layer/native_layer
./validate-shaders.py
```

### Device Debugging

```bash
# Full debug script
cd gn-native-layer/native_layer
./debug-layer.sh

# Manual log monitoring
adb logcat -c  # Clear
adb logcat -s "GN-Framegen"  # Watch
```

## Build Scripts Reference

| Script | Purpose | Location |
|--------|---------|----------|
| `build-android.sh` | Build the layer | `native_layer/` |
| `copy-to-assets.sh` | Copy to GameNative | `gn-native-layer/` |
| `build-and-deploy.sh` | Full build pipeline | `gn-native-layer/` |
| `check-ndk.sh` | Verify NDK setup | `native_layer/` |
| `test-cmake.sh` | Test CMake config | `native_layer/` |
| `debug-layer.sh` | Debug on device | `native_layer/` |
| `validate-shaders.py` | Validate SPIR-V | `native_layer/` |
| `Makefile` | Standard build interface | `native_layer/` |

## Architecture Overview

```
GameNative
├── GNFramegenManager.kt
│   ├── ensureRuntimeInstalled() ← Copies from APK assets
│   ├── applyLaunchEnv() ← Sets environment variables
│   └── isRuntimeInstalled() ← Version check
├── Assets (bundled in APK)
│   └── gn_framegen/android_arm64_v8a/
│       ├── libgn-framegen.so (~5.2MB)
│       └── VkLayer_GN_gamescope_framegen.json
└── BionicProgramLauncherComponent.java
    └── Calls GNFramegenManager at launch

Container Runtime
├── ~/.local/lib/libgn-framegen.so
├── ~/.local/share/vulkan/explicit_layer.d/VkLayer_GN_gamescope_framegen.json
└── Environment: GN_FG_ENABLE=1, etc.

Vulkan Layer
├── Intercepts vkQueuePresentKHR
├── Captures frame history
└── Generates interpolated frames via SPIR-V compute shaders
```

## Support & Feedback

- Issues: [GitHub Issues](https://github.com/xXJSONDeruloXx/GameNative/issues)
- Discussions: [GitHub Discussions](https://github.com/xXJSONDeruloXx/GameNative/discussions)

## License

Same as GameNative project (see LICENSE file).
