# GameScopeVK Integration into GameNative

## Status: BLACK SCREEN — Root Cause Identified

GameScopeVK loads, detects the Adreno driver, creates a Vulkan device with all required extensions, but **cannot present frames** because the DirectRendering socket server is missing from GameNative.

---

## Architecture

### How GameHub Does It

```
┌─────────────────────────────────────────────────────────────────┐
│ GameHub App                                                     │
│                                                                 │
│  ┌──────────┐    ┌─────────────────────────────────────────┐    │
│  │  Wine +   │    │           libwinemu.so                  │    │
│  │  DXVK     │───▶│  - XServer (X11)                       │    │
│  │           │    │  - DirectRendering server (Unix socket) │    │
│  └──────────┘    │  - Android Surface/SurfaceControl        │    │
│       │          └──────────────┬──────────────────────────┘    │
│       ▼                         │ socket: .dr.sock              │
│  ┌──────────────────┐          │                                │
│  │ libGameScopeVK.so│──────────┘                                │
│  │ (Vulkan ICD)     │  sends AHardwareBuffer handles           │
│  │                  │  via Unix socket                          │
│  │ - Intercepts     │                                          │
│  │   vkQueuePresent │                                          │
│  │ - Frame gen via  │                                          │
│  │   SPIR-V shaders │                                          │
│  └──────────────────┘                                          │
│       │                                                         │
│       ▼ dlopen()                                                │
│  ┌──────────────────┐                                          │
│  │ vulkan.adreno.so  │  (proprietary Qualcomm driver)          │
│  └──────────────────┘                                          │
│       ▼                                                         │
│  AHardwareBuffer-allocated images                               │
└─────────────────────────────────────────────────────────────────┘
```

### How GameNative Currently Does It (broken)

```
┌─────────────────────────────────────────────────────────────────┐
│ GameNative App                                                  │
│                                                                 │
│  ┌──────────┐    ┌─────────────────────────────────────────┐    │
│  │  Wine +   │    │     libxserver.so                       │    │
│  │  DXVK     │───▶│  - XServer (X11)                       │    │
│  │           │    │  - NO DirectRendering server            │    │
│  └──────────┘    └─────────────────────────────────────────┘    │
│       │                                                          │
│       ▼                                                          │
│  ┌──────────────────┐                                           │
│  │ libGameScopeVK.so│                                           │
│  │ (Vulkan ICD)     │──▶ tries to connect to DR_SOCK_PATH      │
│  │                  │    → "DirectRendering: Failed to connect" │
│  │                  │    → cannot present frames                 │
│  └──────────────────┘    → BLACK SCREEN                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Root Cause Analysis

### The DirectRendering Protocol

GameScopeVK uses a **client-server Unix socket protocol** for frame presentation:

1. **Server side** (GameHub's `libwinemu.so`):
   - Creates Android `Surface` + `SurfaceControl` objects
   - Listens on `<rootDir>/.dr.sock` (set via `DR_SOCK_PATH` env var)
   - `nativeInitialize(surface, surfaceControl, cursorSurface, cursorSurfaceControl, socketPath, width, height)`
   - Receives AHardwareBuffer handles via `recvmsg()` + ancillary data (SCM_RIGHTS)
   - Blits received buffers to the Android `Surface`

2. **Client side** (`libGameScopeVK.so`):
   - Connects to `DR_SOCK_PATH` on startup
   - After frame generation, sends AHardwareBuffer via `AHardwareBuffer_sendHandleToUnixSocket()`
   - Writes image index over the socket
   - Source: `direct_rendering_client.cpp`

### Key Binary Strings (libGameScopeVK.so)

| Offset | String | Meaning |
|--------|--------|---------|
| `0x192d5` | `DR_SOCK_PATH` | Env var for socket path |
| `0x19ffc` | `DirectRendering: connected` | Successful connection |
| `0x18cc4` | `DirectRendering: Failed to connect` | **This is what we see** |
| `0x1a8e7` | `DirectRendering: Failed to connect to server: {}` | Connection error |
| `0x19005` | `DirectRendering: Failed to write image index: {}` | Present failure |
| `0x18c43` | `GameScope control enabled` | Control file loaded (appears after DR connects) |
| `0x18c89` | `vkQueueSignalReleaseImageANDROID failed: {}` | AHardwareBuffer release |
| `0x198d8` | `Failed to send AHardwareBuffer handle to socket` | Socket send error |
| `0x1ac23` | `DirectRendering: shutting down` | Clean shutdown |
| `0x1ac42` | `DirectRendering: Failed to send hardware buffer {}` | Buffer send error |
| `0x1baf8` | `DirectRendering: Failed to receive pipe fd` | FD recv error |
| `0x1bdd4` | `/Users/me/Documents/GameScopeVK/gamescope/direct_rendering_client.cpp` | Source path |

### GameHub's DirectRendering.java

```java
// GameHub creates Android Surfaces for the DR server
SurfaceControl build = new SurfaceControl.Builder()
    .setName("Direct Rendering Surface")
    .setOpaque(true)
    .build();
