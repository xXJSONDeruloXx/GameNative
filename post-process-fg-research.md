# Post-process frame generation research

_Date: 2026-03-26_

## Goal

Research whether GameNative could add frame generation without relying on engine-provided motion vectors / depth from each game.

## Scope covered

This note summarizes investigation into:

1. `Mob-FGSR`
2. AMD `FidelityFX-SDK` frame generation / optical flow
3. true post-process frame generation candidates better aligned with GameNative
4. why a first prototype should target `glibc + Vortek` before `bionic + wrapper`
5. expected performance constraints

---

## Direct evidence

### 1) Mob-FGSR is not a practical direct integration target

Repository investigated:
- `https://github.com/Mob-FGSR/MobFGSR`
- cloned locally to `/tmp/MobFGSR`

Key findings:

- License is **CC BY-NC 4.0**:
  - `/tmp/MobFGSR/LICENSE`
- The project is a **desktop OpenGL 4.3 + GLFW executable**, not an Android/Vulkan runtime module:
  - `/tmp/MobFGSR/CMakeLists.txt`
  - `/tmp/MobFGSR/src/main.cpp`
- It is built around **offline frame-sequence processing** with hardcoded input/output directories and frame ranges:
  - `/tmp/MobFGSR/src/offscreen_renderer.h`
- It expects more than final color:
  - color
  - depth
  - motion vectors
  - jittered super-resolution inputs
  - see `/tmp/MobFGSR/src/offscreen_renderer.h`
  - and shader inputs under `/tmp/MobFGSR/resources/`

Assessment:
- useful as a paper/prototype reference
- not suitable for direct inclusion in GameNative
- not suitable for arbitrary Windows games running through Wine as a post-present solution

---

### 2) FidelityFX SDK frame generation is not a pure post-process drop-in for GameNative

Repository investigated:
- `https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK`
- cloned locally to `/tmp/FidelityFX-SDK`

Relevant docs/files:
- `/tmp/FidelityFX-SDK/README.md`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation.md`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation-api.md`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation-swap-chain.md`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation-ml.md`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/framegeneration/include/ffx_framegeneration.h`
- `/tmp/FidelityFX-SDK/Kits/FidelityFX/framegeneration/fsr3/include/ffx_opticalflow.h`

Key findings:

- The SDK contains an **Optical Flow** module:
  - `/tmp/FidelityFX-SDK/Kits/FidelityFX/framegeneration/fsr3/include/ffx_opticalflow.h`
- However, shipped frame generation still expects game-side data during prepare/dispatch:
  - depth
  - motion vectors
  - camera position/orientation
  - see `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation-api.md`
  - and `/tmp/FidelityFX-SDK/Kits/FidelityFX/framegeneration/include/ffx_framegeneration.h`
- In the shipped provider, optical flow is used **alongside** game vectors/depth, not as a total replacement:
  - `/tmp/FidelityFX-SDK/Kits/FidelityFX/framegeneration/fsr3/internal/ffx_provider_fsr3framegeneration.cpp`
- Current public SDK implementation investigated here is strongly **DX12 / DXGI swapchain** oriented:
  - backend files under `/tmp/FidelityFX-SDK/Kits/FidelityFX/backend/dx12/`
  - frame generation swapchain docs explicitly target `IDXGISwapChain4`
- SDK root README currently says Vulkan is not supported in SDK 2.2 samples:
  - `/tmp/FidelityFX-SDK/README.md`
- FSR 4 frame generation is even less applicable to GameNative:
  - Windows 11
  - DX12 Agility SDK
  - AMD 9000-series requirement
  - `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/frame-interpolation-ml.md`

Assessment:
- much better as a reference than Mob-FGSR
- not a direct solution for Android/GameNative
- optical flow exists, but the shipped FG path is **not** a true “color-only arbitrary game FG” path

---

### 3) Most realistic true post-process candidates found

#### 3.1) RIFE via ncnn Vulkan

Repositories investigated:
- `https://github.com/nihui/rife-ncnn-vulkan`
- cloned locally to `/tmp/rife-ncnn-vulkan`
- dependency also inspected: `https://github.com/Tencent/ncnn` cloned to `/tmp/ncnn`

