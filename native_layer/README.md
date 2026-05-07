# GameNative Vulkan Frame Generation Layer

A Vulkan layer that implements frame generation using SPIR-V shaders extracted from GameScopeVK.

## Features

- **Frame Generation**: Interpolates frames between real rendered frames
- **SPIR-V Shaders**: Uses embedded GameScopeVK compute shaders for optical flow and warping
- **Vulkan Layer**: Transparent integration via `VK_LAYER_GN_gamescope_framegen`
- **Android Support**: Designed for Android NDK build

## Architecture

```
Application (Wine/DXVK)
    │
    ▼
VK_LAYER_GN_gamescope_framegen
    │
    ┌─────────────┐
    │                  │
    ▼                  ▼
Frame Capture    Frame Generation
(CopyImage)      (Compute)
    │                  │
    └─────────────┘
                  │
                  ▼
         QueuePresentKHR
                  │
                  ▼
           Display (Real or Generated)
```

## Build Instructions

### Prerequisites

- Android NDK (r21 or later)
- CMake 3.16+
- Vulkan SDK headers (included in NDK)

### Pre-Build Checks

First, verify your NDK setup:

```bash
cd native_layer

# Check NDK environment and components
./check-ndk.sh

# Test CMake configuration (no compilation)
./test-cmake.sh
```

### Build for Android

```bash
cd native_layer

# Set NDK path (if not already set)
export ANDROID_NDK=/path/to/android-ndk

# Quick build (using Makefile)
make

# Or using build script directly
./build-android.sh

# With specific options
make build ABI=arm64-v8a PLATFORM=android-30 BUILD_TYPE=Release
# Or
./build-android.sh arm64-v8a android-30 Release
```

### Output

- `build-android-arm64-v8a/libgn-framegen.so` - The Vulkan layer library
- `VkLayer_GN_gamescope_framegen.json` - Layer manifest

## Installation

### Method 1: APK Injection

```bash
./install-to-apk.sh /path/to/GameNative.apk
```

This creates a modified APK with the layer embedded.

### Method 2: System Installation (Rooted Device)

```bash
adb push build-android-arm64-v8a/libgn-framegen.so /system/lib64/
adb push VkLayer_GN_gamescope_framegen.json /system/share/vulkan/explicit_layer.d/
```

### Method 3: Application-Specific (GameNative Integration)

See `GNFramegenManager.kt` for Android integration:

```kotlin
// Set environment variables before launching container
envVars.put("GN_FG_ENABLE", "1")
envVars.put("GN_FG_MULTIPLIER", "3")
envVars.put("GN_FG_FLOW_SCALE", "0.7")
envVars.put("VK_LAYER_PATH", manifestDir)
envVars.put("VK_INSTANCE_LAYERS", "VK_LAYER_GN_gamescope_framegen")
```

## Configuration

Environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `GN_FG_ENABLE` | Enable frame generation | 0 |
| `GN_FG_MULTIPLIER` | Frame multiplier (2-4) | 2 |
| `GN_FG_FLOW_SCALE` | Optical flow scale (0.2-1.0) | 0.6 |
| `GN_FG_MODEL` | Model variant (0/1) | 0 |
| `GN_FG_FPS_LIMIT` | FPS limit (0=unlimited) | 0 |

Example in Android:

```java
// In GameNative settings
ProcessBuilder pb = new ProcessBuilder("sh");
Map<String, String> env = pb.environment();
env.put("GN_FG_ENABLE", "1");
env.put("GN_FG_MULTIPLIER", "3");
env.put("GN_FG_FLOW_SCALE", "0.7");
```

## Technical Details

### Frame Generation Pipeline

1. **Capture**: Copy swapchain image to frame history buffer (`vkCmdCopyImage`)
2. **Optical Flow**: Compute motion vectors between current/previous frames
3. **Warp**: Distort frames along motion vectors for intermediate frames
4. **Blend**: Combine warped frames into final output
5. **Present**: Submit to display (real or generated frame)

### Frame Presentation Strategy

The layer uses **swapchain injection** to present generated frames:

```
Real Frame N:      [Capture → Generate] → Present (shows generated frame 0)
Generated Frame 1:  Present (shows generated frame 1)
Generated Frame 2:  Present (shows generated frame 2)
Real Frame N+1:    [Capture → Generate] → Present ...
```

Generated frames are copied to swapchain images via `vkCmdCopyImage`, allowing
seamless integration with existing swapchain infrastructure.

### Shader Integration

- **49 embedded SPIR-V shaders** (~6MB from GameScopeVK)
- Compute shaders with 16x16 workgroups
- Descriptor set layout: Set 0, Bindings 0 (uniform), 32 (input), 48 (output)

