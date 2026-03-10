# LSFG / lsfg-vk research for GameNative containers

_Last updated: 2026-03-09_

## Goal

Figure out the smallest realistic path to a **Lossless Scaling Frame Generation** proof of concept inside GameNative containers, with enough notes to start implementation safely.

## High-level recommendation

**Start with GLIBC containers only** and target the current **x86_64 lsfg-vk release artifact** (`lsfg-vk_noui.zip`) first.

Why:

- GameNative GLIBC launches Wine/Proton through **box64**, so the Unix-side Vulkan userland is effectively the **x86_64 Linux stack running under box64**.
- Upstream lsfg-vk stable release assets are readily available as **x86_64**.
- This matches the existing Steam Deck / Decky ecosystem most closely.
- BIONIC / arm64ec support is much less certain and likely needs a different native layer build.

## What GameNative already gives us

### 1) Per-container HOME is already solved

Relevant code:

- `app/src/main/java/com/winlator/container/ContainerManager.java`
- `app/src/main/java/com/winlator/xenvironment/ImageFs.java`

Important behavior:

- Containers live under `imagefs/home/xuser-<containerId>`.
- The active container is exposed as the symlink `imagefs/home/xuser`.
- `HOME` is set to `imageFs.home_path`, which resolves to `imagefs/home/xuser`.

Implication:

- `~/.config/lsfg-vk/conf.toml` is **already per-container**.
- `~/.local/share/vulkan/implicit_layer.d/...` is **already per-container**.
- `~/.local/lib/...` is **already per-container**.

In practical GameNative terms, the real install targets can simply be derived from `container.rootDir`:

- `<container.rootDir>/.config/lsfg-vk/conf.toml`
- `<container.rootDir>/.local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json`
- `<container.rootDir>/.local/lib/liblsfg-vk.so`

This is a big difference from the Decky plugin situation. We do **not** need weird profile-sharing hacks just to keep configs separated per container.

### 2) Container env vars already flow into launch

Relevant code:

- `app/src/main/java/app/gamenative/ui/component/dialog/EnvironmentTab.kt`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`

At launch time GameNative does:

- build an `EnvVars`
- merge container env vars with `envVars.putAll(container.envVars)`
- pass them to the guest launcher

Implication:

- For an MVP, GameNative can drive lsfg-vk either by:
  - writing a config file, or
  - setting env vars directly at launch.

### 3) Steam app install paths are already known by GameNative

Relevant code:

- `app/src/main/java/app/gamenative/service/SteamService.kt`
- `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`

`SteamService.getAppDirPath(gameId)` already resolves installed Steam game directories.

Implication:

- For a first pass, we can resolve Lossless Scaling itself via its Steam app install dir instead of inventing a new downloader flow.
- Practical target path:
  - `SteamService.getAppDirPath(993090) + "/Lossless.dll"`

That avoids copying `Lossless.dll` into the container for the first PoC.

## Upstream lsfg-vk findings

Repository researched:

- `https://github.com/PancakeTAS/lsfg-vk`

### Stable release line (v1.0.0)

Release assets include:

- `lsfg-vk_noui.zip`
- `lsfg-vk-1.0.0-x86_64.zip`
- distro packages / flatpak variants

`lsfg-vk_noui.zip` contents:

- `share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json`
- `lib/liblsfg-vk.so`

Observed locally from the downloaded asset:

- `lib/liblsfg-vk.so` is **ELF 64-bit x86-64**
- manifest layer name is `VK_LAYER_LS_frame_generation`
- disable env is `DISABLE_LSFG=1`

### Stable v1 config format

Default config location:

- `~/.config/lsfg-vk/conf.toml`

Config shape:

- `version = 1`
- `[global]`
- `[[game]]`
- game selector key is `exe`

Interesting fields for MVP:

- global:
  - `dll`
- game:
  - `exe`
  - `multiplier`
  - `flow_scale`
  - `performance_mode`
  - `hdr_mode`
  - `experimental_present_mode`

### Stable v1 legacy env mode

Upstream stable still supports a pure env-var path via `LSFG_LEGACY=1`.

Relevant env vars:

