# GN-Lime Asset Patcher

Binary asset patcher for creating the `app.gnlime` side-by-side fork of GameNative.

## What it patches

| Asset | Files modified | Method |
|---|---|---|
| `box86_64/box64-0.3.4.tzst` | `usr/local/bin/box64` PT_INTERP | ELF binary |
| `box86_64/box64-0.3.6.tzst` | `usr/local/bin/box64` PT_INTERP | ELF binary |
| `box86_64/box64-0.3.8.tzst` | `usr/local/bin/box64` PT_INTERP | ELF binary |
| `redirect.tzst` | `usr/lib/libredirect.so` (7 hits), `usr/lib/libredirect-bionic.so` (1 hit) | ELF binary |
| `graphics_driver/vortek-2.0.tzst` | `usr/lib/libvulkan_vortek.so` + `vortek_icd.aarch64.json` | ELF + text |
| `graphics_driver/vortek-2.1.tzst` | `usr/lib/libvulkan_vortek.so` + `vortek_icd.aarch64.json` | ELF + text |
| `graphics_driver/turnip-25.2.0.tzst` | `freedreno_icd.aarch64.json` | text |
| `graphics_driver/turnip-25.3.0.tzst` | `freedreno_icd.aarch64.json` | text |

Any other `*.tzst` in `graphics_driver/` with `app.gamenative` hits is auto-detected and patched.

## How it works

`app.gnlime` (10 chars) is **shorter** than `app.gamenative` (14 chars) by 4 chars.
Every occurrence is patched in-place by replacing the old byte sequence with the new one
padded to the same length with NUL bytes (`\x00`). This is safe because:

- ELF C-string slots are NUL-terminated; extra NULs are ignored
- PT_INTERP slots are sized to hold the whole string; 4-byte slack is fine
- tzst files are extracted → patched → repacked (never patched in compressed form)
- No slot overflow is possible since new ≤ old in length

## Usage

```bash
# Dry run (show what would change)
python3 tools/lime-asset-patcher/patch_assets.py --dry-run --verbose

# Apply patches
python3 tools/lime-asset-patcher/patch_assets.py

# Custom IDs
python3 tools/lime-asset-patcher/patch_assets.py --old app.gamenative --new app.gnlime

# Custom assets directory
python3 tools/lime-asset-patcher/patch_assets.py --assets-dir /path/to/assets
```

Originals are backed up as `*.tzst.orig` on first run.

## What else needs changing (outside assets)

After running this patcher, the remaining source-level changes needed for GN-Lime:

### `app/build.gradle.kts`
```kotlin
applicationId = "app.gnlime"  // was: "app.gamenative"
```

### `app/src/main/cpp/extras/evshim.c`
Replace all `app.gamenative` occurrences with `app.gnlime`.

### `app/src/main/AndroidManifest.xml`
```xml
<action android:name="app.gnlime.LAUNCH_GAME"/>  <!-- was app.gamenative.LAUNCH_GAME -->
```

### Java / Kotlin source string constants
Files with runtime path hardcodes:
- `WinHandler.java` — `WinHandler.PACKAGE_NAME`
- `BionicProgramLauncherComponent.java` — imagefs path construction
- `GlibcProgramLauncherComponent.java` — imagefs path construction
- `WineUtils.java` — wine path helpers
- `DXVKHelper.java` — DXVK path helpers
- `Container.java` — container path constants
- `IntentLaunchManager.kt`, `ShortcutUtils.kt`, `IconSwitcher.kt` — intent actions / URIs
- `GOGService.kt`, `EpicService.kt`, `AmazonService.kt` — service URIs

Use the `AppPaths.java` abstraction from `origin/feat/package-rename-support` to
centralize all path construction.

## What is NOT patched here (and why)

### bionic box64 builds (`box64-0.3.x-bionic.tzst`)
These contain `com.termux` paths only — no `app.gamenative` references.
The `com.termux` paths are for Termux's own glibc layer and are unrelated to our package.

### wrapper libs (`wrapper.tzst`, `wrapper-legacy.tzst`, etc.)
All versions contain `com.winlator.cmod` and `com.termux` paths, NOT `app.gamenative`.
These are pre-bionic Winlator cmod paths. The bionic redirect shim handles the
`com.winlator.cmod` → (our package) rewrite at runtime.

### `imagefs_gamenative.txz` / `imagefs_bionic.txz` (downloaded at runtime)
These are downloaded from `https://downloads.gamenative.app/` and not bundled in the APK.

- **GLIBC imagefs**: contains `libredirect.so` with `app.gamenative` hardcodes,
  and `wineserver` with `com.winlator` paths. The APK's `redirect.tzst` is extracted
  AFTER the imagefs and overwrites `libredirect.so`, so only the APK asset needs patching.
  The `wineserver`'s `com.winlator` paths are handled by the redirect shim at runtime.

- **Bionic imagefs**: contains `aserver`/`cacaserver` with `com.winlator` paths
  (RPATHs). These are less critical; the bionic redirect shim handles most path
  rewriting for the wine-bionic/box64-bionic executables.

## Architecture notes

```
Android layer (bionic):
  wine-bionic, box64-bionic
       ↓ preloaded
  libredirect-bionic.so   old_pkg=com.winlator.cmod → new_pkg=app.gnlime
                          (patched from app.gamenative to app.gnlime)

GLIBC layer (inside imagefs):
  wineserver, wine, box64 (GLIBC)
       ↓ preloaded
  libredirect.so          com.winlator/files/rootfs → app.gnlime/files/imagefs
                          (patched from app.gamenative to app.gnlime)

GLIBC box64 interpreter:
  PT_INTERP = /data/data/app.gnlime/files/imagefs/usr/lib/ld-linux-aarch64.so.1
  (patched from app.gamenative)
```
