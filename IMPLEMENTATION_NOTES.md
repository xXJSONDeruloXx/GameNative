# Implementation Notes - GameNative Vulkan Frame Generation Layer

## Architecture Overview

### Key Design Decisions

1. **Layer Implementation Pattern**: Standard Vulkan loader layer
   - Implements `vkEnumerateInstanceLayerProperties` and related entry points
   - Chains to next layer via `vkGetInstanceProcAddr`/`vkGetDeviceProcAddr`
   - Caches function pointers in `DeviceData` for performance

2. **Frame Generation Timing**: Synchronous during `vkQueuePresentKHR`
   - Captures frame history before present
   - Runs compute shaders (optical flow → warp → blend)
   - Can inject generated frames into presentation stream
   - Uses blocking fence synchronization (CPU stalls until GPU complete)

3. **Generated Frame Strategy**: Swapchain injection
   - Copy generated frame images to swapchain images via `vkCmdCopyImage`
   - Reuses existing swapchain without recreation
   - Pattern: [Real] → [Generated 1] → [Generated 2] → [Real]

### Data Flow

```
Application renders frame N
    │
    ▼
vkQueuePresentKHR (intercepted)
    │
    ├─────────────────────────────────────┐
    │  Layer Processing                  │
    │                                    │
    │  1. Frame Capture:                 │
    │     - Copy swapchain image         │
    │       to frameHistory[current]     │
    │                                    │
    │  2. Check for pending              │
    │     generated frames:              │
    │     - If yes: copy to swapchain    │
    │     - If no: generate new batch    │
    │                                    │
    │  3. Frame Generation (if needed): │
    │     - Optical flow compute         │
    │     - Warp N-1 frames              │
    │     - Blend output                 │
    │     - Store in generatedFrames[]   │
    │     - Queue for presentation       │
    │                                    │
    │  4. Submit compute work            │
    │     - Wait for completion          │
    └─────────────────────────────────────┘
    │
    ▼
vkQueuePresentKHR (next layer)
    │
    ▼
Display (real or generated frame)
```

## Key Classes and Structures

### FrameGenerator
Manages the compute pipeline for frame generation:
- `Initialize()`: Sets up shaders, pipelines, intermediate images
- `GenerateFrames()`: Executes optical flow → warp → blend sequence
- Uses embedded GameScopeVK SPIR-V shaders

### LayerState
Global singleton managing layer-wide state:
- Instance/device/swapchain maps
- Configuration (from environment variables)
- Physical device to instance mapping

### DeviceData
Per-device state with cached function pointers:
- 30+ Vulkan function pointers cached
- Queue family indices (graphics/compute)
- Links to parent instance

### SwapchainData
Per-swapchain state:
- Frame history images (2x for temporal reference)
- Generated frame storage
- Command pool/buffer/fence for compute
- FrameGenerator instance (lazy initialized)

## Shader Pipeline

### Optical Flow (shader_003/004.spv)
Inputs:
- Binding 32: Previous frame (image2D)
- Binding 48: Current frame (image2D)
- Binding 0: Uniform buffer (flowScale, frameIndex, totalFrames)

Output:
- Flow vectors stored in intermediate image

### Warp (shader_005/006.spv)
Inputs:
- Binding 32: Source frame + flow vectors
- Binding 0: Uniform buffer (interpolation factor)

Output:
- Warped frame at specific temporal position

### Blend (shader_009/010/011.spv)
Inputs:
- Binding 32: Warped frames
- Binding 0: Uniform buffer

Output:
- Final interpolated frame

## Memory Management

### Frame History Images
- Created during swapchain creation
- Device-local memory (optimal for GPU access)
- 2 images forming circular buffer
- Transitions: UNDEFINED → TRANSFER_DST → SHADER_READ

### Generated Frame Images
- Created during FrameGenerator initialization
- Device-local memory
- N-1 images where N = multiplier
- Reused across multiple frames

### Intermediate Images (Flow Data)
- Created per frame generation
- Stores optical flow vectors
- Same dimensions as frame images

## Synchronization Strategy

### Current Implementation (Blocking)
```cpp
// Submit compute work
vkQueueSubmit(queue, cmdBuffer, computeFence);

// Wait for completion before present
vkWaitForFences(device, &computeFence, VK_TRUE, UINT64_MAX);
```