- `LSFG_LEGACY=1`
- `LSFG_DLL_PATH`
- `LSFG_MULTIPLIER`
- `LSFG_FLOW_SCALE`
- `LSFG_PERFORMANCE_MODE`
- `LSFG_HDR_MODE`
- `LSFG_EXPERIMENTAL_PRESENT_MODE`
- `LSFG_PROCESS` (process/profile override helper)
- `LSFG_CONFIG` (custom config path)

This lines up closely with the “legacy launch flags” you mentioned and is the easiest path for a first PoC.

### Current upstream dev line (v2.0.0-dev)

The current main branch has moved to:

- `version = 2`
- `[global]`
- `[[profile]]`
- `active_in`
- env mode via `LSFGVK_ENV=1`

Important v2 env vars:

- `LSFGVK_ENV=1`
- `LSFGVK_DLL_PATH`
- `LSFGVK_MULTIPLIER`
- `LSFGVK_FLOW_SCALE`
- `LSFGVK_PERFORMANCE_MODE`
- `LSFGVK_PACING`
- `LSFGVK_GPU`
- `LSFGVK_CONFIG`
- `LSFGVK_PROFILE`

Hot-reload notes from upstream docs:

- `multiplier`
- `flow_scale`
- `performance_mode`

…are hot-reloadable in the v2 config-file path.

### Important versioning takeaway

There are effectively **two integration targets**:

1. **Stable v1 / legacy env vars**
   - best match for Decky plugin behavior
   - simplest PoC
2. **Dev v2 / profile config or LSFGVK_ENV**
   - better long-term target
   - cleaner upstream direction

For GameNative MVP, **v1 legacy env mode is the fastest validation path**.

## Decky plugin findings

Repository researched:

- `https://github.com/xXJSONDeruloXx/decky-lsfg-vk`

### What it installs

The plugin installs lsfg-vk to standard per-user Linux locations:

- `~/.local/lib/liblsfg-vk.so`
- `~/.local/share/vulkan/implicit_layer.d/...json`
- `~/.config/lsfg-vk/conf.toml`

It also creates a helper launch script:

- `~/lsfg`

and users add:

- `~/lsfg %command%`

to Steam launch options.

### What the plugin contributes that is relevant to us

Useful ideas to borrow:

- DLL auto-detection by scanning Steam libraries
- very small user-facing control surface:
  - multiplier
  - flow scale
  - performance mode
  - optional extras
- explicit workarounds for:
  - vkBasalt conflicts
  - MangoHud
  - gamescope WSI
  - DXVK frame caps

Things we probably **do not** need for first GameNative PoC:

- Decky launch script model
- profile management complexity
- gamescope-specific toggles
- Steam Deck mode workarounds

GameNative already owns launch orchestration, container HOME, and env injection.

## Vulkan loader path findings

I checked the Khronos Vulkan loader documentation.

Important points:

- User implicit layers are searched in standard locations including:
  - `$HOME/.local/share/vulkan/implicit_layer.d`
  - `$HOME/.config/vulkan/implicit_layer.d`
- `VK_LAYER_PATH` affects **explicit** layer search.
- Implicit layers can also be overridden with `VK_IMPLICIT_LAYER_PATH`, but that should not be necessary for the normal install layout.

Implication:

- Installing the manifest to container HOME under:
  - `~/.local/share/vulkan/implicit_layer.d`

is the correct default strategy.

## Architecture / runtime assessment

### GLIBC containers: promising

Relevant code:

- `app/src/main/java/com/winlator/xenvironment/components/GlibcProgramLauncherComponent.java`

GLIBC launch path executes:

- `box64 <wine/proton command>`

Meaning the Unix-side Wine/Proton userspace is the x86_64 stack under box64.

That makes the upstream **x86_64 lsfg-vk release artifact the correct first thing to test** for GLIBC containers.

### BIONIC containers: unclear / risky for MVP

Relevant code:

- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`

BIONIC launch path is native ARM/Bionic-oriented and does not mirror the GLIBC/box64 model.

Risks:

- official upstream release assets I found are **not ARM/Bionic builds**
- a BIONIC path may require:
  - an ARM64 build
  - and possibly Android/Bionic-compatible packaging assumptions

Recommendation:

- **Do not block the first PoC on BIONIC / arm64ec support.**
- Gate the first implementation to **GLIBC**.

## Fastest PoC design

### Phase 1: manual-ish but integrated enough to validate

Target:

- GLIBC containers only
- stable `lsfg-vk_noui.zip`
- legacy env vars

At launch / setup time:

1. Ensure the active container has:
   - `~/.local/lib/liblsfg-vk.so`
   - `~/.local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json`
2. Fix manifest `library_path` if needed to the Decky-style relative path:
   - `../../../lib/liblsfg-vk.so`
3. Resolve `Lossless.dll` via either:
   - manual path override, or
   - `SteamService.getAppDirPath(993090) + "/Lossless.dll"`
4. Inject legacy env vars:
   - `LSFG_LEGACY=1`
   - `LSFG_DLL_PATH=<resolved path>`
   - `LSFG_MULTIPLIER=<n>`
   - `LSFG_FLOW_SCALE=<value>`
   - `LSFG_PERFORMANCE_MODE=0|1`
5. Do **not** worry about hot reload yet.

Why this is attractive:

- almost no profile logic
- no quick-menu work required
- minimal UI requirement
- closest match to the Decky “just prove it works” path

### Phase 2: container-native config file

Once Phase 1 works, move to writing container-local:

- `~/.config/lsfg-vk/conf.toml`

Benefits:

- cleaner persistence
- easier future hot reload / quick-menu editing
- closer to upstream model

### Suggested first UI shape

Best initial placement:

- **Graphics tab** in container settings

Recommended first controls:

- Enable LSFG (toggle)
- DLL path (text field or auto-detected readout with override)
- Multiplier (2 / 3 / 4)
- Flow Scale (slider, probably 0.25–1.0)
- Performance Mode (toggle)

Optional note-only fields for later:

- HDR
- present mode override
- GPU selection
- quick menu / hot reload

## Likely implementation shape

A clean first implementation would probably be a small helper like `LsfgVkManager` under `app/src/main/java/app/gamenative/utils/` with responsibilities split like this:

- `resolveLosslessDllPath(...)`
  - manual override first
  - then `SteamService.getAppDirPath(993090) + "/Lossless.dll"`
- `installLsfgVkFiles(...)`
  - ensure container-local `.local/lib` and `.local/share/vulkan/implicit_layer.d`
  - extract or copy `liblsfg-vk.so`
  - write/fix the manifest JSON `library_path`
- `applyLegacyEnv(...)`
  - inject `LSFG_LEGACY`, dll path, multiplier, flow scale, performance mode
- later: `writeConfigToml(...)`
  - for the non-legacy / hot-reload path

That keeps the XServer launch code mostly focused on orchestration instead of file plumbing.

## Likely implementation touchpoints

### Persistence / data model

Files likely needing changes if we add real settings:

- `app/src/main/java/com/winlator/container/ContainerData.kt`
- `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`
- `app/src/main/java/app/gamenative/PrefManager.kt` (if default-container settings matter)

### UI

- `app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigState.kt`
- `app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt`
- `app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt`
- `app/src/main/res/values/strings.xml`
- maybe `app/src/main/res/values/arrays.xml`

### Launch / install logic

- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- possibly a new helper under `app/src/main/java/app/gamenative/utils/`

## Biggest open questions

1. **Does the x86_64 layer load cleanly inside the GLIBC box64 Proton path in practice?**
   - I think yes, but this needs a real device test.

2. **Should we ship the upstream x86_64 zip as an app asset, or download it on demand?**
   - For pure PoC, bundling or hardcoding is fine.
   - Long-term, download / managed content is cleaner.

3. **Should the first PoC use legacy env vars or a conf.toml file?**
   - My vote: **legacy env vars first**, then config file second.

4. **How much of the Decky workaround matrix matters on Android + GameNative?**
   - gamescope-specific toggles likely do not
   - vkBasalt conflict handling might still matter later

## Current recommendation

If I start implementing next, I would do this exact scope:

- GLIBC only
- stable `lsfg-vk_noui.zip`
- install/extract into active container `~/.local`
- add a minimal Graphics-tab section with:
  - enable toggle
  - dll path
  - multiplier
  - flow scale
  - performance mode
- inject **legacy** lsfg env vars at launch
- auto-fill DLL path from Steam app **993090** when available

That gives the best chance of a quick yes/no answer on whether LSFG is viable in GameNative containers without overcommitting to the final UX yet.
