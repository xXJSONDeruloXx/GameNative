# Contributing to GN Framegen Layer

Thank you for your interest in contributing to the GameNative Vulkan Frame Generation Layer!

## Getting Started

### Prerequisites

- Android NDK (r21 or later)
- CMake 3.16+
- Git
- Basic knowledge of Vulkan and C++

### Setup

```bash
# Clone the repository
git clone https://github.com/xXJSONDeruloXx/GameNative.git
cd GameNative/gn-native-layer

# Set up NDK environment
export ANDROID_NDK=/path/to/android-ndk

# Verify your environment
./native_layer/check-ndk.sh
```

## Development Workflow

### Building

```bash
cd native_layer

# Quick build
./build-android.sh

# Debug build
./build-android.sh arm64-v8a android-30 Debug

# Test CMake configuration only
./test-cmake.sh
```

### Testing

#### Unit Tests
Currently, the project relies on:
- `spirv-val` for shader validation
- Manual code review
- Runtime testing with GameNative

Future: Add automated unit tests for:
- Shader loading
- Memory type selection
- Queue family discovery

#### Integration Testing

1. **Build the layer**
   ```bash
   ./build-android.sh
   ```

2. **Install to GameNative APK**
   ```bash
   ./install-to-apk.sh /path/to/GameNative.apk
   ```

3. **Test with a game**
   - Launch GameNative with modified APK
   - Enable frame generation in settings
   - Verify log messages with `adb logcat -s "GN-Framegen"`

### Logging

Enable verbose logging for debugging:

```bash
adb shell setprop log.tag.GN-Framegen VERBOSE
```

Key log messages:
- `GN-Framegen: Instance created successfully` - Layer loaded
- `GN-Framegen: Shaders loaded: flow=...` - Shaders initialized
- `GN-Framegen: Successfully generated N frames` - Generation working
- `GN-Framegen: Presented frame N` - Presentation working

## Code Style

### C++

- **Standard**: C++17
- **Indentation**: 4 spaces (no tabs)
- **Braces**: Allman style
- **Naming**:
  - Classes: `PascalCase` (e.g., `FrameGenerator`)
  - Functions: `camelCase` (e.g., `generateFrames`)
  - Constants: `UPPER_SNAKE_CASE` (e.g., `MAX_FRAME_HISTORY`)
  - Member variables: `lowerCamelCase` with trailing underscore optional

### Example

```cpp
class FrameGenerator {
public:
    static constexpr uint32_t MAX_FRAMES = 4;
    
    VkResult initialize(VkExtent2D extent, VkFormat format);
    
private:
    VkDevice device_;
    uint32_t frameCount_;
};
```

### Vulkan Patterns

Follow Vulkan naming conventions:
- `vkFunctionName` for Vulkan functions
- `VkStructureType` for Vulkan types
- `VK_STRUCTURE_TYPE_*` for enum values

## Project Structure

```
native_layer/
├── src/
│   ├── layer.cpp/hpp          # Layer infrastructure
│   ├── framegen.cpp/hpp       # Frame generation pipeline
│   ├── shader_manager.cpp/hpp # Shader loading
│   ├── descriptor_manager.cpp/hpp # Descriptor management
│   ├── version.hpp            # Version information
│   └── shaders_embedded.hpp   # Embedded SPIR-V (~6MB)
├── VkLayer_GN_gamescope_framegen.json  # Layer manifest
├── CMakeLists.txt             # Build configuration
├── README.md                  # User documentation
├── IMPLEMENTATION_NOTES.md    # Technical details
├── CHANGELOG.md               # Version history
└── build scripts...           # Build utilities
```

## Areas for Contribution

### High Priority

1. **Asynchronous Compute**
   - Replace blocking `WaitForFences` with semaphores
   - Implement proper GPU/CPU synchronization
   - Profile performance gains

2. **FPS Limiting**
   - Implement `GN_FG_FPS_LIMIT` enforcement
   - Add frame pacing logic
   - Test with various frame rates

3. **Performance Profiling**
   - Add GPU timer queries
   - Log frame generation timing
   - Create performance dashboard

### Medium Priority

4. **Shader Hot-Reloading**
   - Load SPIR-V from files at runtime
   - Enable shader development iteration
   - Fallback to embedded if file load fails

5. **Desktop Platform Support**
   - Linux layer testing
   - Windows layer testing
   - Cross-platform CI/CD

6. **Error Handling**
   - Better error messages
   - Recovery from GPU reset
   - Graceful degradation

### Low Priority

7. **Alternative Presentation Strategies**
   - Multiple swapchain images
   - External composition
   - Direct surface access

8. **Quality Adjustments**
   - Dynamic quality based on GPU load
   - Adaptive flow scale
   - Quality presets

## Submitting Changes

### Pull Request Process

1. **Fork and branch**
   ```bash
   git checkout -b feature/my-feature
   ```

2. **Make changes**
   - Follow code style guidelines
   - Add comments for complex logic
   - Update documentation if needed

3. **Test**
   ```bash
   ./check-ndk.sh
   ./test-cmake.sh
   ./build-android.sh
   ```

4. **Commit**
   - Use clear, descriptive commit messages
   - Reference issues if applicable
   - One logical change per commit

5. **Push and create PR**
   ```bash
   git push origin feature/my-feature
   ```

### Commit Message Format

```
[type]: Brief description

Detailed explanation if needed.

- Bullet points for changes
- More bullet points

Fixes #123
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `refactor`: Code refactoring
- `perf`: Performance improvement
- `build`: Build system changes

### Review Process

- All PRs require review
- Address feedback promptly
- Maintain backwards compatibility when possible
- Update CHANGELOG.md for significant changes

## Debugging Tips

### Common Issues

**Layer not loading**
```bash
# Check layer path
adb shell echo $VK_LAYER_PATH

# Verify manifest exists
adb shell ls $VK_LAYER_PATH/VkLayer_GN_gamescope_framegen.json

# Check for loading errors
adb logcat -s "Vulkan" | grep -i error
```

**Shader loading failures**
```bash
# Verify embedded shaders
adb logcat -s "GN-Framegen" | grep -i shader

# Check spirv-val locally
spirv-val path/to/shader.spv
```

**Performance issues**
```bash
# Profile with GPU counters
adb shell dumpsys gfxinfo app.gamenative

# Check frame times
adb logcat -s "GN-Framegen" | grep "Presented frame"
```

### Development Tools

- **Android Studio**: APK debugging
- **RenderDoc**: Frame capture and analysis
- **GAPID**: Graphics API debugger
- **Nsight Graphics**: NVIDIA GPU profiling

## Resources

- [Vulkan Specification](https://www.khronos.org/registry/vulkan/specs/1.3/html/)
- [Vulkan Loader Layers](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderLayerInterface.md)
- [SPIR-V Specification](https://www.khronos.org/registry/SPIR-V/)
- [GameScopeVK](https://github.com/ValveSoftware/gamescope)

## Questions?

- Open an issue for bugs or feature requests
- Check existing issues before creating new ones
- Join discussions for design decisions

## License

This project is part of GameNative. By contributing, you agree that your contributions will be licensed under the same terms as the project.

## Acknowledgments

- SPIR-V shaders extracted from GameScopeVK by Valve Software
- Inspired by LSFG-VK architecture
- Thanks to all contributors and testers
