# LSFG-VK in GameNative: Technical Analysis Journal

**Date:** 2026-05-06  
**Author:** Discord + source audit  
**Scope:** lsfg-vk-android submodule, GameNative Kotlin integration, Discord user reports

---

## 1. Architecture Overview

### How LSFG-VK Works in GameNative

The integration follows this flow:

```
GameNative App (Kotlin/Java)
    │
    ├─ LsfgVkManager.kt ─────────── installs layer + config at container startup
    │   ├─ ensureRuntimeInstalled() ─ copies liblsfg-vk-layer.so + manifest into container
    │   ├─ writeConfig() ──────────── writes conf.toml with DLL path, multiplier, flow_scale
    │   └─ applyLaunchEnv() ────────── sets LSFG_CONFIG, LSFG_PROCESS, VK_LAYER_PATH env vars
    │
    ├─ BionicProgramLauncherComponent ─ calls LsfgVkManager during execGuestProgram()
    │
    └─ LsfgQuickMenuHelper.kt ──────── hot-reloads conf.toml at runtime (toggle on/off)
        └─ updateConfigAtRuntime() ──── rewrites conf.toml → layer detects timestamp change
                                       → returns VK_ERROR_OUT_OF_DATE_KHR
                                       → forces swapchain recreation with new settings
```

### The Vulkan Layer Pipeline (C++ side)

```
Game (Wine/Proton) → Vulkan calls → lsfg-vk layer (hooks) → Real GPU driver

Layer hooks:
  vkCreateInstance ────── adds instance extensions (KHR_external_memory_capabilities, etc.)
  vkCreateDevice (Pre) ── adds device extensions (KHR_external_memory, KHR_external_semaphore, etc.)
  vkCreateDevice (Post) ─ stores DeviceInfo (device, physicalDevice, queue pair)
  vkCreateSwapchainKHR ── increases minImageCount, adds TRANSFER_DST|SRC usage,
                           enforces present mode, creates LsContext
  vkQueuePresentKHR ────── THE KEY HOOK: intercepts present, runs framegen, presents N+1 frames
  vkDestroySwapchainKHR ── tears down LsContext
```

### Android vs Desktop: Two Completely Different Paths

| Aspect | Desktop Linux | Android (GameNative) |
|--------|--------------|----------------------|
| Image sharing | OPAQUE_FD (`vkGetMemoryFdKHR`) | AHardwareBuffer (`VK_ANDROID_external_memory_android_hardware_buffer`) |
| Semaphore sync | FD-based (`vkGetSemaphoreFdKHR`) | **None** — uses `vkDeviceWaitIdle()` instead |
| Framegen device extensions | `VK_KHR_external_memory_fd` + `VK_KHR_external_semaphore_fd` | `VK_ANDROID_external_memory_android_hardware_buffer` + KHR dependencies |
| Present flow | Async: submit copy → semaphore FD → framegen → acquire → copy → present | Sync: submit copy → **waitIdle** → framegen(-1, {}) → **waitIdle** → acquire → copy → present |
| Performance | Non-blocking pipeline | **Two full device-wide stalls per frame** |

---

## 2. Critical Findings from Source Audit

### Finding 1: The "Silent No-Op" Problem — Device Extension Mismatch

**Location:** `framegen/src/core/device.cpp` (the framegen-internal device creation)

The framegen library creates **its own separate VkDevice** on **its own separate VkInstance**. This device requires:

**Android required extensions:**
- `VK_ANDROID_external_memory_android_hardware_buffer`
- `VK_KHR_external_memory`
- `VK_KHR_sampler_ycbcr_conversion`
- `VK_KHR_dedicated_allocation`
- `VK_KHR_get_memory_requirements2`
- `VK_KHR_bind_memory2`
- `VK_KHR_maintenance1`

Plus **optionally** `VK_EXT_robustness2` (mandatory in the code logic, but guarded — if missing, a fallback descriptor image is created instead).

**Also requires Vulkan 1.3 features:**
- `synchronization2 = VK_TRUE`
- `timelineSemaphore = VK_TRUE`
- `vulkanMemoryModel = VK_TRUE`

**Key issue:** If ANY of the required extensions or Vulkan 1.3 features are missing on the Adreno driver, the `Device` constructor throws `LSFG::vulkan_error`. But this exception happens **inside the framegen library's own `initialize()` call**, which is called from `context.cpp` in the **layer's swapchain creation path**.

