# LSFG / lsfg-vk research for GameNative containers

_Last updated: 2026-03-09_

## Goal

Figure out the smallest realistic path to a **Lossless Scaling Frame Generation** proof of concept inside GameNative containers, with enough notes to start implementation safely.

## High-level recommendation

**Start with GLIBC containers only** and target the **AArch64 GLIBC** build from `xXJSONDeruloXx/lsfg-vk` tag `arm-test` first.

This materially changes my earlier assessment.

Why:

- GameNative GLIBC launches Wine/Proton through **box64**, and the launcher clearly separates:
  - native library path: `LD_LIBRARY_PATH=/usr/lib`
  - emulated x86_64 library path: `BOX64_LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu`
- Vulkan layers are very likely to need to exist on the **native side** of that boundary.
- I verified the `arm-test` release asset is:
  - `ELF 64-bit LSB shared object, ARM aarch64`
  - exporting `layer_vkGetInstanceProcAddr` and `layer_vkGetDeviceProcAddr`
- The `arm-test` source tag also includes the matching manifest file:
  - `VkLayer_LS_frame_generation.json`
- The fork is still on the familiar **v1 config / legacy env var** model, which is ideal for an MVP.

Important caveat:

- The `arm-test` binary is **GLIBC-linked** and references `GLIBC_2.38`.
- So it looks like a good fit for **GLIBC** containers, but **not** for **BIONIC** containers.
- We still need to verify the GameNative GLIBC imagefs provides a new enough glibc runtime.

So the updated priority order is:

1. **GLIBC + arm-test AArch64 build**
2. **GLIBC + upstream x86_64 build** as fallback experiment
3. **BIONIC / arm64ec** later, with a different binary strategy

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

### Fork ARM build line (`xXJSONDeruloXx/lsfg-vk` tag `arm-test`)

User-provided lead:

- `https://github.com/xXJSONDeruloXx/lsfg-vk/releases/tag/arm-test`

What I verified:

- release metadata:
  - tag: `arm-test`
  - prerelease: `true`
  - tag created: `2025-10-06T15:16:09Z`
  - release published: `2025-11-28T03:10:14Z`
- tag commit:
  - `62f814999f754907570c7c55be74493f0ff5e0c7`
  - message: `enhancement(flatpak): add support for 25.08`
- release assets:
  - only `liblsfg-vk.so`

Binary inspection of that asset:

- architecture: `aarch64`
- SONAME: `liblsfg-vk.so`
- exported layer entrypoints:
  - `layer_vkGetInstanceProcAddr`
  - `layer_vkGetDeviceProcAddr`
- also exports the framegen symbols:
  - `LSFG_3_1::*`
  - `LSFG_3_1P::*`
- dynamic dependencies:
  - `libstdc++.so.6`
  - `libm.so.6`
  - `libgcc_s.so.1`
  - `libc.so.6`
- required symbol versions include:
  - `GLIBC_2.17`
  - `GLIBC_2.32`
  - `GLIBC_2.34`
  - `GLIBC_2.38`

Implications:

- This is **not** an Android/Bionic-native build.
- It is an **ARM64 Linux GLIBC** build.
- That makes it promising for **GLIBC** GameNative containers and still questionable for **BIONIC** ones.

Manifest / packaging findings:

- The release asset does **not** include the manifest JSON.
- But the tag source tree does include:
  - `VkLayer_LS_frame_generation.json`
- The tag `CMakeLists.txt` installs:
  - `liblsfg-vk.so -> lib`
  - `VkLayer_LS_frame_generation.json -> share/vulkan/implicit_layer.d`

So for GameNative we can still package/install it ourselves by pairing:

- the released `liblsfg-vk.so`
- the source manifest from the same tag

We would still want to patch `library_path` in the manifest to the Decky-style relative path:

- `../../../lib/liblsfg-vk.so`

Config / env model at that tag:

