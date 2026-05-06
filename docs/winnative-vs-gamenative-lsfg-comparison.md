# WinNative vs GameNative: LSFG Integration Comparison

**Date:** 2026-05-06  
**Sources:**  
- WinNative PR #8 (`DevElderLost/WinNative-test`, branch `Lsfg-Intergration-from-GameNative`)  
- GameNative `LsfgVkManager.kt` + `LsfgQuickMenuHelper.kt` + `BionicProgramLauncherComponent.java`  
- Same `lsfg-vk-android` submodule (GameNative org), WinNative at `98afeaaf`, GameNative at `86234ee8` (4 commits ahead — adds FP16 shader support + CI workflow)

---

## Executive Summary

WinNative's LSFG integration **works on devices where GameNative's doesn't** (confirmed by sbo73 on Fold 4, .luisgaming on 8 Elite). The core difference is NOT in the Vulkan layer C++ code (it's the same submodule). The difference is in the **app-side Kotlin/Java integration** — how the layer gets installed, configured, and launched.

WinNative solves several problems that GameNative still has:

1. **Per-game shortcut config** (not global container config)
2. **Manual DLL import** (not Steam-only auto-discovery)
3. **User-selectable present mode** (not hardcoded FIFO)
4. **Proper `DISABLE_LSFG` management** (set/unset based on actual enabled state)
5. **Dynamic API version in manifest** (not hardcoded 1.4.313)
6. **`LSFG_LAST_PATH` override** (fixes /tmp write path for containers)
7. **Null-safety fixes** in input controls (prevents crashes that may correlate with FG use)

---

## Detailed Comparison

### 1. Configuration Architecture

| Aspect | GameNative | WinNative |
|--------|-----------|-----------|
| Config scope | **Container-level** (all games share one config) | **Per-game shortcut** (each .exe can differ) |
| Config storage | `Container.putExtra()` | `Shortcut.putExtra()` |
| UI location | Container Settings → Graphics tab | Game Settings → dedicated LSFG tab |
| Multiplier options | 2x, 3x, 4x (global) | 2x, 3x, 4x (per game) |
| Flow scale | Container extra | Per-game shortcut extra |
| HDR mode | Hardcoded `false` | User-configurable checkbox |
| Present mode | **Hardcoded `fifo`** | **User-selectable**: FIFO / Mailbox / Immediate |

**Impact:** GameNative's container-level config means you can't have FG enabled for one game and disabled for another in the same container. WinNative's per-game approach avoids this.

### 2. Lossless.dll Source

| Aspect | GameNative | WinNative |
|--------|-----------|-----------|
| DLL source | **Auto-discovered from Steam** (`SteamService.getAppDirPath(993090)`) | **Manually imported** by user via file picker |
| DLL location in container | `~/.local/share/lsfg-vk/Lossless.dll` | User-chosen path (stored in shortcut extra) |
| Failure if DLL missing | Silently fails — `isArmed()` returns false | Shows toast "Select a .dll file" |
| No Steam installed | **LSFG completely unusable** | Works fine — import from any source |

**Impact:** GameNative requires Steam installed + Lossless Scaling purchased. Many sideload users don't have Steam. WinNative lets users import from anywhere.

### 3. Environment Variable Management

| Env Var | GameNative | WinNative |
|---------|-----------|-----------|
| `DISABLE_LSFG` | Only removed (never set) when armed | **Explicitly set "1"** when disabled, **removed** when enabled |
| `LSFG_PROCESS` | `"gamenative-lsfg"` (constant) | Actual .exe filename (e.g., `eldenring.exe`) |
| `LSFG_PROCESS_EXE` | Not set | Actual .exe filename |
| `LSFG_LAST_PATH` | Not set (defaults to `/tmp/lsfg-vk_last`) | **Container-specific path** |
| `LSFG_TMP_DIR` | Not set | `~/.local/share/lsfg-vk/` |
| `LSFG_MULTIPLIER` | Not set (only in conf.toml) | Set as env var |
| `LSFG_FLOW_SCALE` | Not set | Set as env var |
| `LSFG_PERFORMANCE_MODE` | Not set | "1"/"0" |
| `LSFG_HDR_MODE` | Not set | "1"/"0" |
| `LSFG_EXPERIMENTAL_PRESENT_MODE` | Not set | "fifo"/"mailbox"/"immediate" |
| `LSFG_DLL_PATH` | Not set | DLL absolute path |
| `LSFG_DLL_PATH_UNIX` | Not set | DLL path (Unix-style) |
| `LSFG_LEGACY` | Not set | "1" |