**Pros**:
- Simple, reliable
- No additional synchronization primitives needed
- Works with any queue family configuration

**Cons**:
- CPU stalls waiting for GPU
- Limits maximum FPS to ~30-60 depending on workload
- Not suitable for real-time performance

### Future Improvement (Semaphores)
```cpp
// Submit compute work, signal semaphore
vkQueueSubmit(queue, cmdBuffer, computeFinishedSemaphore);

// Present waits on compute semaphore
presentInfo.waitSemaphoreCount = 1;
presentInfo.pWaitSemaphores = &computeFinishedSemaphore;
vkQueuePresentKHR(queue, &presentInfo);
```

**Benefits**:
- CPU doesn't wait for GPU
- Higher FPS possible
- GPU can overlap work

**Challenges**:
- Requires proper semaphore lifecycle management
- More complex with multiple swapchains
- Need to handle GPU reset/recovery

## Configuration

### Environment Variables

| Variable | Values | Default | Description |
|----------|--------|---------|-------------|
| `GN_FG_ENABLE` | 0, 1 | 1 | Enable/disable layer |
| `GN_FG_MULTIPLIER` | 2-4 | 2 | Frame multiplier (2x = 1 extra frame) |
| `GN_FG_FLOW_SCALE` | 0.2-1.0 | 0.6 | Optical flow sensitivity |
| `GN_FG_MODEL` | 0, 1 | 0 | Model variant selection |
| `GN_FG_FPS_LIMIT` | 0-240 | 0 | FPS cap (0 = unlimited) |

### Android Integration

```kotlin
// In GameNative game launch
val env = mapOf(
    "GN_FG_ENABLE" to "1",
    "GN_FG_MULTIPLIER" to "3",
    "GN_FG_FLOW_SCALE" to "0.7"
)
processBuilder.environment().putAll(env)
```

## Known Limitations

1. **Synchronous Compute**: Uses blocking fence wait (see Synchronization Strategy)
2. **6 Truncated Shaders**: Shaders 002, 041, 042, 046, 047, 053 truncated at 64KB
3. **No FPS Limiting**: FPS_LIMIT variable read but not enforced in current implementation
4. **Single Swapchain**: Multiple swapchains per present not fully tested
5. **Swapchain Recreation**: Not handling dynamic recreation scenarios

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

### Bottlenecks
1. Optical flow (most compute intensive)
2. Memory bandwidth (intermediate images)
3. Present rate (synchronous blocking)

## Testing Strategy

### Unit Tests (Future)
- Shader validation with spirv-val
- Frame history capture verification
- Memory type selection correctness

### Integration Tests
1. **Basic functionality**: Layer loads without crash
2. **Frame generation**: Visual confirmation of interpolated frames
3. **Performance**: Frame time measurement
4. **Stability**: Long-running test (1+ hour)

### Test Applications
- Simple rotating cube (predictable motion)
- GameNative + Wine + DXVK test game
- Real-world game with motion

## Debugging

### Log Messages to Watch
```
GN-Framegen: Instance created successfully
GN-Framegen: Device created successfully (cached 30 function pointers)
GN-Framegen: Creating swapchain WxH, images=N
GN-Framegen: Created 2 frame history images
GN-Framegen: Initializing FrameGenerator
GN-Framegen: Shaders loaded: flow=0x..., warp=0x..., blend=0x...
GN-Framegen: Copied swapchain image N to history[M]
GN-Framegen: Successfully generated N frames
GN-Framegen: Presented frame N (captured history, generated M frames)
```

### Common Issues

**"Failed to load any shaders"**
- Check shaders_embedded.hpp is compiled
- Verify EMBED_SHADERS=1 in CMake

**"Failed to create frame history image"**
- Memory allocation failure
- Check device memory limits
- Reduce generation count

**"Failed to wait for compute fence"**
- GPU hang/timeout
- Reduce work per frame
- Check shader compatibility

## References

- Vulkan Loader Layers: https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderLayerInterface.md
- GameScopeVK: https://github.com/ValveSoftware/gamescope
- LSFG-VK (reference implementation): https://github.com/artvbs/lsfg-vk
- SPIR-V Specification: https://www.khronos.org/registry/SPIR-V/
