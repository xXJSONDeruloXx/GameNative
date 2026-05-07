# GN Framegen Layer - Quick Start Guide

Get up and running with frame generation in 5 minutes.

## Prerequisites

- Android device with Vulkan support (Android 10+ recommended)
- Android NDK (r21 or later)
- GameNative APK (or compatible Wine/DXVK application)

## 1. Check Your Environment

```bash
cd gn-native-layer/native_layer

# Verify NDK is set up
./check-ndk.sh
```

If `ANDROID_NDK` is not set:
```bash
export ANDROID_NDK=/path/to/android-ndk
```

## 2. Build the Layer

```bash
# Quick build (arm64-v8a, android-30, Release)
./build-android.sh

# Or with specific options
./build-android.sh arm64-v8a android-30 Release
```

**Output**: `build-android-arm64-v8a/libgn-framegen.so`

## 3. Install to GameNative

### Option A: APK Injection (Recommended)

```bash
./install-to-apk.sh /path/to/GameNative.apk

# Install modified APK to device
adb install GameNative-with-framegen.apk
```

### Option B: Manual File Copy (Rooted Device)

```bash
# Push layer to device
adb push build-android-arm64-v8a/libgn-framegen.so /data/data/app.gamenative/lib/
adb push VkLayer_GN_gamescope_framegen.json /data/data/app.gamenative/shared_prefs/

# Set permissions
adb shell chmod 755 /data/data/app.gamenative/lib/libgn-framegen.so
```

## 4. Configure in GameNative

Open GameNative and configure the container:

1. **Enable Frame Generation**
   - Container settings → Advanced → GameScopeVK Frame Generation: **ON**

2. **Set Multiplier** (frames to generate between real frames)
   - 2x = 1 extra frame (33% smoother)
   - 3x = 2 extra frames (50% smoother)
   - 4x = 3 extra frames (60% smoother)

3. **Adjust Flow Scale** (motion sensitivity)
   - Lower (0.2-0.4): Faster, more artifacts
   - Default (0.6): Balanced
   - Higher (0.8-1.0): Slower, higher quality

## 5. Launch and Verify

### Check Logs

```bash
# Watch for layer initialization
adb logcat -s "GN-Framegen" | grep "created"

# Expected output:
# GN-Framegen: Instance created successfully (VK_LAYER_GN_gamescope_framegen 1.0.0 (Android arm64-v8a), shaders=embedded)
# GN-Framegen: Device created successfully (cached 30 function pointers, graphics=0, compute=0)
```

### Verify Frame Generation

```bash
# Watch frame generation in real-time
adb logcat -s "GN-Framegen"

# Expected output pattern:
# GN-Framegen: Copied swapchain image 0 to history[0]
# GN-Framegen: Generating 2 frames between history[1] and history[0]
# GN-Framegen: Successfully generated 2 frames
# GN-Framegen: Presented frame 1 (captured history, generated 2 frames, historyIndex=0)
```

## Troubleshooting

### Layer Not Loading

```bash
# Check if layer is in APK
unzip -l GameNative-with-framegen.apk | grep gn-framegen

# Check device logs for errors
./debug-layer.sh

# Verify environment variables
adb shell cat /proc/$(adb shell ps | grep gamenative | awk '{print $2}')/environ | tr '\0' '\n' | grep VK_LAYER
```

### Build Errors

```bash
# Test CMake configuration without building
./test-cmake.sh

# Check NDK components
./check-ndk.sh

# Full verbose build
cmake .. -DCMAKE_VERBOSE_MAKEFILE=ON
make VERBOSE=1
```

### Performance Issues

1. **Reduce multiplier**: Try 2x instead of 3x or 4x
2. **Increase flow scale**: Try 0.8-1.0 for less aggressive motion detection
3. **Check GPU load**: Use `adb shell dumpsys gfxinfo`

### Shader Errors

```bash
# Validate embedded shaders
./validate-shaders.py

# Check for truncated shaders
adb logcat -s "GN-Framegen" | grep "shader"
```

## Configuration Reference

### Environment Variables

```bash
# Core settings
GN_FG_ENABLE=1              # Enable/disable
GN_FG_MULTIPLIER=3          # 2-4 (frame multiplier)
GN_FG_FLOW_SCALE=0.6        # 0.2-1.0 (motion sensitivity)
GN_FG_MODEL=0               # 0=default, 1=clear
GN_FG_FPS_LIMIT=0           # 0=unlimited, or max FPS

# Vulkan loader
VK_LAYER_PATH=/path/to/layer
VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
```

### Recommended Settings by Device

| Device Class | Multiplier | Flow Scale | Expected FPS |
|--------------|------------|------------|--------------|
| Mid-range (Adreno 640) | 2x | 0.5 | 30→60 |
| High-end (Adreno 650) | 3x | 0.6 | 30→60, 45→90 |
| Flagship (Adreno 660+) | 4x | 0.7 | 30→80, 60→120 |

## Next Steps

### For Users
- Read [README.md](README.md) for detailed documentation
- Check [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) for technical details
- See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) (if available) for common issues

### For Developers
- See [CONTRIBUTING.md](CONTRIBUTING.md) for development workflow
- Read [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) for architecture
- Check [DOCUMENTATION.md](DOCUMENTATION.md) for full documentation index

### For Integrators
- See `GNFramegenManager.kt` for Android integration
- Check `VkLayer_GN_gamescope_framegen.json` for layer manifest
- Review `native_layer/README.md` for build customization

## Getting Help

1. Check [DOCUMENTATION.md](DOCUMENTATION.md) for full documentation
2. Review logs: `adb logcat -s "GN-Framegen"`
3. Run debug script: `./debug-layer.sh`
4. Open an issue on GitHub with logs and device info

## Common Questions

**Q: Does this work with all games?**  
A: Works with Vulkan games through Wine/DXVK. GameNative must use Bionic container.

**Q: Will this damage my device?**  
A: No. This is a standard Vulkan layer that intercepts presentation. It's read-only for game data.

**Q: Why is my FPS lower?**  
A: Frame generation adds ~1-4ms per frame. If base FPS is low (<30), overhead may be noticeable.

**Q: Can I use this on other apps?**  
A: Technically yes, but designed for GameNative/Wine/DXVK games. May not work with all Vulkan apps.

**Q: How do I uninstall?**  
A: Reinstall original GameNative APK, or disable in container settings.

---

**Full Documentation**: [DOCUMENTATION.md](DOCUMENTATION.md)  
**Project Status**: [PROJECT_STATUS.md](PROJECT_STATUS.md)  
**GitHub**: https://github.com/xXJSONDeruloXx/GameNative