Looking at `context.cpp` (the layer's `LsContext` constructor):
```cpp
setenv("DISABLE_LSFG", "1", 1); // NOLINT
lsfgInitialize(
    Utils::getDeviceUUID(info.physicalDevice),
    ...
);
```

If `lsfgInitialize` throws, the exception propagates to `myvkCreateSwapchainKHR` in `hooks.cpp`, which catches it and:
```cpp
catch (const std::exception& e) {
    Utils::logLimitN("swapCtxCreate", 5, "An error occurred while creating the swapchain wrapper:\n"
        "- " + std::string(e.what()));
    return VK_SUCCESS; // swapchain is still valid
}
```

**The swapchain is created successfully but WITHOUT the LSFG context.** The layer is loaded, the hooks are active, but `swapchains.find()` in `myvkQueuePresentKHR` will miss this swapchain, and it falls through to `Layer::ovkQueuePresentKHR` — a plain passthrough.

This is **exactly** the "FPS counter doubles but nothing actually changes" pattern users report. The layer IS loaded (Vulkan loader reports it), but the framegen context never gets created because the internal device creation fails due to missing extensions/features on the Adreno driver.

### Finding 2: Vulkan 1.3 Requirement on Adreno

The framegen library's `Instance::Instance()` creates a Vulkan 1.3 instance:
```cpp
.apiVersion = VK_API_VERSION_1_3
```

And the device creation requires Vulkan 1.3 features (`synchronization2`).

**Adreno 7xx (SD 8 Gen 1/2/3):** Turnip drivers advertise Vulkan 1.3 in recent versions, but the actual feature conformance is incomplete. `synchronization2` and `vulkanMemoryModel` may not be fully supported even if advertised.

**Adreno 830 (SD 8 Elite):** Per Discord user `psycho_ch`: "No Mesa dev has an A830. Only A840. Meaning there are much more issues and performance regressions on A830." The A830's Turnip support is immature.

**Xclipse 940 (Exynos 2400):** Uses Samsung's Xclipse driver, which is a completely different ICD from Turnip. Extension availability is unknown/different.

### Finding 3: The `vkGetAndroidHardwareBufferPropertiesANDROID` Skipped

In `layer.cpp`, during device function pointer initialization:
```cpp
#ifdef __ANDROID__
// AHB function is optional — not all ICDs (e.g. Vortek wrapper) support it.
// If unavailable, the AHB image path will fail at point-of-use, but
// the layer still initializes so it can fall back gracefully.
initDeviceFunc(*pDevice, "vkGetAndroidHardwareBufferPropertiesANDROID", 
    &next_vkGetAndroidHardwareBufferPropertiesANDROID);
#endif
```

The comment admits the Vortek wrapper ICD doesn't support AHB properties. But the AHB image path in `mini/image.cpp` actually **skips** this function entirely:

```cpp
// NOTE: We skip vkGetAndroidHardwareBufferPropertiesANDROID because
// the Vortek ICD wrapper doesn't pass it through. Instead we use
// vkGetImageMemoryRequirements after image creation to get the
// allocation size and memory type bits.
```

This is a workaround for the Vortek wrapper but it may cause incorrect memory allocation on non-Vortek drivers (real Turnip, Xclipse) that DO support the function.

### Finding 4: The Synchronous Stall Problem (Performance)

The Android present path in `context.cpp` does **two full `vkDeviceWaitIdle()` calls per frame**:

```cpp
// Step 1: Copy swapchain image to frame_0/frame_1
pass.preCopyBuf.submit(...);

// Step 2: STALL #1 — Wait for copy to finish
Layer::ovkQueueSubmit(info.queue.second, 0, nullptr, VK_NULL_HANDLE);

// Step 3: Run framegen (no semaphore sync, synchronous)
LSFG_3_1::presentContext(*this->lsfgCtxId, -1, noOutSems);

// Step 4: STALL #2 — Wait for framegen's GPU work
LSFG_3_1::waitIdle();

// Step 5: Copy generated frames to swapchain and present
for (i = 0; i < multiplier - 1; i++) { ... acquire + copy + present ... }
```

On desktop Linux, this is fully pipelined with FD-based semaphore chains. On Android, it's completely synchronous. This explains:

- **"Choppier fps with framegen on"** (pinkkink, Odin 3 Max) — the stalls kill any throughput benefit
- **"Audio cut out"** — the stalls may block the audio thread if it shares the same process
- **Works OK on Odin 2 Portal Pro** — simpler games at lower res may have enough GPU headroom that the stalls don't dominate

### Finding 5: Layer Not Fully Unloaded When "Disabled"

When the user toggles LSFG "off" in the quick menu, `LsfgQuickMenuHelper.applySettings()` calls `LsfgVkManager.updateConfigAtRuntime()` which writes `multiplier = 1` to conf.toml.

In the layer's `myvkQueuePresentKHR`:
```cpp
if (conf.multiplier <= 1) return Layer::ovkQueuePresentKHR(queue, pPresentInfo);
```

This passes through. BUT the layer is still loaded, still hooking all Vulkan calls, and still intercepting `vkCreateSwapchainKHR` (which adds extra images and forces `TRANSFER_DST|SRC` usage). The swapchain context creation may still attempt (and fail) on the next swapchain recreation.

This explains:
- **softwareslicer's** "artifacts even when disabled in overlay"
- **killerdwarf15's** "stuttering with frame reverts even when off"

The `disableLayerInContainer()` method (which deletes the manifest JSON) is only called at **startup** if `isArmed()` is false — NOT during runtime toggle-off. The hot-reload path only changes the multiplier, it doesn't unload the layer.

### Finding 6: CAS + LSFG Conflict

From Discord (`irwilove`): "activating CAS together with lsfg you create graphic glitches."

CAS (Contrast Adaptive Sharpening) in GameNative is likely implemented as another Vulkan post-processing step or as a DXVK shader override. Both CAS and LSFG operate on the swapchain images. The LSFG layer adds `VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT` to swapchain images during `vkCreateSwapchainKHR`. If CAS also modifies the swapchain presentation path, the two fight over image layout transitions.

### Finding 7: Present Mode Forced to FIFO

`LsfgVkManager.buildConfigToml()` hardcodes:
```kotlin
appendLine("experimental_present_mode = ${tomlString("fifo")}")
```

And `greenteen93` on Discord noted: "we can't change preset lsfg from mailbox to FIFO or something."

The layer enforces the present mode in `myvkCreateSwapchainKHR`:
```cpp
createInfo.presentMode = Config::activeConf.e_present;
```

FIFO (vsync) is correct for frame generation (you need consistent timing), but it also means the FPS is capped to the display refresh rate. Users with 60Hz displays will never see above 60fps even with 2x FG. This explains `eloah.`'s report: "FPS is capped at 60; I can't uncap it."

### Finding 8: `DISABLE_LSFG` Environment Variable Race

In `context.cpp`, during LSFG initialization:
```cpp
setenv("DISABLE_LSFG", "1", 1); // NOLINT
lsfgInitialize(...);
// ...
unsetenv("DISABLE_LSFG"); // NOLINT
```

The layer manifest declares:
```json
"disable_environment": { "DISABLE_LSFG": "1" }
```

This means setting `DISABLE_LSFG=1` tells the Vulkan loader to skip loading the layer. But the code sets it **while the layer is already loaded** and then unsets it. The intent is to prevent the layer from recursively intercepting the framegen library's own Vulkan calls (since framegen creates its own instance/device). This is a workaround for the lack of proper layer filtering in the Android Vulkan loader.

However, if `unsetenv("DISABLE_LSFG")` doesn't happen (e.g., an exception is thrown between setenv and unsetenv), the layer will be disabled for all subsequent Vulkan instances in the process.

---

## 3. Device-Specific Failure Analysis

### Adreno 730 (SD 8 Gen 1) — Galaxy Tab S8 Ultra
- Turnip Vulkan 1.3 support is partial
- `VK_KHR_sampler_ycbcr_conversion` may not be advertised (it's in the required list for AHB)
- `synchronization2` may not work correctly
- **Verdict:** Extension/feature mismatch → framegen device creation fails → silent passthrough

### Adreno 740 (SD 8 Gen 2) — Samsung S23/S24
- Turnip 26.x has better Vulkan 1.3 support
- But `vulkanMemoryModel` is known to be flaky on Turnip
- **Verdict:** Likely `vulkanMemoryModel` or `synchronization2` conformance issue

### Adreno 750 (SD 8 Gen 3) — Poco F7 Pro, ROG 9FE
- Similar to 740 but with Turnip Gen8 drivers
- **Verdict:** Same extension/feature issue

### Adreno 830 (SD 8 Elite) — Redmagic Astra, Xiaomi 15, S25 Ultra
- Immature Turnip support ("No Mesa dev has an A830, only A840")
- `TU_DEBUG=sysmem` is needed (gmem is broken on A830)
- Extension availability uncertain
- **Verdict:** Multiple driver-level issues + extension gaps

### Adreno 840 (SD 8 Elite Gen 5) — Samsung S26+, OnePlus Pad 4
- Slightly more mature than 830 but still new
- **Verdict:** Likely same as 830

### Xclipse 940 (Exynos 2400) — Samsung S24FE
- Completely different ICD (Samsung Xclipse, not Turnip)
- AHB extension support is unknown
- The layer's Vortek-wrapper workaround in mini/image.cpp may interact poorly
- **Verdict:** AHB import path may fail on Xclipse; extension mismatch likely

### Odin 2 Portal Pro — WORKING
- SD 8 Gen 2 (Adreno 740) but with system drivers
- `elquete_` reports it works with Rune Factory
- May have a different driver/extension profile than Samsung S23 (same SoC, different OEM driver)
- **Key question:** What driver version? What container config?

### Odin 3 — WORKING (with caveats)
- SD 8 Elite (Adreno 830) — same GPU class as Redmagic Astra where it fails
- `zurce` reports it works
- But `pinkkink` (Odin 3 Max) reports choppier fps and audio loss
- `.luisgaming` reports "Odin 2 It boosts only FPS counter for me" — contradictory
- **Verdict:** Borderline — may work for simple games but stalls dominate for heavier titles

---

## 4. Priority Fix Recommendations

### P0 — Make Failures Visible (Not Silent)

**Problem:** When framegen's internal device creation fails, the layer silently falls through to passthrough. Users see "LSFG loaded" but get no framegen.

**Fix:** In `context.cpp` `LsContext` constructor, catch the `lsfgInitialize` / `createContextFromAHB` exceptions and write a status file (e.g., `${TMPDIR}/lsfg-vk_status`) with the error. The Kotlin side (`LsfgVkManager` or `QuickMenu`) can read this file and display "LSFG failed: [reason]" in the UI.

Additionally, add a "FG Status" line to the FPS overlay: `60 → 120` (working) vs `60 | 60` (passthrough).

### P1 — Extension/Feature Pre-Check

**Problem:** The framegen library's `Device` constructor throws when required extensions are missing, but by then the game is already running and the swapchain is already created.

**Fix:** Before attempting `lsfgInitialize`, enumerate the available extensions on the physical device and check against the required list. If any are missing, skip framegen entirely and log the specific missing extension. This gives users actionable error messages.

For the Vulkan 1.3 features (`synchronization2`, `timelineSemaphore`, `vulkanMemoryModel`), probe `vkGetPhysicalDeviceFeatures2` and skip if unsupported.

### P2 — Reduce Synchronous Stalls

**Problem:** Two `vkDeviceWaitIdle()` calls per frame on Android kill performance.

**Possible approaches:**
1. **Fence-based synchronization** instead of device-wide idle waits. The framegen library already uses fences internally (`completionFences`).
2. **Timeline semaphores** shared between the layer's device and framegen's device (if both support `VK_KHR_external_semaphore` + FD or AHB-based semaphore import).
3. **Pipeline the framegen work** — don't wait for the copy to finish; instead use a semaphore chain: game renders → semaphore → copy → semaphore → framegen → semaphore → present.

This is the hardest fix but would turn the Android path from "worse than native" to "actually useful."

### P3 — Fix Layer Unload on Toggle-Off

**Problem:** Toggling LSFG "off" in the quick menu only sets `multiplier = 1` in conf.toml. The layer is still loaded, still modifying swapchain creation parameters, and still potentially causing artifacts.

**Fix:** When multiplier is set to 0/off:
1. Set `DISABLE_LSFG=1` in the environment (this tells the Vulkan loader to skip the layer on the next instance creation)
2. Return `VK_ERROR_OUT_OF_DATE_KHR` from `vkQueuePresentKHR` to force swapchain recreation with the layer fully disabled
3. On the Kotlin side, delete the manifest from `implicit_layer.d` (like `disableLayerInContainer()`) to ensure the Vulkan loader won't reload it
4. If the user toggles back on, re-install the manifest and return `VK_ERROR_OUT_OF_DATE_KHR` again

### P4 — CAS + LSFG Mutual Exclusion

**Problem:** Enabling CAS and LSFG simultaneously causes graphical glitches.

**Fix:** In the Kotlin `GraphicsTab` or `LsfgVkManager`, when LSFG is armed, force-disable CAS (and vice versa). Add a UI warning: "CAS is incompatible with Frame Generation."

### P5 — FPS Cap Pipeline Fix

**Problem:** GameNative's built-in FPS capper breaks framegen (per `zurce` on Odin 3).

**Fix:** The FPS capper should cap the **base** framerate (pre-FG), not the output. The current architecture has:
- `DXVK_FRAME_RATE` → pre-FG cap (correct)
- Quick menu FPS clamp → overall ceiling (can conflict)

When LSFG is active, the quick menu clamp should be disabled or set to `base_fps × multiplier` automatically. Document that users should cap FPS in-game or via DXVK, not through the GN capper.

### P6 — Present Mode Options

**Problem:** FIFO (vsync) is hardcoded, capping users to display refresh rate. On 60Hz devices, 2x FG from 30fps = 60fps (no visible benefit over native 60).

**Fix:** Allow users to choose between FIFO (vsync, smooth) and mailbox (no vsync, higher FPS counter but tearing possible). This should be an advanced option. On 120Hz+ devices, FIFO at 60fps × 2 = 120fps works perfectly.

---

## 5. Summary Table: What's Broken Where

| Symptom | Root Cause | Affected Devices | Fix Priority |
|---------|-----------|-----------------|-------------|
| FPS counter doubles, no actual change | Framegen internal device creation fails (missing ext/features), layer falls through to passthrough silently | All Adreno 7xx/8xx, Xclipse 940 | P0 + P1 |
| Artifacts/stuttering when FG "off" | Layer still loaded and hooking even at multiplier=1; swapchain params still modified | All devices | P3 |
| CAS + LSFG glitches | Two post-process layers fighting over swapchain images | Any device with CAS enabled | P4 |
| Choppier FPS with FG on | Two `vkDeviceWaitIdle()` stalls per frame in Android sync path | Odin 3 Max, any heavier game | P2 |
| Audio loss with FG | Stalls may block audio thread | Odin 3 Max | P2 |
| FPS capped at 60 | FIFO present mode + 60Hz display = hard cap | All 60Hz devices | P6 |
| GN capper breaks FG | Capper applied post-FG instead of pre-FG | Any device where FG works | P5 |
| "LSFG off" UI bug | Menu says off but FG is still running (elquete_ on Odin 2 Portal Pro) | Odin 2 Portal Pro | P3 (related) |
| Framegen not persisting in config | `4lf1903`: "enable the lsfg function, press save, re-enter config and option is disabled" | Unknown | Bug in container extra persistence |

---

## 6. The Critical Question for Targeted Fixes

**Why does it work on Odin 2 Portal Pro (SD 8 Gen 2 / Adreno 740) but NOT on Samsung S23 (same SoC / same GPU)?**

Possible explanations:
1. **Different OEM driver builds** — Samsung may ship a different Turnip/driver variant than AYN
2. **System driver vs Turnip wrapper** — `kharradi` reported "lsfg works better here (system drivers only)" — the Turnip wrapper may not expose all extensions that the system ICD does
3. **Container configuration** — Odin 2 may use a different container variant or driver version
4. **VK_LAYER_PATH resolution** — the layer discovery path depends on `VK_LAYER_PATH` env var which is set by `LsfgVkManager.applyLaunchEnv()`. If the container's filesystem layout differs, the Vulkan loader may not find the manifest

**Action item:** Compare the exact container config + driver version between a working Odin 2 Portal Pro setup and a failing Samsung S23 setup. The Vulkan extension enumeration output from both would immediately pinpoint the gap.