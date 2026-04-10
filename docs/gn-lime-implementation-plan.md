# GN-Lime Implementation Plan

**Goal**: Create a side-by-side installable GameNative fork (`app.gnlime`) that can coexist with stock GameNative on the same device.

**Target package ID**: `app.gnlime` (10 chars, 4 shorter than `app.gamenative` — fits in all binary slots)

## Status: ALL UNKNOWNs RESOLVED ✅

Every binary layer has been verified. No remaining gaps.

---

## Phase 1: Asset Binary Patching (automated)

**Tool**: `tools/lime-asset-patcher/patch_assets.py`

```bash
python3 tools/lime-asset-patcher/patch_assets.py --dry-run --verbose  # preview
python3 tools/lime-asset-patcher/patch_assets.py                       # apply
```

### What gets patched (17 replacements total)

| Asset file | What changes | Count |
|---|---|---|
| `box86_64/box64-0.3.4.tzst` | PT_INTERP `app.gamenative` → `app.gnlime` | 1 |
| `box86_64/box64-0.3.6.tzst` | same | 1 |
| `box86_64/box64-0.3.8.tzst` | same | 1 |
| `redirect.tzst` → `libredirect.so` | All `app.gamenative` paths + LD_PRELOAD + preload_loaded.txt | 7 |
| `redirect.tzst` → `libredirect-bionic.so` | `new_pkg` string: `app.gamenative` → `app.gnlime` | 1 |
| `graphics_driver/vortek-2.0.tzst` | ELF + ICD JSON | 2 |
| `graphics_driver/vortek-2.1.tzst` | ELF + ICD JSON | 2 |
| `graphics_driver/turnip-25.2.0.tzst` | ICD JSON | 1 |
| `graphics_driver/turnip-25.3.0.tzst` | ICD JSON | 1 |

Method: extract each `.tzst` → binary replace ELFs (null-padded to same length) → text replace JSONs → repack. Originals backed up as `*.orig`.

---

## Phase 2: Source Code Changes (manual)

### 2a. Build configuration

**`app/build.gradle.kts`**:
```kotlin
namespace = "app.gamenative"           // KEEP — Java/Kotlin source package stays the same
applicationId = "app.gnlime"            // CHANGE — this is the Android package name
```

Note: `namespace` and `applicationId` can differ. The Java/Kotlin source files remain under `app.gamenative.*` — only the installed app identity changes.

### 2b. C/C++ native code

**`app/src/main/cpp/extras/evshim.c`** (line ~174):
```c
// Before:
"/data/data/app.gamenative/files/imagefs/tmp/gamepad%s.mem",
// After:
"/data/data/app.gnlime/files/imagefs/tmp/gamepad%s.mem",
```

Alternatively, adopt sockmonkey72's `EVSHIM_DATA_DIR` env var approach from PR #585 — cleaner for future changes. In that case, `BionicProgramLauncherComponent.java` sets the env var and evshim.c reads it at runtime.

### 2c. AndroidManifest.xml

**`app/src/main/AndroidManifest.xml`**:
```xml
<!-- Before -->
<action android:name="app.gamenative.LAUNCH_GAME"/>
<!-- After -->
<action android:name="app.gnlime.LAUNCH_GAME"/>
```

### 2d. Java/Kotlin path constants

Adopt `BuildConfig.APPLICATION_ID` pattern from PR #585. Files that need changes:

