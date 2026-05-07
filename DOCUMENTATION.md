# GN Framegen Layer Documentation

Complete documentation index for the GameNative Vulkan Frame Generation Layer.

## Quick Start

**New to the project?** Start here:
1. [README.md](native_layer/README.md) - User guide for building and using the layer
2. [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) - Technical implementation details
3. [CONTRIBUTING.md](CONTRIBUTING.md) - How to contribute to the project

## Documentation Files

### For Users

| Document | Purpose | Audience |
|----------|---------|----------|
| [README.md](native_layer/README.md) | Build instructions, installation, configuration | End users, integrators |
| [CHANGELOG.md](CHANGELOG.md) | Version history, feature list, statistics | Users, developers |
| [INSTALL.md](native_layer/INSTALL.md) | Detailed installation guide (if exists) | System administrators |

### For Developers

| Document | Purpose | Audience |
|----------|---------|----------|
| [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) | Architecture, design decisions, technical details | Developers, architects |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development workflow, code style, contribution guidelines | Contributors |
| [README.md#technical-details](native_layer/README.md) | Frame pipeline, shader integration, debugging | Technical users |

### For Project Management

| Document | Purpose | Audience |
|----------|---------|----------|
| [CHANGELOG.md](CHANGELOG.md) | Release notes, version history | Project managers, release engineers |
| [ROADMAP.md](ROADMAP.md) | Future plans, priorities (if exists) | Stakeholders |

## Documentation Topics

### Building and Installation

- **Quick Build**: See [README.md#build-instructions](native_layer/README.md#build-instructions)
- **Pre-build Checks**: `native_layer/check-ndk.sh` and `native_layer/test-cmake.sh`
- **Installation Methods**:
  - APK Injection: [README.md#method-1-apk-injection](native_layer/README.md#method-1-apk-injection)
  - System Installation: [README.md#method-2-system-installation-rooted-device](native_layer/README.md#method-2-system-installation-rooted-device)
  - GameNative Integration: [README.md#method-3-application-specific-gamenative-integration](native_layer/README.md#method-3-application-specific-gamenative-integration)

### Configuration

- **Environment Variables**: [README.md#configuration](native_layer/README.md#configuration)
- **GameNative Settings**: [GNFramegenManager.kt](app/src/main/java/app/gamenative/utils/GNFramegenManager.kt)
- **Recommended Settings**: [README.md#performance-considerations](native_layer/README.md#performance-considerations)

### Technical Implementation

- **Architecture**: [IMPLEMENTATION_NOTES.md#architecture-overview](IMPLEMENTATION_NOTES.md#architecture-overview)
- **Frame Generation Pipeline**: [IMPLEMENTATION_NOTES.md#frame-generation-pipeline](IMPLEMENTATION_NOTES.md#frame-generation-pipeline)
- **Shader Integration**: [IMPLEMENTATION_NOTES.md#shader-pipeline](IMPLEMENTATION_NOTES.md#shader-pipeline)
- **Frame Presentation**: [IMPLEMENTATION_NOTES.md#frame-presentation-strategy](IMPLEMENTATION_NOTES.md#frame-presentation-strategy)
- **Synchronization**: [IMPLEMENTATION_NOTES.md#synchronization-strategy](IMPLEMENTATION_NOTES.md#synchronization-strategy)
- **Memory Management**: [IMPLEMENTATION_NOTES.md#memory-management](IMPLEMENTATION_NOTES.md#memory-management)

### Development

- **Getting Started**: [CONTRIBUTING.md#getting-started](CONTRIBUTING.md#getting-started)
- **Development Workflow**: [CONTRIBUTING.md#development-workflow](CONTRIBUTING.md#development-workflow)
- **Code Style**: [CONTRIBUTING.md#code-style](CONTRIBUTING.md#code-style)
- **Testing**: [CONTRIBUTING.md#testing](CONTRIBUTING.md#testing)
- **Project Structure**: [CONTRIBUTING.md#project-structure](CONTRIBUTING.md#project-structure)
- **Areas for Contribution**: [CONTRIBUTING.md#areas-for-contribution](CONTRIBUTING.md#areas-for-contribution)

### Debugging

- **Log Messages**: [README.md#log-messages-to-watch](native_layer/README.md#log-messages-to-watch)
- **Common Issues**: [README.md#common-issues](native_layer/README.md#common-issues)
- **Debugging Tools**: [CONTRIBUTING.md#development-tools](CONTRIBUTING.md#development-tools)
- **Debug Script**: `native_layer/debug-layer.sh`

## File Organization

```
gn-native-layer/
├── CHANGELOG.md                  # Version history
├── CONTRIBUTING.md             # Contribution guidelines
├── DOCUMENTATION.md            # This file - documentation index
├── IMPLEMENTATION_NOTES.md     # Technical implementation details
├── README.md                   # Project overview
└── native_layer/
    ├── README.md               # User guide (build, install, config)
    ├── build-android.sh        # Build script
    ├── check-ndk.sh            # NDK verification
    ├── debug-layer.sh          # Device debugging
    ├── install-to-apk.sh       # APK injection
    ├── test-cmake.sh           # CMake testing
    ├── src/
    │   ├── layer.cpp/hpp       # Layer infrastructure
    │   ├── framegen.cpp/hpp    # Frame generation
    │   ├── shader_manager.cpp/hpp # Shader loading
    │   ├── descriptor_manager.cpp/hpp # Descriptor management
    │   ├── version.hpp         # Version information
    │   └── shaders_embedded.hpp # Embedded SPIR-V (~6MB)
    └── VkLayer_GN_gamescope_framegen.json # Layer manifest
```

## Key Concepts

### What is GN Framegen?

A Vulkan explicit layer that generates interpolated frames between real rendered frames, effectively increasing perceived frame rate without requiring higher GPU rendering load from the application.

### How it Works

1. **Capture**: Intercepts `vkQueuePresentKHR`, copies swapchain image to frame history
2. **Generate**: Runs compute shaders (optical flow → warp → blend) to create interpolated frames
3. **Present**: Copies generated frames back to swapchain images and presents them

### Frame Generation Pipeline

```
Real Frame N        -> Capture to history
Real Frame N+1      -> Generate frames N.33, N.66 between N and N+1
Generated Frame N.33 -> Present (shows interpolated frame)
Generated Frame N.66 -> Present (shows interpolated frame)
Real Frame N+1      -> Capture to history, cycle repeats
```

### Technical Highlights

- **49 Embedded SPIR-V Shaders**: From GameScopeVK, ~6MB total
- **16x16 Workgroups**: Matching GameScopeVK shader layout
- **Dynamic Queue Discovery**: Automatic graphics/compute queue detection
- **Swapchain Injection**: Presents generated frames by copying to swapchain images
- **Device-Local Memory**: Optimal GPU memory allocation
- **Synchronous Compute**: Blocking fence wait (production: use semaphores)

## Version Information

- **Current Version**: 1.0.0
- **Release Date**: 2026-05-07
- **Total Commits**: 22
- **Lines of Code**: ~4,200 C++
- **SPIR-V Shaders**: 49 embedded (~6MB)

See [CHANGELOG.md](CHANGELOG.md) for detailed release notes.

## Quick Reference

### Environment Variables

```bash
export GN_FG_ENABLE=1           # Enable frame generation
export GN_FG_MULTIPLIER=3       # Generate 2 extra frames (3x total)
export GN_FG_FLOW_SCALE=0.7     # Optical flow sensitivity
export GN_FG_MODEL=0            # Model variant
export GN_FG_FPS_LIMIT=0        # No FPS limit
export VK_LAYER_PATH=/path/to/manifest
export VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
```

### Build Commands

```bash
# Verify environment
./native_layer/check-ndk.sh

# Test CMake configuration
./native_layer/test-cmake.sh

# Full build
./native_layer/build-android.sh

# Debug build
./native_layer/build-android.sh arm64-v8a android-30 Debug
```

### Debug Commands

```bash
# Check device status
./native_layer/debug-layer.sh

# Watch live logs
./native_layer/debug-layer.sh --watch

# Manual log inspection
adb logcat -s "GN-Framegen"
```

## Support

- **Issues**: Open an issue on GitHub
- **Questions**: Check documentation first, then open a discussion
- **Contributing**: See [CONTRIBUTING.md](CONTRIBUTING.md)

## License

This implementation is part of GameNative. The SPIR-V shaders are extracted from GameScopeVK's proprietary `libGameScopeVK.so` binary for educational/research purposes.

---

**Full implementation**: `https://github.com/xXJSONDeruloXx/GameNative.git`

**Main branch**: `gn-native-layer` (22 commits)