Surface gameSurface = new Surface(build);

// Cursor surface
SurfaceControl cursor = new SurfaceControl.Builder()
    .setName("Direct Rendering Cursor surface")
    .setFormat(1)
    .build();
Surface cursorSurface = new Surface(cursor);

// Initialize native DR server with the socket path
String sockPath = new File(rootDir, ".dr.sock").getPath();
DirectRendering.Companion.nativeInitialize(
    gameSurface, surfaceControl,
    cursorSurface, cursorSurface,
    sockPath, width, height
);

// Set DR_SOCK_PATH env var so GameScopeVK can find it
envController.set("DR_SOCK_PATH", sockPath);
```

### What Works (confirmed via logcat)

- ✅ `libGameScopeVK.so` loads successfully (2.1MB ARM64)
- ✅ Auto-detects `vulkan.adreno.so` via `ro.hardware.vulkan`
- ✅ `vkCreateInstance` succeeds (DXVK detected as engine)
- ✅ `vkCreateDevice` succeeds with all extensions:
  - `VK_KHR_external_memory_fd`, `VK_KHR_external_semaphore_fd`
  - `VK_KHR_external_fence_fd`, `VK_ANDROID_external_memory_android_hardware_buffer`
  - Full DXVK extension set (conservative raster, custom border color, etc.)
- ✅ `external memory type: 1` (AHardwareBuffer)
- ✅ `wellknown engine DXVK detected`

### What Fails

- ❌ DirectRendering socket connection (`DR_SOCK_PATH` → `.dr.sock` — no server listening)
- ❌ `GameScope control enabled` never appears
- ❌ `Swapchain size:` never appears
- ❌ Frame interpolation never starts
- ❌ Black screen

---

## GameScopeVK Control File (mmap)

Verified via ARM64 disassembly of `fcn.00195a78`:

| Offset | Type | Field | Disasm | GameHub Default |
|--------|------|-------|--------|-----------------|
| +0 | u16 | FPS limit | `ldrh w9, [x8]` | 0 (unlimited) |
| +2 | u8 | enable | `ldrb w9, [x8, 2]` | 1 |
| +4 | f32 | flow_scale | `ldr s9, [x8, 4]` | 0.60 |
| +8 | u8 | model | `ldrb w28, [x8, 8]` | 0 |
| +9 | u8 | multiplier | `ldrb w10, [x8, 9]` | 2 |

## SPIR-V Compute Shaders

54 embedded compute shaders in `.rodata`:
- Most: `local_size(16, 16, 1)` — image processing (flow estimation, warping, blending)
- Some: `local_size(2, 2, 1)` — reduction/scan operations
- All use `GLSL.std.450` extended instruction set
- Proprietary, no debug names

## Environment Variables

| Variable | Purpose | Value |
|----------|---------|-------|
| `VK_ICD_FILENAMES` | Override ICD | Path to generated GameScopeVK ICD JSON |
| `GAMESCOPE_DRIVER_PATH` | Real GPU driver to wrap | Path to `vulkan.adreno.so` (auto-detected if unset) |
| `GAMESCOPE_CONTROL_PATH` | Control mmap file | `<rootDir>/usr/tmp/gamescope.control` |
| `DR_SOCK_PATH` | DirectRendering socket | `<rootDir>/.dr.sock` |
| `GAMESCOPE_SURFACE_USING_BGRA` | Surface format hint | (optional) |

## ICD JSON (generated at runtime)

```json
{
  "file_format_version": "1.0.0",
  "ICD": {
    "library_path": "<rootDir>/usr/lib/libGameScopeVK.so",
    "api_version": "1.3.0"
  }
}
```

---

## Implementation Plan: DirectRendering Server

To fix the black screen, GameNative needs a DirectRendering server. Options:

### Option A: JNI DirectRendering Server (recommended)

Create a new native component that:
1. Creates Android `Surface` + `SurfaceControl`
2. Listens on `DR_SOCK_PATH` Unix socket
3. Receives AHardwareBuffer handles via `recvmsg()` + `SCM_RIGHTS`
4. Uses Vulkan or Canvas to blit AHardwareBuffer → Surface

Files needed:
- `GamescopeDirectRendering.kt` — manages Surface lifecycle
- `jni/direct_rendering_server.cpp` — socket server + blit logic
- Integration with `XServerScreen.kt` / `XEnvironment` to start/stop the DR server

### Option B: Patch libxserver.so

Add the DirectRendering socket server to GameNative's existing libxserver. Requires modifying native code in the xserver build.

### Option C: Use GameHub's libwinemu.so

Ship GameHub's `libxserver.so` alongside GameNative's, using it only when GameScopeVK is active. High risk of incompatibility.

### Option D: Socket Proxy

Create a thin socket server that:
1. Accepts GameScopeVK's DR connection
2. Receives AHardwareBuffer handles
3. Pushes them to GameNative's existing Surface via `Canvas` or Vulkan blit

This is essentially Option A but framed as a standalone component.

---

## Files Modified in GameNative

### New Files
- `app/src/main/assets/gamescope_vk/android_arm64_v8a/libGameScopeVK.so` (2.1MB)
- `app/src/main/assets/gamescope_vk/android_arm64_v8a/libxcb-dri3.so` (14KB)
- `app/src/main/assets/gamescope_vk/android_arm64_v8a/libxcb-present.so` (9.9KB)
- `app/src/main/java/app/gamenative/utils/GamescopeVkManager.kt`

### Modified Files
- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`
- `app/src/main/java/com/winlator/container/ContainerData.kt`
- `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`
- `app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt`