| File | Current hardcoded path | Fix |
|---|---|---|
| `WinHandler.java` L523,533 | `/data/data/app.gamenative/files/imagefs/tmp/gamepad*.mem` | Use `BuildConfig.APPLICATION_ID` |
| `BionicProgramLauncherComponent.java` L189,192 | same gamepad.mem paths | Use `BuildConfig.APPLICATION_ID` |
| `Container.java` L49-53 | MEDIACONV_* env vars with `app.gamenative` | Use `BuildConfig.APPLICATION_ID` |
| `WineUtils.java` L46,58,72 | E: drive path, storage check | Use `BuildConfig.APPLICATION_ID` |
| `DXVKHelper.java` L20 | `DXVK_STATE_CACHE_PATH` | Use `imageFs.getRootDir()` |
| `IntentLaunchManager.kt` | intent action `app.gamenative.*` | Use `BuildConfig.APPLICATION_ID` |
| `ShortcutUtils.kt` | shortcut URI with `app.gamenative` | Use `BuildConfig.APPLICATION_ID` |
| `IconSwitcher.kt` | icon URI | Use `BuildConfig.APPLICATION_ID` |
| `GOGService.kt` | service URI | Check and update |
| `EpicService.kt` | service URI | Check and update |
| `AmazonService.kt` | service URI | Check and update |

### 2e. Optional: GN-Lime branding

- App name in `strings.xml`
- Launcher icon
- Theme colors

---

## Phase 3: Runtime layers that DON'T need patching

These are downloaded at runtime and work via the redirect shim:

### GLIBC mode
```
wine binaries (in imagefs_gamenative.txz)
  ├── wineserver:     com.winlator/files/rootfs → caught by libredirect.so ✓
  ├── ntdll.so:       com.winlator/files/rootfs → caught by libredirect.so ✓
  ├── esync sockets:  /wine-%lx-esync (relative, no package) ✓
  └── ld-linux/libc:  app.gamenative defaults (fallback only, no ld.so.cache) ✓
```

The patched `libredirect.so` (from our Phase 1 `redirect.tzst` patch) intercepts `openat`/`fstatat` syscalls and rewrites `com.winlator/files/rootfs` → `app.gnlime/files/imagefs`. This covers all GLIBC wine path lookups.

### Bionic mode
```
Proton ARM64EC (downloaded proton-9.0-arm64ec.txz)
  ├── wineserver:     com.winlator.cmod → caught by libredirect-bionic.so ✓
  ├── ntdll.so:       com.winlator.cmod → caught by libredirect-bionic.so ✓
  ├── esync sockets:  /wine-%lx-esync (relative, no package) ✓
  └── wine binary:    com.winlator.cmod → caught by libredirect-bionic.so ✓

Proton x86_64 (downloaded proton-9.0-x86_64.txz)
  └── Uses com.termux paths → NOT our package, no redirect needed ✓

Bionic imagefs (downloaded imagefs_bionic.txz)
  ├── aserver:        com.winlator → caught by libredirect-bionic.so ✓
  ├── cacaserver:     com.winlator → caught by libredirect-bionic.so ✓
  └── No app.gamenative references ✓
```

The patched `libredirect-bionic.so` (from our Phase 1 `redirect.tzst` patch) has `new_pkg = app.gnlime` and intercepts `openat`/`fstatat`/`ioctl` syscalls, rewriting any path containing `com.winlator.cmod` → `app.gnlime`.

### Redirect architecture (post-patching)

```
┌──────────────────────────────────────────────────────────┐
│ Android layer (bionic)                                   │
│                                                          │
│  Bionic wine/box64 executables                           │
│  have com.winlator.cmod hardcoded                        │
│       ↓ LD_PRELOAD                                       │
│  libredirect-bionic.so                                   │
│    old_pkg = com.winlator.cmod  (unchanged)              │
│    new_pkg = app.gnlime          (patched by us)         │
│  → rewrites com.winlator.cmod → app.gnlime at runtime   │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│ GLIBC layer (inside imagefs, via box64 on ARM)           │
│                                                          │
│  GLIBC wineserver, ntdll, wine binaries                  │
│  have com.winlator/files/rootfs hardcoded                │
│       ↓ LD_PRELOAD                                       │
│  libredirect.so                                          │
│    catches: com.winlator/files/rootfs                    │
│    rewrites to: app.gnlime/files/imagefs  (patched)     │
│    preload_loaded.txt sentinel check                     │
│    loads libpluviagoldberg.so via LD_PRELOAD             │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│ GLIBC box64                                              │
│  PT_INTERP: /data/data/app.gnlime/files/imagefs/         │
│             usr/lib/ld-linux-aarch64.so.1 (patched)      │
└──────────────────────────────────────────────────────────┘
```