Relevant files:
- `/tmp/rife-ncnn-vulkan/README.md`
- `/tmp/rife-ncnn-vulkan/LICENSE`
- `/tmp/rife-ncnn-vulkan/src/rife.h`
- `/tmp/rife-ncnn-vulkan/src/rife.cpp`
- `/tmp/ncnn/README.md`
- `/tmp/ncnn/src/mat.h`
- `/tmp/ncnn/src/command.cpp`

Findings:
- License is **MIT**:
  - `/tmp/rife-ncnn-vulkan/LICENSE`
- RIFE takes only **two frames** and generates an intermediate frame:
  - `/tmp/rife-ncnn-vulkan/README.md`
- The current repo is oriented around **CPU-side image IO**:
  - load image to CPU mat
  - upload to GPU
  - process
  - download result
  - see `/tmp/rife-ncnn-vulkan/src/rife.cpp`
- Internally it already uses Vulkan compute through ncnn:
  - `ncnn::VulkanDevice`
  - `ncnn::VkMat`
  - custom Vulkan pipelines in `/tmp/rife-ncnn-vulkan/src/rife.cpp`
- Model sizes vary widely; smaller modern models exist:
  - `rife-v4` ~9.9MB
  - `rife-v4.6` ~10MB
  - older variants are significantly larger
- ncnn is explicitly optimized for **mobile + Android + Vulkan**:
  - `/tmp/ncnn/README.md`
- ncnn also exposes Android hardware buffer / external image interop primitives:
  - `VkImageMat`
  - external image constructors
  - `from_android_hardware_buffer(...)`
  - `/tmp/ncnn/src/mat.h`
  - `/tmp/ncnn/src/command.cpp`

Assessment:
- strongest candidate found for a true post-process FG prototype
- but would still require custom integration work for zero-copy-ish GameNative usage

#### 3.2) IFRNet via ncnn Vulkan

Repository investigated:
- `https://github.com/nihui/ifrnet-ncnn-vulkan`
- cloned locally to `/tmp/ifrnet-ncnn-vulkan`

Relevant files:
- `/tmp/ifrnet-ncnn-vulkan/README.md`
- `/tmp/ifrnet-ncnn-vulkan/LICENSE`
- `/tmp/ifrnet-ncnn-vulkan/src/ifrnet.cpp`

Findings:
- License is **MIT**:
  - `/tmp/ifrnet-ncnn-vulkan/LICENSE`
- Same overall shape as RIFE:
  - takes two frames
  - outputs an interpolated frame
  - Vulkan/ncnn backend
- Smaller bundled models exist and look attractive for first experiments:
  - `IFRNet_S_*` ~5.7MB
  - standard models ~10MB

Assessment:
- probably the best first benchmark candidate if minimizing cost is the priority
- same integration work as RIFE still required

#### 3.3) Magpie as architecture reference only

Repository investigated:
- `https://github.com/Blinue/Magpie`
- cloned locally to `/tmp/Magpie`

Relevant files:
- `/tmp/Magpie/README.md`
- `/tmp/Magpie/src/Magpie.Core/Renderer.cpp`

Findings:
- Magpie is a **capture + scaling/effects compositor** for arbitrary Windows apps
- It demonstrates useful architecture patterns:
  - frame capture
  - effect chains
  - separate presentation path
- It does **not** provide frame generation

Assessment:
- good architectural inspiration for capture/effect pipeline design
- not a direct FG algorithm source

#### 3.4) Classical baseline only: FFmpeg `minterpolate`

Local check:
- `ffmpeg -h filter=minterpolate`

Assessment:
- useful only as a low-quality / non-interactive baseline idea
- not a serious target for GameNative game-time FG

---

### 4) Why a first prototype should target `glibc + Vortek`

Relevant GameNative files:
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- `app/src/main/java/com/winlator/xenvironment/components/VortekRendererComponent.java`
- `app/src/main/jniLibs/arm64-v8a/libvortekrenderer.so`
- `app/src/main/assets/graphics_driver/wrapper.tzst`
- `app/src/main/assets/graphics_driver/wrapper-v2.tzst`
- `app/src/main/assets/graphics_driver/wrapper-legacy.tzst`
- `app/src/main/assets/graphics_driver/wrapper-leegao.tzst`

Findings:

- `XServerScreen.kt` attaches `VortekRendererComponent` for:
  - `vortek`
  - `adreno`
  - `sd-8-elite`