### Branch
`feat/gamescope-vk-experiment` on `/Users/kurt/Developer/GameNative`

---

## Logcat Evidence

### Successful Load (but no presentation)
```
V/gamescope: Loading Vulkan driver: vulkan.adreno.so
V/gamescope: Successfully loaded Vulkan module
D/gamescope: Using RGBA format for surface
V/gamescope: vkCreateInstance app_name: DreamsOfAether.exe, engine_name: DXVK, api_version: 1.3.0
V/gamescope: external memory type: 1
V/gamescope: vkCreateDevice guest enabled extensions: VK_KHR_external_memory_fd ...
V/gamescope: vkCreateDevice host enabled extensions: VK_ANDROID_external_memory_android_hardware_buffer ...
D/gamescope: wellknown engine DXVK detected
```
Then silence — no `GameScope control enabled`, no swapchain, no frame interpolation.

### Env Vars Set by BionicProgramLauncherComponent
```
VK_ICD_FILENAMES=<rootDir>/usr/lib/gamescope_vk_icd.json
GAMESCOPE_CONTROL_PATH=<rootDir>/usr/tmp/gamescope.control
DR_SOCK_PATH=<rootDir>/.dr.sock
```

---

## Comparison: lsfg-vk vs GameScopeVK

| Aspect | lsfg-vk | GameScopeVK |
|--------|---------|-------------|
| Type | Vulkan Layer | Vulkan ICD wrapper |
| Install mechanism | `VK_LAYER_PATH` | `VK_ICD_FILENAMES` |
| Frame gen | Lossless Scaling algorithm (DXBC-extracted) | Proprietary SPIR-V compute shaders |
| Presentation | Pass-through (uses host swapchain) | DirectRendering socket (custom protocol) |
| Config | `LSFG_CONFIG` env var | mmap control file |
| Dependencies | None beyond Vulkan | `libxcb-dri3.so`, `libxcb-present.so`, DR server |
| Android Surface | Not needed | Required (via DR server) |
| Conflicts with | GameScopeVK (ICD vs layer) | lsfg-vk (ICD vs layer) |

---

*Last updated: 2026-05-07*