---

## Phase 4: Build & Test

### Build
```bash
# Apply asset patches
python3 tools/lime-asset-patcher/patch_assets.py

# Apply source changes (manual edits above)

# Build debug APK
./gradlew assembleDebug

# Install alongside stock GameNative
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Test checklist

- [ ] GN-Lime APK installs alongside stock GameNative
- [ ] App launches, downloads imagefs
- [ ] Bionic container: create, launch a game
- [ ] Bionic container: gamepad input works (evshim gamepad.mem)
- [ ] Bionic container: touch controls work
- [ ] Bionic container: audio works (aserver redirect)
- [ ] Bionic container: Vulkan rendering (vortek/turnip ICD paths)
- [ ] GLIBC container: create, launch a game (if supported)
- [ ] GLIBC container: box64 loads (PT_INTERP correct)
- [ ] GLIBC container: wine starts (libredirect.so catches com.winlator paths)
- [ ] Both containers: keyboard/mouse input
- [ ] Both containers: save/load works
- [ ] Both containers: Steam connectivity

### Known acceptable risks

1. **GLIBC ld-linux/libc compiled-in defaults**: These have `app.gamenative` as fallback paths for gconv/locale. If a game needs non-UTF-8 encoding conversion and the explicit path isn't set, this could fail. Extremely unlikely for game workloads.

2. **Bionic imagefs aserver RPATH**: `aserver` has `com.winlator` in its RPATH. The bionic redirect catches `openat` calls but not `RPATH` resolution by the linker itself. If the linker resolves libraries before `LD_PRELOAD` kicks in, aserver might fail to find its libs. In practice, `LD_LIBRARY_PATH` is typically set explicitly by the launcher component, making RPATH irrelevant.

3. **wine-custom source vs compiled binary divergence**: The wine-custom source has `app.gamenative` but the compiled GLIBC wineserver has `com.winlator`. If the wine is ever rebuilt from the current wine-custom source without modification, it would have `app.gamenative` paths that libredirect.so does NOT catch. Any future wine rebuild must either use the correct prefix or be binary-patched.

---

## Reference: Provenance of findings

| Finding | Source | How verified |
|---|---|---|
| All APK assets patchable | Binary scan of every tzst in git history | `patch_assets.py --dry-run --verbose` |
| GLIBC wineserver uses com.winlator | Downloaded `imagefs_gamenative.txz`, extracted wineserver | `strings` scan, 0 app.gamenative hits |
| GLIBC ntdll uses com.winlator | Same download, extracted ntdll.so | `strings` scan |
| GLIBC esync sockets are relative | ntdll.so strings: `/wine-%lx-esync` (no prefix) | String scan |
| Proton ARM64EC uses com.winlator.cmod | Downloaded `proton-9.0-arm64ec.txz` | `strings` scan of wineserver + all .so |
| Proton x86_64 uses com.termux | Downloaded `proton-9.0-x86_64.txz` | `strings` scan |
| Bionic imagefs has no app.gamenative | Downloaded `imagefs_bionic.txz`, full string scan | `strings | grep` across entire archive |
| libredirect.so internals mapped | Binary analysis of `redirect.tzst` extraction | Offset-by-offset comparison with VibeNative |
| libredirect-bionic.so has old_pkg/new_pkg | Binary analysis of redirect.tzst | String scan + symbol table |
| PR #585 mapped same source files | Discord + GitHub API | Full message history, PR review |
| VibeNative binary patching works | Cloned `vibenative-imagefs`, binary diff | 7 three-byte changes confirmed |
| ld-linux/libc defaults are fallbacks | Checked for ld.so.cache (absent), traced resolution chain | find + path analysis |
