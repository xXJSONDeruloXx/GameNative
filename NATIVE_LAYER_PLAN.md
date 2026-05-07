# Option C: Native Android Layer Plan

## Goal
Create a new Vulkan implicit layer specifically for Android that uses GameScopeVK's SPIR-V shaders but replaces the DirectRendering socket protocol with direct `ANativeWindow` + `AHardwareBuffer` presentation.

## Why This Approach?

GameScopeVK's architecture:
- ✅ Excellent frame generation shaders (54 SPIR-V compute kernels)
- ❌ DirectRendering socket protocol (requires GameHub's libwinemu.so)
- ❌ ICD wrapper model (intercepts entire Vulkan driver)

Android native approach:
- ✅ Direct Surface control via `ANativeWindow`
- ✅ Zero-copy with `AHardwareBuffer` + `VK_ANDROID_external_memory_android_hardware_buffer`
- ✅ Standard Vulkan layer model (no ICD replacement)
- ✅ Works with any Android Vulkan app (not just Wine/DXVK)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    GameNative / Wine / DXVK               │
│                          │                                  │
│                          ▼                                  │
│  ┌───────────────────────────────────────────────────────┐   │
│  │         VK_LAYER_GN_gamescope_framegen                  │   │
│  │                    (NEW LAYER)                         │   │
│  │  ┌────────────────────────────────────────────────┐  │   │
│  │  │  GameScopeVK Shaders (54 SPIR-V compute)          │  │   │
│  │  │  - Optical flow estimation                      │  │   │
│  │  │  - Motion vector calculation                    │  │   │
│  │  │  - Frame warping/blending                       │  │   │
│  │  └────────────────────────────────────────────────┘  │   │
│  │                                                       │   │
│  │  ┌────────────────────────────────────────────────┐  │   │
│  │  │  ANativeWindow Presentation (NEW)               │  │   │
│  │  │  - No DirectRendering socket                  │  │   │
│  │  │  - Direct Surface/Buffer management             │  │   │
│  │  └────────────────────────────────────────────────┘  │   │
│  │                          │                           │   │
│  │                          ▼                           │   │
│  │              vkQueuePresentKHR (hooked)              │   │
│  │              - Captures app swapchain images         │   │
│  │              - Runs compute shaders (generates N-1)  │   │
│  │              - Presents real + generated frames        │   │
│  └──────────────────────────┼───────────────────────────┘   │
│                             │                                │
│                             ▼                                │
│  ┌───────────────────────────────────────────────────────┐   │
│  │           Real Vulkan Driver (Turnip/Adreno)          │   │
│  │              - vkCreateSwapchainKHR                  │   │
│  │              - vkAcquireNextImageKHR                 │   │
│  │              - Standard Android presentation          │   │
│  └───────────────────────────────────────────────────────┘   │
│                             │                                │
│                             ▼                                │
│  ┌───────────────────────────────────────────────────────┐   │
│  │           Android SurfaceFlinger                      │   │
│  │              ANativeWindow → BufferQueue → Display    │   │
│  └───────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Key Differences from GameScopeVK

| Aspect | GameScopeVK | New Native Layer |
|--------|-------------|------------------|
| **Type** | ICD wrapper | Implicit layer |
| **Install** | `VK_ICD_FILENAMES` | `VK_LAYER_PATH` + manifest |
| **Presentation** | DirectRendering socket | ANativeWindow blit |
| **Surface creation** | Managed internally | Intercept app's swapchain |
| **Buffer export** | Socket send | Direct AHardwareBuffer use |
| **Dependencies** | libxcb-dri3, libxcb-present, DR server | None (Android native) |
| **Control** | mmap control file | Layer settings / env vars |

## Implementation Steps

### Phase 1: Core Layer Infrastructure

1. **Create layer manifest**
   ```json
   {
     "file_format_version": "1.0.0",
     "layer": {
       "name": "VK_LAYER_GN_gamescope_framegen",
       "type": "GLOBAL",
       "api_version": "1.3.0",
       "library_path": "libgn-framegen.so",
       "implementation_version": "1",
       "description": "GameNative frame generation layer",
       "functions": {
         "vkGetInstanceProcAddr": "layer_GetInstanceProcAddr",
         "vkGetDeviceProcAddr": "layer_GetDeviceProcAddr"
       },
       "disable_environment": {
         "DISABLE_GN_FG": "1"
       }
     }
   }
   ```

2. **Implement layer entry points**
   ```cpp
   // layer.cpp
   VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL layer_GetInstanceProcAddr(
       VkInstance instance, const char* pName) {
       if (strcmp(pName, "vkCreateSwapchainKHR") == 0)
           return (PFN_vkVoidFunction)layer_CreateSwapchainKHR;
       if (strcmp(pName, "vkQueuePresentKHR") == 0)
           return (PFN_vkVoidFunction)layer_QueuePresentKHR;
       // ... chain to next layer
   }
   ```

3. **Swapchain interception**
   - Hook `vkCreateSwapchainKHR`: Create larger swapchain for intermediate frames
   - Hook `vkAcquireNextImageKHR`: Manage frame indices
   - Hook `vkQueuePresentKHR`: Run compute, present real + generated frames

### Phase 2: Shader Integration

1. **Embed extracted SPIR-V**
   ```cpp
   // shaders_embedded.cpp
   const uint32_t g_flow_estimate_spv[] = { 0x07230203, ... }; // SPIR-V magic + words
   const uint32_t g_warp_blend_spv[] = { ... };
   // ... 54 shaders total
   ```

2. **Create compute pipelines**
   ```cpp
   VkShaderModuleCreateInfo shaderInfo = {};
   shaderInfo.codeSize = sizeof(g_flow_estimate_spv);
   shaderInfo.pCode = g_flow_estimate_spv;
   vkCreateShaderModule(device, &shaderInfo, nullptr, &flowModule);
   ```

3. **Implement frame generation logic**
   ```cpp
   void GenerateFrame(VkCommandBuffer cmd, VkImage current, VkImage previous, VkImage output) {
       // 1. Optical flow: current vs previous
       vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, flowPipeline);
       vkCmdDispatch(cmd, width/16, height/16, 1);
       
       // 2. Warp previous frame using flow vectors
       vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, warpPipeline);
       vkCmdDispatch(cmd, width/16, height/16, 1);
       
       // 3. Blend warped frame with current
       vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, blendPipeline);
       vkCmdDispatch(cmd, width/16, height/16, 1);
   }
   ```

### Phase 3: ANativeWindow Integration

1. **Buffer management**
   ```cpp
   // Create AHardwareBuffer for compute output
   AHardwareBuffer_Desc desc = {};
   desc.width = width;
   desc.height = height;
   desc.layers = 1;
   desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
   desc.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
   AHardwareBuffer_allocate(&desc, &buffer);
   
   // Import to Vulkan
   VkImportAndroidHardwareBufferInfoANDROID importInfo = {};
   importInfo.buffer = buffer;
   vkCreateImage(..., &importInfo, ...);
   ```

2. **Presentation blit**
   ```cpp
   // Instead of DR socket, blit directly
   void PresentToSurface(AHardwareBuffer* buffer, ANativeWindow* window) {
       ANativeWindow_Buffer windowBuffer;
       ANativeWindow_lock(window, &windowBuffer, nullptr);
       
       // GPU blit or CPU copy based on setup
       AHardwareBuffer_lock(buffer, ...);
       // copy pixels...
       AHardwareBuffer_unlock(buffer);
       
       ANativeWindow_unlockAndPost(window);
   }
   ```

### Phase 4: Android Build

1. **CMake configuration**
   ```cmake
   cmake_minimum_required(VERSION 3.16)
   project(gn-framegen)
   
   find_package(Vulkan REQUIRED)
   
   add_library(gn-framegen SHARED
       src/layer.cpp
       src/framegen.cpp
       src/presentation.cpp
       src/shaders_embedded.cpp
   )
   
   target_link_libraries(gn-framegen
       Vulkan::Vulkan
       android
       log
   )
   ```

2. **Android.mk (alternative)**
   ```makefile
   LOCAL_PATH := $(call my-dir)
   
   include $(CLEAR_VARS)
   LOCAL_MODULE := gn-framegen
   LOCAL_SRC_FILES := $(wildcard $(LOCAL_PATH)/src/*.cpp)
   LOCAL_LDLIBS := -lvulkan -landroid -llog
   include $(BUILD_SHARED_LIBRARY)
   ```

## Files to Create

```
gn-native-layer/
├── CMakeLists.txt
├── VkLayer_GN_gamescope_framegen.json
├── src/
│   ├── layer.cpp              # Layer entry points
│   ├── framegen.cpp           # Frame generation logic
│   ├── framegen.hpp           # Frame generation headers
│   ├── presentation.cpp       # ANativeWindow integration
│   ├── presentation.hpp       # Presentation headers
│   ├── shaders_embedded.cpp   # Extracted SPIR-V shaders
│   ├── shaders_embedded.hpp   # Shader declarations
│   ├── dispatch.cpp          # Command buffer management
│   └── dispatch.hpp
├── include/
│   └── vulkan_wrappers.hpp   # Helper templates
└── extracted_shaders/      # Intermediate SPIR-V files
    ├── flow_estimate.spv
    ├── flow_refinement.spv
    ├── warp_forward.spv
    ├── warp_backward.spv
    ├── blend_spatial.spv
    └── ... (54 total)
```

## Configuration

| Environment Variable | Type | Default | Description |
|---------------------|------|---------|-------------|
| `GN_FG_ENABLE` | bool | 1 | Enable frame generation |
| `GN_FG_MULTIPLIER` | int | 2 | Frame multiplier (2- 4) |
| `GN_FG_FLOW_SCALE` | float | 0.6 | Flow scale (0.2-1.0) |
| `GN_FG_MODEL` | int | 0 | Model variant (0=default, 1=clear) |
| `GN_FG_FPS_LIMIT` | int | 0 | FPS limit (0=unlimited) |

## Risks & Challenges

| Risk | Mitigation |
|------|-----------|
| SPIR-V descriptors don't match | Careful reverse-engineering of GameScopeVK layouts |
| Frame pacing issues | Implement proper sync between real and generated frames |
| Performance on Mali GPUs | Test on multiple GPU vendors, may need fallbacks |
| App compatibility | Test with various swapchain configurations |
| ANativeWindow format mismatch | Handle format conversion in compute shaders |

## Success Criteria

1. ✅ Layer loads successfully on Android
2. ✅ Intercepts Vulkan swapchain calls
3. ✅ Frame generation produces smooth output
4. ✅ Control parameters (flow_scale, multiplier, model) functional
5. ✅ No DirectRendering socket dependency
6. ✅ Works with Wine/DXVK in GameNative

## Timeline Estimate

- Layer infrastructure: 2-3 days
- Shader integration: 2-3 days
- ANativeWindow presentation: 2-3 days
- Testing & refinement: 3-5 days
- **Total: 1.5-2 weeks**

## Comparison with Option B

| Aspect | Option B (LSFG-VK Swap) | Option C (Native Layer) |
|--------|--------------------------|-------------------------|
| **Code reuse** | High (uses LSFG-VK framework) | Medium (new layer) |
| **Complexity** | Medium (shader replacement) | Higher (new architecture) |
| **Flexibility** | Limited to LSFG-VK design | Full control over presentation |
| **Android integration** | Good | Better (ANativeWindow native) |
| **Maintenance** | Tied to LSFG-VK updates | Independent |
| **Timeline** | 1-2 weeks | 1.5-2 weeks |
| **Risk** | Lower (proven framework) | Higher (new code) |