- config version is still `version = 1`
- config shape is still `[global]` + `[[game]]`
- legacy env vars still exist:
  - `LSFG_LEGACY`
  - `LSFG_DLL_PATH`
  - `LSFG_MULTIPLIER`
  - `LSFG_FLOW_SCALE`
  - `LSFG_PERFORMANCE_MODE`
  - `LSFG_HDR_MODE`
  - `LSFG_EXPERIMENTAL_PRESENT_MODE`

That means the forked ARM build still fits the simple MVP plan very well.

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

Important env split from the launcher:

- `LD_LIBRARY_PATH=<root>/usr/lib`
- `BOX64_LD_LIBRARY_PATH=<root>/usr/lib/x86_64-linux-gnu`

This suggests a split between:

- native ARM64-side libraries used by the host / wrappers
- emulated x86_64-side libraries used by boxed programs

Given that, the newly found `arm-test` build is now the **preferred first thing to test** for GLIBC containers:

- it is `aarch64`
- it exports the actual Vulkan layer entrypoints
- it uses the same v1 config/env model

But there is a compatibility caveat:

- the binary requires `GLIBC_2.38`
- so we need to verify the GameNative GLIBC imagefs is new enough

If that glibc requirement turns out to be too new, the upstream x86_64 bundle remains the obvious fallback experiment.

### BIONIC containers: unclear / risky for MVP

Relevant code:

- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`

BIONIC launch path is native ARM/Bionic-oriented and does not mirror the GLIBC/box64 model.

The newly found ARM build does **not** remove that risk, because binary inspection shows it is linked against:

- `libc.so.6`
- `libstdc++.so.6`
- `GLIBC_2.38`

So this ARM asset looks like a **Linux GLIBC AArch64** build, not an Android/Bionic build.

Recommendation:

- **Do not block the first PoC on BIONIC / arm64ec support.**
- Gate the first implementation to **GLIBC**.
- Treat BIONIC as a separate follow-up that likely needs a different build target.

## Fastest PoC design

### Phase 1: manual-ish but integrated enough to validate

Target:

- GLIBC containers only
- forked `arm-test` AArch64 GLIBC `liblsfg-vk.so`
- source-matched manifest from the same tag
- legacy env vars

At launch / setup time:

1. Ensure the active container has:
   - `~/.local/lib/liblsfg-vk.so`
   - `~/.local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json`
2. Source those files from:
   - release asset: `liblsfg-vk.so`
   - tag source tree: `VkLayer_LS_frame_generation.json`
3. Fix manifest `library_path` to the Decky-style relative path:
   - `../../../lib/liblsfg-vk.so`
4. Resolve `Lossless.dll` via either:
   - manual path override, or
   - `SteamService.getAppDirPath(993090) + "/Lossless.dll"`
5. Inject legacy env vars:
   - `LSFG_LEGACY=1`
   - `LSFG_DLL_PATH=<resolved path>`
   - `LSFG_MULTIPLIER=<n>`
   - `LSFG_FLOW_SCALE=<value>`
   - `LSFG_PERFORMANCE_MODE=0|1`
6. Do **not** worry about hot reload yet.

Why this is attractive:

- almost no profile logic
- no quick-menu work required
- minimal UI requirement
- binary/model still matches the familiar v1 lsfg-vk integration style

Fallback if the GLIBC runtime is too old for the forked ARM binary:

- try the upstream x86_64 bundle as the secondary experiment

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
- install the forked `arm-test` `liblsfg-vk.so` plus the matching manifest into active container `~/.local`
- add a minimal Graphics-tab section with:
  - enable toggle
  - dll path
  - multiplier
  - flow scale
  - performance mode
- inject **legacy** lsfg env vars at launch
- auto-fill DLL path from Steam app **993090** when available
- add one explicit runtime caveat in logs / docs if GLIBC version mismatch prevents loading

That gives the best chance of a quick yes/no answer on whether LSFG is viable in GameNative containers without overcommitting to the final UX yet.
