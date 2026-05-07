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
           Real Display
```

## Build Instructions

### Prerequisites

- Android NDK (r21 or later)
- CMake 3.16+
- Vulkan SDK headers

### Build for Android

```bash
cd native_layer

# Set NDK path
export ANDROID_NDK=/path/to/android-ndk

# Build
./build-android.sh

# Or manually:
mkdir build-android-arm64-v8a
cd build-android-arm64-v8a
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30
make
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

### Method 3: Application-Specific

Set environment variables in your application:

```bash
VK_LAYER_PATH=/path/to/layer
VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
```

## Configuration

Environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `GN_FG_ENABLE` | Enable frame generation | 0 |
| `GN_FG_MULTIPLIER` | Frame multiplier (2-4) | 2 |
| `GN_FG_FLOW_SCALE` | Optical flow scale (0.2-1.0) | 0.5 |
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

1. **Capture**: Copy swapchain image to frame history buffer
2. **Optical Flow**: Compute motion vectors between current/previous frames
3. **Warp**: Distort frames along motion vectors for intermediate frames
4. **Blend**: Combine warped frames into final output
5. **Present**: Submit to display

### Shader Integration

- 49 embedded SPIR-V shaders (~6MB)
- Compute shaders with 16x16 workgroups
- Descriptor set layout: Set 0, Bindings 0 (uniform), 32 (input), 48 (output)

### Known Limitations

- Generated frames are computed but not yet presented separately (currently only real frames are shown)
- Blocking fence wait for compute completion (should use semaphores)
- Hardcoded queue family 0 (should query for compute queue)
- 6 truncated SPIR-V shaders from original extraction (may limit some functionality)

## Development

### Project Structure

```
native_layer/
├── src/
│   ├── layer.cpp/hpp           # Layer infrastructure
│   ├── framegen.cpp/hpp        # Frame generation pipeline
│   ├── shader_manager.cpp/hpp  # SPIR-V shader loading
│   ├── descriptor_manager.cpp/hpp  # Descriptor management
│   └── shaders_embedded.hpp    # Embedded SPIR-V data (~6MB)
├── VkLayer_GN_gamescope_framegen.json  # Layer manifest
├── CMakeLists.txt            # Build configuration
└── build-android.sh          # Android build script
```

### Shader Analysis

The embedded shaders were extracted from `libGameScopeVK.so` and analyzed:

- 54 SPIR-V files found via magic number detection
- 48 validated with `spirv-val`
- 6 truncated due to 64KB extraction limit
- All use GLCompute execution model with 16x16x1 local size
- Descriptor binding analysis documented in `interface_analysis.json`

## License

This implementation is part of GameNative. The SPIR-V shaders are extracted from GameScopeVK's proprietary `libGameScopeVK.so` binary for educational/research purposes.

## References

- Vulkan Loader and Layers: https://github.com/KhronosGroup/Vulkan-Loader
- GameScopeVK: https://github.com/ValveSoftware/gamescope
- LSFG-VK: https://github.com/artvbs/lsfg-vk