### Key Implementation Features

- **Dynamic queue family discovery**: Automatically detects graphics/compute queues
- **Memory type selection**: Proper device-local memory allocation
- **Synchronous compute**: Blocking fence wait (production: use semaphores)
- **Lazy initialization**: FrameGenerator created on first present with correct dimensions

## Known Limitations

- **Synchronous compute**: Uses blocking fence wait (CPU stalls until GPU complete)
  - Production builds should use semaphore-based synchronization
- **6 truncated SPIR-V shaders**: May limit some shader variants (48/54 validated)
- **FPS limiting**: Variable read but not enforced in current implementation

## Development

### Project Structure

```
native_layer/
├── src/
│   ├── layer.cpp/hpp           # Layer infrastructure
│   ├── framegen.cpp/hpp        # Frame generation pipeline
│   ├── shader_manager.cpp/hpp  # SPIR-V shader loading
│   ├── descriptor_manager.cpp/hpp  # Descriptor management
│   ├── version.hpp             # Version information
│   └── shaders_embedded.hpp    # Embedded SPIR-V data (~6MB)
├── VkLayer_GN_gamescope_framegen.json  # Layer manifest
├── CMakeLists.txt              # Build configuration
├── README.md                   # This file
├── IMPLEMENTATION_NOTES.md     # Technical implementation details
├── build-android.sh            # Android build script
├── install-to-apk.sh           # APK injection script
├── check-ndk.sh                # NDK environment checker
└── test-cmake.sh               # CMake configuration tester
```

### Shader Analysis

The embedded shaders were extracted from `libGameScopeVK.so` and analyzed:

- 54 SPIR-V files found via magic number detection (0x07230203)
- 48 validated with `spirv-val`
- 6 truncated due to 64KB extraction limit
- All use GLCompute execution model with 16x16x1 local size
- Descriptor binding analysis in `extracted_shaders/interface_analysis.json`

### Building for Development

```bash
# Check what will be built
./check-ndk.sh

# Test CMake configuration
./test-cmake.sh

# Full build
./build-android.sh

# Debug build with symbols
./build-android.sh arm64-v8a android-30 Debug
```

## Performance Considerations

### Expected Overhead

- Optical flow compute: ~0.5-1.0ms per frame
- Warp (N-1 frames): ~0.3-0.5ms per frame
- Blend: ~0.2-0.3ms
- **Total: ~1.0-2.0ms for 2x, ~2.0-4.0ms for 4x**

### Recommended Settings

- **Mobile (Adreno 640+)**: 2x multiplier, flowScale=0.5
- **High-end mobile (Adreno 650+)**: 3x multiplier, flowScale=0.6
- **Desktop-class mobile**: 4x multiplier, flowScale=0.7

## Debugging

### Log Messages to Watch

```
GN-Framegen: Instance created successfully (VK_LAYER_GN_gamescope_framegen 1.0.0 (Android arm64-v8a), shaders=embedded)
GN-Framegen: Device created successfully (cached 30 function pointers, graphics=0, compute=0)
GN-Framegen: Creating swapchain WxH, images=N
GN-Framegen: Created 2 frame history images (WxH)
GN-Framegen: Initializing FrameGenerator
GN-Framegen: Shaders loaded: flow=0x..., warp=0x..., blend=0x...
GN-Framegen: Copied swapchain image N to history[M] (WxH)
GN-Framegen: Successfully generated N frames
GN-Framegen: Presented frame N (captured history, generated M frames, historyIndex=X)
```

### Common Issues

**"Failed to load any shaders"**
- Check `shaders_embedded.hpp` is compiled
- Verify `EMBED_SHADERS=1` in CMake
- Check version log for `shaders=embedded`

**"Failed to create frame history image"**
- Memory allocation failure
- Check device memory limits
- Reduce generation count (GN_FG_MULTIPLIER)

**"Failed to wait for compute fence"**
- GPU hang/timeout
- Reduce work per frame
- Check shader compatibility with device

**Layer not loading**
- Verify `VK_LAYER_PATH` points to manifest directory
- Check `VK_INSTANCE_LAYERS` includes `VK_LAYER_GN_gamescope_framegen`
- Use `check-ndk.sh` to verify environment

## License

This implementation is part of GameNative. The SPIR-V shaders are extracted from GameScopeVK's proprietary `libGameScopeVK.so` binary for educational/research purposes.

## References

- Vulkan Loader Layers: https://github.com/KhronosGroup/Vulkan-Loader
- GameScopeVK: https://github.com/ValveSoftware/gamescope
- LSFG-VK: https://github.com/artvbs/lsfg-vk
- IMPLEMENTATION_NOTES.md - Technical implementation details
