# Changelog

All notable changes to the GN Framegen Vulkan Layer project.

## [1.0.0] - 2026-05-07

### Added - Core Implementation

#### SPIR-V Shader Extraction
- Extracted 54 SPIR-V shaders from `libGameScopeVK.so` binary
- 48 shaders validated with `spirv-val` tool
- Created `shaders_embedded.hpp` (~6MB) with constexpr byte arrays
- Shader registry for runtime lookup by name

#### Vulkan Layer Infrastructure
- Complete Vulkan explicit layer implementation
- 30+ cached function pointers for efficient dispatch
- Physical device tracking via `EnumeratePhysicalDevices` interception
- Instance/device/swapchain lifecycle management
- Proper chain advancement for loader compatibility

#### Frame Generation Pipeline
- Optical flow compute shader execution (motion vector detection)
- Warp shader for temporal interpolation
- Blend shader for final frame composition
- 16x16 workgroup dispatch matching GameScopeVK shader layout
- Image layout transitions and memory barriers

#### Resource Management
- Frame history buffers (2x circular buffer for temporal reference)
- Generated frame image storage
- Device-local memory allocation with proper type selection
- Uniform buffer for frame generation parameters
- Descriptor pool and set management

#### Frame Presentation
- Swapchain injection strategy for presenting generated frames
- `vkCmdCopyImage` for copying generated frames to swapchain
- Pattern: [Real] → [Generated 1] → [Generated 2] → ... → [Real]
- Pending frame queue management

#### Dynamic System Discovery
- Automatic graphics queue family detection (`VK_QUEUE_GRAPHICS_BIT`)
- Automatic compute queue family detection (`VK_QUEUE_COMPUTE_BIT`)
- Preference for dedicated compute queues
- Graceful fallback to graphics queue for compute
- `FindMemoryType()` helper for device-local memory selection

#### Configuration System
- Environment variable-based configuration:
  - `GN_FG_ENABLE` - Enable/disable layer
  - `GN_FG_MULTIPLIER` - Frame multiplier (2-4)
  - `GN_FG_FLOW_SCALE` - Optical flow scale (0.2-1.0)
  - `GN_FG_MODEL` - Model variant (0/1)
  - `GN_FG_FPS_LIMIT` - FPS limit (0=unlimited)
- `LayerConfig::FromEnvironment()` for parsing

#### Build System
- `CMakeLists.txt` for Android NDK build
- `build-android.sh` - Configurable build script (ABI, platform, build type)
- `install-to-apk.sh` - APK injection with optional signing
- `check-ndk.sh` - NDK environment verification
- `test-cmake.sh` - CMake configuration testing
- Cross-platform support (Android primary, Linux/Windows stubs)

#### Documentation
- `README.md` - Comprehensive user guide with architecture, build, install
- `IMPLEMENTATION_NOTES.md` - Technical implementation details
- `CHANGELOG.md` - This file, tracking all changes
- Inline code documentation for key functions

#### GameNative Integration
- `GNFramegenManager.kt` - Android integration manager
- Environment variable setup for container launch
- Conflict handling with GameScopeVK and LSFG-VK
- Reuses GameNative UI settings for compatibility

#### Version Information
- `version.hpp` with version, platform, architecture detection
- Build-time constants for platform identification
- `GetVersionString()` for logging and debugging

### Technical Details

#### Files Created
- `native_layer/src/layer.cpp/hpp` (~62KB) - Layer infrastructure
- `native_layer/src/framegen.cpp/hpp` (~31KB) - Frame generation
- `native_layer/src/shader_manager.cpp/hpp` - Shader loading
- `native_layer/src/descriptor_manager.cpp/hpp` - Descriptor management
- `native_layer/src/version.hpp` - Version information
- `native_layer/src/shaders_embedded.hpp` (~6MB) - SPIR-V data
- `native_layer/VkLayer_GN_gamescope_framegen.json` - Layer manifest
- `native_layer/CMakeLists.txt` - Build configuration

#### Key Design Decisions
1. **Layer Type**: Vulkan explicit layer (not ICD wrapper like GameScopeVK)
2. **Presentation Strategy**: Swapchain injection via image copying
3. **Synchronization**: Blocking fence wait (production: use semaphores)
4. **Shader Loading**: Embedded at compile time (no runtime file access)
5. **Memory Strategy**: Device-local for performance
6. **Queue Discovery**: Dynamic based on device capabilities

#### Architecture
```
Application (Wine/DXVK)
    │
    ▼
VK_LAYER_GN_gamescope_framegen
    │
    ├── Frame Capture (vkCmdCopyImage)
    │
    ├── Frame Generation
    │   ├── Optical Flow (compute)
    │   ├── Warp (compute)
    │   └── Blend (compute)
    │
    └── Presentation
        ├── Copy to swapchain
        └── QueuePresentKHR
```

### Known Limitations

- **Synchronous Compute**: Uses blocking fence wait (CPU stalls until GPU complete)
  - Production should use semaphores for async operation
- **6 Truncated Shaders**: May limit some shader variants (48/54 validated)
- **FPS Limiting**: Variable read but not enforced in current implementation
- **Shader Compatibility**: Relies on GameScopeVK SPIR-V (proprietary)

### Future Improvements

- [ ] Asynchronous compute with semaphores
- [ ] FPS limiting implementation
- [ ] Multiple swapchain support verification
- [ ] Dynamic quality adjustment based on GPU load
- [ ] Frame time profiling and telemetry
- [ ] Alternative shader loading (external SPIR-V files)
- [ ] Desktop platform support (Linux, Windows)

## Statistics

- **Total Commits**: 21 (gn-native-layer branch)
- **Lines of Code**: ~4,200 C++ across 6 source files
- **Embedded Data**: ~6MB SPIR-V shaders (49 shaders)
- **Build Scripts**: 5 shell scripts
- **Documentation**: 3 major markdown files

## References

- Based on SPIR-V shaders extracted from GameScopeVK
- Inspired by LSFG-VK architecture patterns
- Vulkan Loader Layer Interface Specification

---

**Full implementation available at:**
`https://github.com/xXJSONDeruloXx/GameNative.git`

**Branches:**
- `gn-native-layer` - Layer implementation (21 commits)
- `feat/gamescope-vk-experiment` - GameNative integration (4 commits)