#### `DISABLE_LSFG` — The Critical Difference

In GameNative, `DISABLE_LSFG` is only ever **removed** when LSFG is armed. When disabled, the manifest is deleted but the env var is NOT set. If the Vulkan loader cached the layer list, deleting the manifest may not take effect.

WinNative **explicitly sets `DISABLE_LSFG=1`** when disabled. This is the correct use of the Vulkan implicit layer `disable_environment` field — the loader checks this env var at instance creation and skips the layer entirely.

#### `LSFG_LAST_PATH` — The /tmp Problem

The layer writes status to `/tmp/lsfg-vk_last` by default. In containers, `/tmp` may not be writable or may conflict. WinNative overrides with `LSFG_LAST_PATH`. GameNative doesn't set this.

### 4. Layer Manifest — THE SMOKING GUN

| Aspect | GameNative | WinNative |
|--------|-----------|-----------|
| Source | Copied from assets (static JSON) | **Dynamically generated** at launch time |
| `api_version` | **Hardcoded `"1.4.313"`** | **Resolved from `graphicsDriverConfig.get("vulkanVersion")`** with fallback to "1.3.0" |
| `library_path` | Patched at copy time | Hardcoded relative path |

**This is likely the single biggest reason WinNative works and GameNative doesn't.**

The Vulkan loader uses `api_version` to determine if a layer is compatible with the instance being created. If the instance's API version is lower than the layer's `api_version`, the loader **silently skips the layer**. `1.4.313` is Vulkan 1.4 — extremely new. Most Android Vulkan implementations are 1.3.x. The Turnip driver for Adreno 7xx/8xx advertises Vulkan 1.3 at most.

**GameNative's manifest tells the Vulkan loader "this layer requires Vulkan 1.4.313" — and devices that only support Vulkan 1.3 will silently reject it.** WinNative's dynamic approach generates a manifest with the correct version for the device.

### 5. CMakeLists.txt Patch

WinNative patches `main.cpp` at build time to respect `LSFG_LAST_PATH`:

```cmake
string(REPLACE
    "std::ofstream latest(\"/tmp/lsfg-vk_last\", std::ios::trunc);"
    "const char* latestPathEnv = std::getenv(\"LSFG_LAST_PATH\");\n"
    " const std::string latestPath = ...\n"
    " std::ofstream latest(latestPath, std::ios::trunc);"
    LSFGVK_MAIN_CONTENT "${LSFGVK_MAIN_CONTENT}")
```

GameNative doesn't have this patch, so the layer always writes to `/tmp/lsfg-vk_last`.

### 6. Null Safety Fixes (InputControlsView)

WinNative includes null-safety fixes:

```java
// Before: winHandler.mouseEvent(...)
// After:  if (winHandler != null) winHandler.mouseEvent(...)

// Stick position fix:
if (selectedElement.getType() == ControlElement.Type.STICK) {
    selectedElement.setCurrentPosition(bb.centerX(), bb.centerY());
}
```

Not LSFG-specific but prevents crashes during FG-induced timing issues.

### 7. Per-Game vs Global Process Matching

GameNative: `exe = "gamenative-lsfg"` (constant) — all games match one config entry.  
WinNative: `exe = "eldenring.exe"` (actual .exe) — enables per-game profiles.

---

## Issues in GameNative That WinNative Solves

### Issue 1 (CRITICAL): `api_version: 1.4.313` Causes Layer Rejection

GameNative's asset manifest hardcodes `api_version: 1.4.313`. The Vulkan loader silently skips layers whose `api_version` exceeds the instance version. Most Adreno devices are Vulkan 1.3. **This alone could explain the "layer loads but does nothing" reports across all Adreno devices.**

**Fix:** Dynamic manifest generation (like WinNative), or at minimum lower to `1.3.0`.

### Issue 2 (HIGH): Missing `DISABLE_LSFG=1` When Disabled

GameNative only deletes the manifest when disabled, but doesn't set the disable env var. The Vulkan loader may still load a cached layer.

**Fix:** Set `DISABLE_LSFG=1` in env vars when disabled.

### Issue 3 (HIGH): Hardcoded `/tmp/lsfg-vk_last` Path

Status file may not be writable in container. Prevents error visibility.