- `VortekRendererComponent.java` exposes useful bridge points, including:
  - `getWindowHardwareBuffer(int windowId)`
  - `updateWindowContent(int windowId)`
- `libvortekrenderer.so` strings suggest present/swapchain interception already exists:
  - `vt_handle_vkCreateSwapchainKHR`
  - `vt_handle_vkQueuePresentKHR`
  - `XWindowSwapchain_presentImage`
- By contrast, the bionic wrapper path in this repo is mostly configured through **prebuilt packaged assets**:
  - extracted wrapper libraries from `graphics_driver/*.tzst`
  - env/config wiring in `XServerScreen.kt`
- The source for the bionic wrapper implementation is not present in-tree here; this repo mostly packages and configures it

Assessment:
- `glibc + Vortek` looks like the shortest path to a prototype because it offers a clearer in-repo integration surface
- `bionic + wrapper` may still become a later target, but is a worse first prototyping surface from this repo alone

---

### 5) Performance expectations should be conservative

Relevant notes:
- `/tmp/ncnn/docs/how-to-use-and-FAQ/FAQ-ncnn-vulkan.md`
- `/tmp/rife-ncnn-vulkan/README.md`
- `/tmp/ifrnet-ncnn-vulkan/README.md`

Findings:
- ncnn explicitly warns that Vulkan inference on ARM/mobile is not automatically faster than CPU for all models/workloads
- neither RIFE nor IFRNet repos provide evidence here that they are consistently **<10ms per generated frame** in a live game compositor scenario
- current repos also use CPU-image-style ingestion/output, which is too expensive for a final latency target unless reworked
- GameNative would need to run FG while the same device is already busy doing:
  - translated game rendering
  - existing compositing/present work
  - any optional image enhancement / HUD / overlay work

Assessment:
- **do not assume** sub-10ms/frame is achievable at 1080p in real gameplay
- best realistic first target is likely:
  - one generated frame
  - 540p or 720p FG input/output region
  - zero-copy-ish Vulkan/AHardwareBuffer path
  - only on devices with clear GPU headroom

---

## Hypotheses / open risks

These were not proven in this pass and should be treated as follow-up items:

1. A zero-copy-ish `AHardwareBuffer -> ncnn -> output image` path may be possible through ncnn external image support, but still needs implementation and measurement.
2. `IFRNet_S` is likely the best first model to benchmark for mobile viability, but this is still a hypothesis until measured inside GameNative.
3. `RIFE-v4` may produce better output quality than `IFRNet_S`, but likely at higher cost.
4. UI/HUD artifacts are expected for any post-process-only FG approach unless extra masking/composition work is added.
5. Frame pacing may become as hard as the interpolation itself.

---

## Recommended next experiment

### Prototype scope
- Backend: **glibc + Vortek only**
- Model order:
  1. `IFRNet_S_Vimeo90K`
  2. `RIFE-v4`
- Input/output path:
  - avoid CPU readback/upload
  - use Vulkan / `AHardwareBuffer` / external image path if possible

### Initial benchmark resolutions
- 960x540
- 1280x720
- optionally 1600x900 later

### Metrics to capture
- frame import cost
- inference cost
- output/composite cost
- total GPU time
- behavior while a real game is running (not just synthetic idle renderer)
- artifact classes:
  - HUD/text distortion
  - camera cuts / flashes
  - particles / alpha effects
  - ghosting / occlusion errors

### Success criteria for continuing
- no CPU round-trip required in hot path
- acceptable latency at reduced internal resolution
- generated frame quality good enough to justify more engineering work
- pacing can be made stable enough to feel better than doing nothing

---

## Bottom line

Current ranking for GameNative research direction:

1. **IFRNet-ncnn-vulkan** — best small-model post-process FG benchmark candidate
2. **RIFE-ncnn-vulkan** — strongest overall post-process FG reference / likely better quality candidate
3. **FFX Optical Flow + custom interpolator** — only if willing to build much more from scratch
4. **Magpie** — architecture reference only

For GameNative specifically:
- `Mob-FGSR` is not a practical direct path
- FidelityFX frame generation is useful reference material but not a direct arbitrary-game post-process solution
- true post-process FG is most plausibly explored through **ncnn Vulkan VFI models**
- first prototype should target **Vortek**, not **bionic wrapper**, because Vortek exposes a clearer modifiable integration surface in this repo
