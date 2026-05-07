# Option B: LSFG Shader Swap Plan

## Goal
Extract SPIR-V shaders from GameScopeVK's `libGameScopeVK.so` and adapt them to work within LSFG-VK's layer architecture.

## Why This Approach?

LSFG-VK already solves:
- ✅ Vulkan implicit layer architecture (works with Wine/DXVK)
- ✅ Swapchain interception (`vkQueuePresentKHR` hooking)
- ✅ Frame pacing and timing
- ✅ Android/Wine integration

GameScopeVK has:
- ✅ 54 embedded SPIR-V compute shaders
- ✅ Optical flow algorithm implementation
- ❌ Tightly coupled to DirectRendering socket protocol

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Wine + DXVK                               │
│                          │                                    │
│                          ▼                                    │
│  ┌───────────────────────────────────────────────────────┐   │
│  │      LSFG-VK Layer (modified)                          │   │
│  │  ┌─────────────────────────────────────────────────┐  │   │
│  │  │  GameScopeVK Shaders (extracted SPIR-V)          │  │   │
│  │  │  - Optical flow estimation                      │  │   │
│  │  │  - Frame warping/blending                       │  │   │
│  │  │  - 54 compute shaders                           │  │   │
│  │  └─────────────────────────────────────────────────┘  │   │
│  │                          │                           │   │
│  │                          ▼                           │   │
│  │              vkQueuePresentKHR (hooked)              │   │
│  │              (generates intermediate frames)         │   │
│  │                          │                           │   │
│  └──────────────────────────┼───────────────────────────┘   │
│                             │                                │
│                             ▼                                │
│  ┌───────────────────────────────────────────────────────┐   │
│  │           Real Vulkan Driver (Turnip/Adreno)          │   │
│  │              Standard swapchain presentation            │   │
│  └───────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Steps

### Phase 1: Shader Extraction

1. **Extract SPIR-V from `libGameScopeVK.so`**
   ```bash
   # Method 1: objdump + hex extraction from .rodata
   aarch64-linux-gnu-objdump -s -j .rodata libGameScopeVK.so > rodata.hex
   
   # Method 2: radare2 analysis
   r2 -A libGameScopeVK.so
   # Search for SPIR-V magic: 0x07230203
   ```

2. **Identify shader purposes**
   - SPIR-V compute shaders with `LocalSize` decorations
   - Look for `GLSL.std.450` extended instruction set
   - Pattern: most use `local_size(16, 16, 1)`, some `local_size(2, 2, 1)`

3. **Validate extracted shaders**
   ```bash
   # Use spirv-val from Vulkan SDK
   for spv in *.spv; do spirv-val --target-env vulkan1.3 $spv; done
   ```

### Phase 2: LSFG-VK Integration

1. **Map GameScopeVK control parameters to LSFG-VK config**

   | GameScopeVK | LSFG-VK Equivalent |
   |-------------|-------------------|
   | `flow_scale` (f32, 0.2-1.0) | `flowScale` |
   | `multiplier` (u8, 2-4) | `generationCount` |
   | `model` (u8, 0-1) | Shader variant selection |
   | `enable` (u8, 0/1) | Layer enable/disable |

2. **Replace LSFG-VK's shader loader**
   - Current: `loader` callback in `lsfg.cpp` loads shaders from filesystem
   - New: Embed extracted SPIR-V as byte arrays in source
   - Location: `lsfg-vk-upstream/framegen/v3.1_src/shaders/`

3. **Adapt descriptor layouts**
   - Reverse GameScopeVK's descriptor set layouts from disassembly
   - Match to LSFG-VK's `Context` class expectations
   - Key: `lsfg-vk-upstream/framegen/v3.1_src/context.cpp`

### Phase 3: Android Integration

1. **Build modified LSFG-VK for Android**
   - Target: `liblsfg-vk.so` for `arm64-v8a`
   - CMake: `lsfg-vk-upstream/CMakeLists.txt`
   - Dependencies: volk, Vulkan headers

2. **Update GameNative integration**
   - Replace current LSFG-VK shaders with GameScopeVK shaders
   - Keep existing layer manifest (`VkLayer_LS_frame_generation.json`)
   - Update `LSFG_CONFIG` handling to map to GameScopeVK parameters

## Files to Modify

```
gn-lsfg-shader-swap/
├── lsfg-vk-upstream/
│   ├── framegen/v3.1_src/shaders/     # New: GameScopeVK shaders as .cpp
│   ├── framegen/v3.1_src/context.cpp  # Modify: descriptor layouts
│   ├── framegen/v3.1_src/lsfg.cpp     # Modify: initialization
│   └── src/layer.cpp                  # Modify: config mapping
└── extracted_shaders/                   # Intermediate output
    ├── flow_estimation_0.spv
    ├── flow_estimation_1.spv
    ├── warp_0.spv
    ├── warp_1.spv
    ├── blend_0.spv
    └── ... (54 total)
```

## Risks & Challenges

| Risk | Mitigation |
|------|-----------|
| SPIR-V extraction incomplete | Use multiple methods, validate with spirv-val |
| Descriptor layout mismatch | Disassemble GameScopeVK, trace vkCreateDescriptorSetLayout calls |
| Shader variant (model 0/1) differences | Extract both variants, switch at runtime |
| Performance regression | Benchmark against original LSFG-VK |
| Legal concerns | Clean-room: only use SPIR-V, not source code |

## Success Criteria

1. Shaders extract and validate successfully
2. Modified LSFG-VK builds for Android arm64
3. Frame generation works in GameNative
4. Control parameters (flow_scale, multiplier, model) functional
5. No DirectRendering socket required (standard swapchain)

## Timeline Estimate

- Shader extraction: 1-2 days
- LSFG-VK integration: 3-5 days
- Android build & test: 2-3 days
- **Total: 1-2 weeks**