**Fix:** Apply CMake string-replace patch for `LSFG_LAST_PATH`, or patch `main.cpp` directly.

### Issue 4 (MEDIUM): No Present Mode Choice

Hardcoded FIFO caps users to display refresh rate. 60Hz + 2x FG = 60fps (same as native).

**Fix:** Add present mode selection (FIFO/Mailbox/Immediate).

### Issue 5 (MEDIUM): Steam-Only DLL Source

LSFG unusable without Steam installed on device.

**Fix:** Add manual DLL import alongside Steam auto-discovery.

### Issue 6 (MEDIUM): Global Config (Not Per-Game)

All games share same FG settings. Can't enable FG for one game and disable for another.

**Fix:** Move LSFG config to per-shortcut extras.

### Issue 7 (LOW): No HDR Option

Hardcoded `hdr_mode = false`. WinNative exposes it.

**Fix:** Add HDR toggle (low priority for Android).

---

## What GameNative Does Better

1. **Cleaner architecture** — `LsfgVkManager` is well-structured with clear separation
2. **Hot-reload support** — `LsfgQuickMenuHelper` enables runtime FG toggle
3. **Steam integration** — Auto DLL discovery is better UX (when Steam available)
4. **Version-cached installs** — File-based version check avoids redundant copies
5. **4 commits ahead in submodule** — FP16 shader support included

---

## Action Items for GameNative

| Priority | Fix | Effort | Impact |
|----------|-----|--------|--------|
| **P0** | Lower manifest `api_version` to `1.3.0` (or make dynamic) | Low | Could immediately fix "layer not loading" on most Adreno devices |
| **P0** | Set `DISABLE_LSFG=1` when LSFG is disabled | Low | Prevents stale layer loading |
| **P1** | Patch `main.cpp` to use `LSFG_LAST_PATH` env var | Low | Fixes status file write failure in containers |
| **P1** | Add manual DLL import option | Medium | Unblocks users without Steam |
| **P2** | Add present mode selection (FIFO/Mailbox/Immediate) | Low | Enables 60Hz display users to benefit from FG |
| **P2** | Move LSFG config to per-shortcut extras | Medium | Enables per-game FG profiles |
| **P3** | Add HDR mode toggle | Low | Future-proofing |
| **P3** | Null safety fixes in InputControlsView | Low | Prevents FG-correlated crashes |

---

## The Theory: Why WinNative Works and GameNative Doesn't

The most likely explanation for WinNative working where GameNative fails is **the `api_version` in the layer manifest**:

1. GameNative's manifest says `api_version: 1.4.313`
2. The Vulkan loader on Adreno 7xx/8xx creates a VkInstance with `apiVersion = 1.3.x`
3. The loader sees the layer requires 1.4.313 > 1.3.x → **silently skips it**
4. The game runs without the layer, gets normal FPS
5. Users see "LSFG enabled" in the UI but nothing changes in-game
6. FPS counter may double because other factors (e.g., swapchain image count changes from the layer's `vkCreateSwapchainKHR` hook not being called)

Wait — if the layer is skipped entirely, it wouldn't modify the swapchain either. So FPS counter doubling with no visual change must mean the layer IS loaded but framegen fails. This suggests the `api_version` might NOT be the sole issue, or there's a second path where the layer loads but the internal framegen device creation fails.

**Revised theory:** Two failure modes:
1. **Layer completely skipped** (manifest `api_version` too high) → normal FPS, no doubling
2. **Layer loads, hooks succeed, framegen device creation fails** (missing Vulkan 1.3 features/extensions on Adreno) → FPS counter doubles (hooks modify swapchain) but no generated frames

The Discord reports describe mode 2 more often ("FPS counter doubles but nothing changes"), which suggests the manifest `api_version` may actually be accepted on most devices (maybe the Vulkan loader on Android is more lenient, or the Wine/Vortek Vulkan instance requests 1.4). In that case, the internal device creation failure (from the earlier source audit) is the primary culprit, and WinNative works because of **different driver configurations** (system drivers vs Turnip wrapper), not the manifest version.

**To confirm:** Need to check what Vulkan API version the Wine/Vortek container actually creates its instance with. If it's 1.4+, the manifest version is fine and the issue is purely the framegen internal device extensions. If it's 1.3, the manifest is the blocker.

**Either way, both fixes should be applied:** lower the manifest `api_version` AND add extension/feature pre-checking before framegen initialization.
