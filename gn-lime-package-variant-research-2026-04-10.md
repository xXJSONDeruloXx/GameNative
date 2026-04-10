# GN-Lime package variant research

Date: 2026-04-10
Branch: `research/gn-lime-package-variant`
Repo: `/Users/kurt/Developer/GameNative`

## Goal

Research how to create a **side-by-side installable** GameNative fork with a different Android package name and branding (working name: **GN-Lime**) so users can keep stock GameNative installed at the same time.

This document is about a **safe forked package ID**, not an app that impersonates a third-party package like AnTuTu / PUBG / Ludashi.

---

## Executive summary

### Short version

Yes — a GN-Lime side-by-side build looks feasible, but **not** as a one-line `applicationId` change on current `master`.

The current blocker pattern is consistent across:
- current source on `master`
- Discord discussions
- old PRs / issues
- Jeremy Bernstein's `jb/dev-env` branch
- local branch `origin/feat/package-rename-support`

### What is true right now

1. **Current `master` still has runtime-critical hardcoded `app.gamenative` paths.**
2. **Current shipped runtime archives also still embed `app.gamenative` paths.**
3. **A side-by-side debug/dev build has already been explored and partially proven** in `jeremybernstein/GameNative` `jb/dev-env`.
4. **There is a much larger local branch here already**: `origin/feat/package-rename-support`, which contains research plus a more serious de-hardcoding effort.
5. **Bionic looks realistically fixable first. GLIBC remains the harder path.**

### Recommendation

If the goal is **GN-Lime as a parallel install**, the best path is:

- target a **safe custom package name** first
- prioritize a **bionic-first side-by-side build**
- treat **glibc as experimental** until the binary/runtime layer is cleaned up more fully
- reuse ideas from `jb/dev-env` and `origin/feat/package-rename-support`
- **do not** start with third-party spoof package IDs

---

## Key sources reviewed

### Discord

#### Linked message / side-by-side dev build hint
- Channel `1486139389238317179`, message `1486259672280334526`
- sockmonkey72 shared `jeremybernstein/GameNative/tree/jb/dev-env`
- Quote: this turns GameNative into **"GameNative Dev"** for sandboxed testing
- Known issue called out there: **controller support in the glibc container**

#### Package naming discussion
- General thread: `Package Naming chat` `1479449383027212451`
- Supporting general messages in `1412756778159964201`
- Repeated findings:
  - package-name changes can unlock device-specific performance modes on some phones
  - controller/input is the first thing people report breaking
  - devs repeatedly describe current support as **hardcoded** and needing a **chunky refactor**

#### Debug/daily-driver limitations
- `How to PR?` thread `1481326067858804756`
- Quote from spacebubble: `We currently have a technical limitation regarding debug builds vs daily drive due to package-name hard-coded constraints. That's being looked at`

#### User reports after renaming
- `Controller input not working on screen or usb c` thread `1485789264049340558`
- User report: after changing package name for Red Magic frame generation, controller input stops working
- utkarshdalal response: effectively `you can't change the package name` / `it won't work`

#### 2026-03-22 general discussion
- Around message `1485360145121804460`
- Repeated theme:
  - rename attempts took many hours
  - bionic could sometimes be made to limp along after relinking
  - glibc still failed after rename attempts
  - users suspected this explains why some forks kept the stock app ID

### GitHub / repo history

#### Relevant upstream issues / PRs
- Issue `#618` — `Hardcoded /data/data/app.gamenative paths break variant builds`
- PR `#585` — `fix: replace hardcoded app ID paths with BuildConfig.APPLICATION_ID`
- PR `#684` — `feat: Add Ludashi, PUBG and AnTuTu package options`

#### Relevant branches
- Local remote branch: `origin/feat/package-rename-support`
- External branch: `https://github.com/jeremybernstein/GameNative/tree/jb/dev-env`

---

## What current `master` still hardcodes

### Runtime-critical source hardcodes found locally

Current `master` still contains hardcoded `/data/data/app.gamenative` paths in:

- `app/src/main/cpp/extras/evshim.c`
- `app/src/main/java/com/winlator/winhandler/WinHandler.java`
- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`
- `app/src/main/java/com/winlator/core/WineUtils.java`
- `app/src/main/java/com/winlator/core/DXVKHelper.java`
- `app/src/main/java/com/winlator/container/Container.java`

These directly affect:
- controller shared-memory files (`gamepad.mem`)
- Wine `E:` drive mapping
- DXVK cache path
- mediaconv paths

### Package-scoped string literals still hardcoded on `master`

- `app/src/main/AndroidManifest.xml`
  - `app.gamenative.LAUNCH_GAME`
- `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt`
- `app/src/main/java/app/gamenative/utils/ShortcutUtils.kt`
- `app/src/main/java/app/gamenative/utils/IconSwitcher.kt`
- internal service action strings in:
  - `GOGService.kt`
  - `EpicService.kt`
  - `AmazonService.kt`

### Runtime archives still embedding `app.gamenative`

Auditing current packaged assets on `master` found hits in at least:

- `app/src/main/assets/redirect.tzst`
- `app/src/main/assets/graphics_driver/turnip-25.2.0.tzst`
- `app/src/main/assets/graphics_driver/turnip-25.3.0.tzst`
- `app/src/main/assets/graphics_driver/vortek-2.0.tzst`
- `app/src/main/assets/graphics_driver/vortek-2.1.tzst`
- `app/src/main/assets/box86_64/box64-0.3.4.tzst`
- `app/src/main/assets/box86_64/box64-0.3.6.tzst`
- `app/src/main/assets/box86_64/box64-0.3.8.tzst`

Representative strings currently present in those archives include:
- `/data/data/app.gamenative/files/imagefs/usr/lib/libvulkan_freedreno.so`
- `/data/data/app.gamenative/files/imagefs/usr/lib/libvulkan_vortek.so`
- `/data/data/app.gamenative/files/imagefs/usr/lib/ld-linux-aarch64.so.1`
- `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so`

This is the clearest reason why changing only Gradle `applicationId` is not enough.

---

## Existing work already found

## 1) `jeremybernstein/GameNative` → `jb/dev-env`

This is the most directly useful “small working branch” for side-by-side installs.

### What it does
- adds `applicationIdSuffix = ".debug"` to debug builds
- changes label via manifest placeholders (`GameNative Dev`)
- changes manifest launch action to `${applicationId}.LAUNCH_GAME`
- builds `evshim` from source for dev use
- swaps several `/data/data/app.gamenative` uses to `BuildConfig.APPLICATION_ID`

### What it proves
- a **parallel-install dev/sandbox build** is practical enough to use as a workflow
- the first set of source-level hardcodes really does matter
- bionic-side controller issues are strongly tied to `evshim` / `gamepad.mem` path handling

### What it does *not* prove
- full glibc-side rename support
- full asset/runtime independence from `app.gamenative`
- production-ready flavor support

### Most important quote from linked Discord message
- the only known issue called out there was **glibc controller support**, which matches the broader research

## 2) Local branch `origin/feat/package-rename-support`

This branch is much larger and much closer to a real rename-support effort.

### Important commits on that branch
- `764e319e` — `docs: add package renaming research notes`
- `c12d2441` — `fix: de-hardcode package scoped runtime paths`
- `20744184` — `per pkg build work`

### What it adds conceptually
- central app/package path helper (`AppPaths.java`)
- dynamic launch action / alias handling
- dynamic gamepad path handling
- dynamic wrapper env vars (`WRAPPER_LAYER_PATH`, `WRAPPER_CACHE_PATH`)
- env-driven input hook paths (`FAKE_EVDEV_DIR`, `EVSHIM_DATA_PATH`, `EVSHIM_WIN_PATH`)
- extraction-time / runtime work toward rebuilding or patching source-controlled native pieces
- tooling / notes for auditing runtime archives

### Why it matters
This is the strongest local evidence that the right path is **not** “just flavor the package name.”
It is:
- de-hardcode app/runtime paths in code
- rebuild or neutralize binary/runtime assumptions
- reduce reliance on opaque legacy redirect shims

## 3) Upstream issue / PR history

### Issue `#618`
- explicitly documents that hardcoded `/data/data/app.gamenative` breaks variant builds
- maintainer response in comments: `Let's not do this one please...`
- meaning: the problem is real, but maintainers were reluctant to take it on in that form

### PR `#585`
- replaced hardcoded app-ID paths with `BuildConfig.APPLICATION_ID`
- specifically targeted:
  - gamepad input
  - E: drive
  - DXVK cache
  - mediaconv
- later comments said it was superseded by other work / dev-env style work
- but current `master` still clearly contains hardcoded paths again or still

### PR `#684`
- added simple product flavors for Ludashi / Antutu / PUBG package IDs only
- **draft, then closed as unwanted**
- important lesson: **flavors alone are not the solution**
- also came with obvious policy/legal risk because those were third-party package IDs

---

## GN-Lime-specific recommendation

## Use a safe, custom package ID

If the goal is a real distributable fork, prefer a custom package ID that is:
- yours / clearly fork-specific
- short enough to avoid path-length issues in existing GLIBC assets
- not impersonating another app

### Best current candidate
- `app.gnlime`

### Why this is better than `app.gamenative.lime`

I checked the current `box64-0.3.8.tzst` binary and its ELF `PT_INTERP` slot is exactly sized for:

`/data/data/app.gamenative/files/imagefs/usr/lib/ld-linux-aarch64.so.1\0`

That slot is **70 bytes total**.

Implication:
- `app.gamenative.lime` is **too long** if you want to patch the current box64 binary in-place
- `app.gnlime` **fits**
- `app.gnlime.dev` also fits, but only barely

So if GN-Lime wants to reuse current box64 assets with patching instead of rebuilding them, **short package names matter**.

### Recommended naming set
- App label: `GN-Lime`
- Flavor name: `lime`
- Candidate `applicationId`: `app.gnlime`

---

## Practical implementation path

## Phase 1 — get a side-by-side bionic build working

This is the best first milestone.

### Suggested approach
1. Start from current `master`
2. Port the low-risk side-by-side pieces from `jb/dev-env`
3. Port the package-path centralization from `origin/feat/package-rename-support`
4. Add a `lime` product flavor to:
   - `app/build.gradle.kts`
   - `ubuntufs/build.gradle.kts`
5. Use flavor-specific placeholders for:
   - app label
   - icon if desired
   - launch action strings / authorities where needed
6. Keep code namespace as `app.gamenative`
   - only `applicationId` changes
   - no need to rename source packages

### Success criteria for Phase 1
- installs alongside stock GameNative
- bionic container boots
- controller input works
- internal storage paths are variant-correct
- launch shortcuts and file provider still work

## Phase 2 — clean up runtime asset assumptions

Needed for a real usable fork.

### Minimum targets
- patch or rebuild Vulkan ICD JSONs to remove app-specific absolute paths
- deal with box64 interpreter path assumptions
- make sure wrapper env vars override package-bound fallbacks cleanly

### Existing branch hints
`origin/feat/package-rename-support` already contains tooling and research pointing in this direction.

## Phase 3 — GLIBC support

This remains the hardest part.

### Why GLIBC is still risky
Current evidence points to dependencies outside the plain Kotlin/Java tree:
- box64 packaged interpreter path
- `redirect.tzst` preload libs
- custom Wine / `wine-custom` path assumptions
- possible length constraints for in-place binary patching

### Recommendation
Treat GLIBC as one of:
- disabled for GN-Lime initially, or
- clearly experimental

Bionic-first is the lower-risk route.

---

## Important technical insights from this research

### 1) Namespace and applicationId do **not** have to match
This is good news.

You can keep source code under:
- `app.gamenative`

while installing the APK as:
- `app.gnlime`

The hard problem is runtime file paths and packaged binaries, not Kotlin/Java package declarations.

### 2) Current `release-gold` is evidence the app already *wants* variant support
Current `master` already has:
- `applicationIdSuffix = ".gold"`

So the idea of alternate application IDs is not foreign to the repo.
The problem is that runtime support is incomplete.

### 3) Flavor-only work was already tried and was insufficient
PR `#684` proved the easy part:
- adding flavors is trivial

It did **not** solve:
- runtime hardcoded paths
- controller breakage
- GLIBC runtime assumptions
- binary asset assumptions

### 4) App-ID length may be a real constraint
If you reuse current GLIBC/box64 assets and patch them in-place, **shorter package names are materially safer**.

---

## Suggested test matrix for GN-Lime

## Install / coexistence
- stock GameNative installed
- GN-Lime installed alongside it
- both launch independently

## Bionic
- create new container
- boot to library
- launch game
- verify controller input
- verify DXVK cache path
- verify E: drive path

## GLIBC
- same checks, but expect this to be the fragile path
- explicitly verify controller input and box64 startup

## External integration
- shortcut launch
- intent launch
- file sharing / update install path

## Upgrade behavior
- stock GameNative upgrade does not break GN-Lime
- GN-Lime upgrade does not break stock GameNative

---

## Bottom line

### High confidence
- A **GN-Lime side-by-side fork is feasible**.
- It should be approached first as a **safe custom package variant**, not a spoof-package feature.
- The most realistic first target is a **bionic-first side-by-side build**.
- `jb/dev-env` is the best proof-of-concept starting point.
- `origin/feat/package-rename-support` is the best serious technical foundation already sitting in this fork.

### Main cautions
- current `master` is still not rename-safe
- glibc remains the hardest part
- binary/runtime archives are still package-bound in several places
- **package name length matters more than it first appears**

### Recommended next implementation target
If we build this for real, I would start with:
- flavor `lime`
- `applicationId = "app.gnlime"`
- label `GN-Lime`
- bionic-first validation
- glibc marked experimental until its runtime path stack is cleaned up

---

## Follow-up source-mapping research (2026-04-10)

I did a second-pass source hunt focused on public repos under:
- `https://github.com/utkarshdalal?tab=repositories`
- upstream Pluvia: `https://github.com/oxters168/Pluvia`

I cloned and scanned these repos locally under `/tmp/gn-research/`:
- `utkarshdalal/box64`
- `utkarshdalal/bionic-vulkan-wrapper`
- `utkarshdalal/wine-custom`
- `utkarshdalal/winlator-cmod`
- `utkarshdalal/proton-wine`
- `utkarshdalal/wine-9.2-custom`
- `utkarshdalal/ziad-wine`
- `utkarshdalal/gbe_fork`
- `oxters168/Pluvia`

### Confirmed source-backed pieces

#### 1) Box64 hardcoding is source-visible and rebuildable

In `utkarshdalal/box64`:
- `.github/workflows/release.yml`
  - contains:
    - `patchelf --set-interpreter /data/data/com.winlator/files/imagefs/usr/lib/ld-linux-aarch64.so.1 ./box64`

This is strong confirmation that the package-bound `PT_INTERP` path in shipped `box64-*.tzst` assets is not mysterious. It is being baked in during packaging and can be changed by rebuilding or repackaging.

#### 2) The Vulkan wrapper / ICD path assumptions are source-visible and rebuildable

In `utkarshdalal/bionic-vulkan-wrapper`:
- `src/vulkan/wrapper/graphics_env_hooks.cpp`
  - hardcodes:
    - `/data/data/com.winlator.cmod/files/imagefs/usr/lib`
    - `/data/data/com.micewine.emu/files/usr/lib`
- `src/vulkan/wrapper/wrapper_instance.c`
  - logs / expects validation layers under:
    - `/data/data/com.winlator.cmod/files/imagefs/usr/lib/`
- `src/freedreno/vulkan/meson.build` and `src/vulkan/wrapper/meson.build`
  - show how ICD JSON artifacts are generated

This means the wrapper / ICD issue is a **real source patch + rebuild task**, not an opaque binary dead-end.

#### 3) GLIBC hardcoding is definitely upstream in `wine-custom`

In `utkarshdalal/wine-custom`, I found live `app.gamenative` path assumptions in actual source files, not just Android app glue:

- `server/request.c`
  - `/data/data/app.gamenative/files/imagefs/tmp/.wine-%u`
- `server/esync.c`
  - `/data/data/app.gamenative/files/imagefs/tmp/wine-...-esync`
- `dlls/ntdll/unix/server.c`
  - `/data/data/app.gamenative/files/imagefs/tmp/.wine-%u/server-...`
  - `symlink( "/data/data/app.gamenative/files/imagefs", "dosdevices/z:" )`
- `programs/winebrowser/main.c`
  - `/data/data/app.gamenative/files/imagefs/usr/bin/open`
- `server/unicode.c`
  - fallback NLS directories under `/data/data/app.gamenative/files/imagefs/usr/...`
- `programs/winemenubuilder/winemenubuilder.c`
  - app-specific share dirs under `/data/data/app.gamenative/files/imagefs/usr/...`

I also found many older path patches under `packages/*` that still reference:
- `/data/data/com.utkarshdalal.PluviaGoldberg/files/usr/...`

Examples include patches for:
- `pulseaudio`
- `glib`
- `Vulkan-Loader`
- `mesa-vulkan-wrapper-adrenotools`
- `xtrans`
- `openssl`
- `libarchive`
- `libunbound`

This is the clearest public-source evidence so far that **GLIBC rename breakage is upstream in the custom Wine / userspace stack itself**, not just in GameNative app code.

### Important comparison against upstream Pluvia

#### `oxters168/Pluvia` is mixed evidence

Current `oxters168/Pluvia` has:
- `app/build.gradle.kts`
  - `namespace = "com.OxGames.Pluvia"`
  - `applicationId = "com.OxGames.Pluvia"`
  - `applicationIdSuffix = ".gold"`

But it still contains at least one stale Winlator-era hardcode:
- `app/src/main/java/com/winlator/container/Container.java`
  - `E:/data/data/com.winlator/storage`

So upstream Pluvia does **not** look fully package-decoupled either.

#### However: older Pluvia box64 packaging looks more rename-friendly than current GameNative

I unpacked `oxters168/Pluvia` asset:
- `app/src/main/assets/box86_64/box64-0.2.9.tzst`

Its ELF interpreter is:
- `/lib/ld-linux-aarch64.so.1`

That is much more rename-safe than current GameNative's package-bound interpreter path. So at some point in later Winlator/GameNative packaging, the GLIBC stack became **more app-ID-bound**, not less.

This suggests a potentially better long-term direction:
- rebuild `box64` with a generic interpreter strategy when possible, instead of hard-baking `/data/data/<package>/...` into the binary

### What I did **not** find in the searched repos

Despite searching the repos above, I still did **not** find public source for:
- `libredirect.so`
- `libredirect-bionic.so`
- `redirect.tzst` build source
- exact source files named in the shipped binaries such as:
  - `preload_replace.c`
  - `preload_replace_bionic.c`
- binary strings previously observed in the shipped redirect libs, such as:
  - `pluviagoldberg_on_load`
  - `libpluviagoldberg.so`
  - `preload_loaded.txt`

I also did not find those redirect shim sources in the extra Utkarsh repos I scanned:
- `proton-wine`
- `wine-9.2-custom`
- `ziad-wine`
- `gbe_fork`
- `winlator-cmod`
- `oxters168/Pluvia`

### Current feasibility read after the repo search

#### Feasible / source-backed
- **Bionic side-by-side support:** still looks feasible first
- **Box64 cleanup:** feasible from public source (`utkarshdalal/box64`)
- **Wrapper / ICD cleanup:** feasible from public source (`utkarshdalal/bionic-vulkan-wrapper`)
- **Wine/custom userspace cleanup:** feasible in principle because source exists, but it is a much larger patch surface

#### Still blocked / uncertain
- **Full GLIBC parity for renamed package IDs:** still risky
- **Redirect shim layer (`redirect.tzst`):** still the biggest unresolved opaque binary gap
- **Drop-in spoof-package builds:** still not credible as a quick or safe path

### Updated practical conclusion

If the goal is a real `GN-Lime` fork, the evidence still points to:

1. **Do a real source-built variant, not an APK-editor rename.**
2. **Target bionic first.**
3. **Treat glibc as experimental until at least box64 + wine-custom + redirect-layer issues are handled.**
4. Prefer a short package name such as:
   - `app.gnlime`
5. Keep a distinction between:
   - **source-known blockers** we can rebuild or patch
   - **source-missing blockers** we may need to replace, reverse, or avoid

---

## Follow-up: `redirect.tzst` provenance and external survey (2026-04-10)

This section focuses specifically on the opaque redirect/preload layer:
- `app/src/main/assets/redirect.tzst`
- `usr/lib/libredirect.so`
- `usr/lib/libredirect-bionic.so`

### High-level conclusion

Right now, `redirect.tzst` looks like a **GameNative-specific artifact**, not something broadly shipped by upstream Pluvia, mainstream Winlator, or MiceWine current trees.

It also looks like it evolved in two steps:

1. a **GLIBC preload shim** lineage tied to the older name `libpluviagoldberg.so`
2. a later **bionic redirect shim** lineage that first appeared as loose JNI libs on the `upstream/new_vortek` branch and only later got packed into `redirect.tzst`

### What I confirmed in local GameNative history

#### GLIBC-side preload naming timeline

- `e1f09f22` (`It works!`, 2025-05-17)
  - `GlibcProgramLauncherComponent.java` sets:
    - `LD_PRELOAD="libpluviagoldberg.so libandroid-sysvshm.so"`
- `2e936d7e` (`Updated name of preload`, 2025-05-27)
  - changes that to:
    - `LD_PRELOAD="libredirect.so libandroid-sysvshm.so"`

Important implication:
- the current GLIBC redirect shim is very likely a **renamed descendant** of an earlier `libpluviagoldberg.so` preload library
- but there is **no committed `libpluviagoldberg.so` file** anywhere in this repo history that I could find

#### Bionic-side redirect timeline

- `2df2b427` (`Progress - patched libvortekrenderer and now it doesn't crash but i get a black screen hehe`, 2025-07-15)
  - branch: `upstream/new_vortek`
  - adds loose JNI binaries:
    - `app/src/main/jniLibs/arm64-v8a/libredirect-bionic.so`
    - `app/src/main/jniLibs/arm64-v8a/libredirect_logging-bionic.so`
- `2c25981f` (`Got aarch64 proton working with LD_PRELOAD`, 2025-10-18)
  - `BionicProgramLauncherComponent.java` starts preloading:
    - `imageFs.getLibDir() + "/libredirect-bionic.so"`
- `451ca4a1` (`Fixed wowbox64 for arm64ec bionic containers`, 2025-10-20)
  - introduces:
    - `app/src/main/assets/redirect.tzst`
  - adds `ImageFsInstaller` extraction logic for it
- `20ebeaf0` (`Initial bionic changes (#191)`, 2025-10-23)
  - merges the bionic stream to mainline and keeps the same `redirect.tzst`

#### Current `redirect.tzst` has never changed since introduction

I checked the blob ID for `app/src/main/assets/redirect.tzst` across local history.

Result:
- it appears to be the **same blob** from its first appearance in the bionic branch through current `master`

So the current packed redirect bundle is not something that has been iterated in-tree many times. It looks more like a single imported binary artifact that survived unchanged.

### What is actually inside `redirect.tzst`

Archive listing:
- `./usr/lib/libredirect.so`
- `./usr/lib/libredirect-bionic.so`
- plus two accidental Mac metadata files:
  - `./.DS_Store`
  - `./usr/.DS_Store`

Tar metadata is also revealing:
- owner/group: `utkarshdalal staff`
- archive directory timestamps: `Oct 20 05:35`
- `libredirect.so` timestamp: `Jul 24 2025`
- `libredirect-bionic.so` timestamp: `Oct 17 21:11`

That strongly suggests this archive was **hand-packed on a Mac**, not produced by a clean reproducible packaging pipeline.

### Binary observations

#### Current asset `libredirect.so`

From strings / symbols in the packed GLIBC shim:
- source filename string:
  - `preload_replace.c`
- retained legacy naming:
  - `[INIT] libpluviagoldberg.so loaded`
  - `pluviagoldberg_on_load`
  - `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so`
- hardcoded path lineage hints:
  - `com.winlator/files/rootfs`
  - `app.gamenative/files/imagefs`
  - `/data/data/app.gamenative/files/imagefs/usr/tmp`
  - `/data/data/app.gamenative/files/imagefs/preload_loaded.txt`

Interpretation:
- the GLIBC redirect shim still carries obvious **PluviaGoldberg-era** and **old Winlator rootfs** lineage inside the binary itself
- it is not package-agnostic today

#### Current asset `libredirect-bionic.so`

The packed bionic shim is **not** the same binary as the loose July 2025 `jniLibs` one.

Current packed asset:
- size: `12680`
- SHA-256: `a6a0ee59bac93112f84bf75994def7607a278e8cb011a6e23414cb0107abc2cd`
- strings include:
  - `preload_replace_bionic.c`
  - `old_pkg`
  - `new_pkg`
  - `rewrite (openat): %s -> %s`
  - `rewrite (read): %s -> %s`
  - `rewrite (ioctl): %s -> %s`
  - `rewrite (fstatat): %s -> %s`
  - `com.winlator.cmod`
  - `app.gamenative`
- notably, it **does not** expose the older xhook JNI symbols

Interpretation:
- this looks like a **smaller later-generation package/path rewrite shim**
- it appears to be doing generic rewrite logic with `old_pkg` / `new_pkg`
- but it is still opaque, source-missing, and still knows about legacy package names

#### Historical loose bionic libs from `2df2b427`

Loose historical `libredirect-bionic.so`:
- size: `45008`
- SHA-256: `b641fbed68c03d925f18a16d6700ed870ffd937260b694dca8020787f907ebe0`

Loose historical `libredirect_logging-bionic.so`:
- size: `45072`
- includes:
  - `libxhook 1.2.0 (aarch64)`
  - `Java_com_qiyi_xhook_NativeHandler_*`
  - `Java_app_gamenative_NativeHooks_init`
  - `preload_replace_bionic.c`
  - `/data/data/com.winlator/`
  - `/data/data/app.gamenative/`

Interpretation:
- the earliest bionic redirect implementation in repo history was an **xhook-based JNI hooking library**
- that same `upstream/new_vortek` tree also temporarily added a `NativeHooks` object in `app/src/main/java/app/gamenative/PluviaApp.kt`:
  - `System.loadLibrary("redirect_logging-bionic")`
  - `NativeHooks.init()` during app startup
- that startup-hook path did **not** survive to mainline
- the later packed `redirect.tzst` bionic shim is a **different, smaller binary**
- so the bionic redirect layer was not just “moved into a tar”; it was **replaced by a different implementation** before `redirect.tzst` landed

### Current external survey results

I checked current trees and/or commit history for:
- `redirect.tzst`
- `libredirect.so`
- `libredirect-bionic.so`
- related references

#### Repos checked
- `utkarshdalal/GameNative`
- `oxters168/Pluvia`
- `brunodev85/winlator`
- `coffincolors/winlator`
- `winebox64/winlator`
- `utkarshdalal/winlator-cmod`
- `KreitinnSoftware/MiceWine-Application`
- `KreitinnSoftware/MiceWine-RootFS-Generator`
- `MaxsTechReview/WinNative`
- `pipetto-crypto/winlator`
- `Vivsi1/winlator`
- `longjunyu2/winlator`
- `afeimod/winlator-glibc`

#### What I found

##### `utkarshdalal/GameNative`
- current tree **does** contain `app/src/main/assets/redirect.tzst`
- this is still the only current tree I found that actually ships the bundle

##### `oxters168/Pluvia`
- **no** `redirect.tzst`
- **no** `libredirect*` files in the current tree
- still has some package-bound paths, but not this exact redirect bundle

##### `brunodev85/winlator`
- **no** `redirect.tzst`
- **no** `libredirect*` files found in current tree
- current tree still has older package-bound paths like `/data/data/com.winlator/...`

##### `coffincolors/winlator`
- **no** `redirect.tzst`
- **no** `libredirect*` files found in current tree
- many package-bound path assumptions still exist, especially around `com.winlator.cmod`

##### `winebox64/winlator`
- **no** `redirect.tzst`
- **no** `libredirect*` files found in current tree

##### `utkarshdalal/winlator-cmod`
- **no** `redirect.tzst`
- **no** `libredirect*` files found in current tree
- still uses package-bound hardcodes in ordinary app code

##### `KreitinnSoftware/MiceWine-*`
- **no** `redirect.tzst`
- **no** `libredirect*` files found
- MiceWine appears to solve Android path assumptions more directly in its rootfs/package build system and patches, e.g. many `/data/data/com.micewine.emu/...` path patches in `MiceWine-RootFS-Generator`

##### `MaxsTechReview/WinNative`
- interesting partial hit:
  - current code **references** `redirect.tzst` and `libredirect.so` / `libredirect-bionic.so`
  - but current tree contains **no actual `redirect.tzst` asset** and no `libredirect*` files
- using GitHub API history, I confirmed those references were introduced on:
  - `fad0594a` (2026-03-18)
- before that, WinNative did not reference redirect libs in the same launcher/installer files

Interpretation:
- WinNative seems to have **ported some GameNative-style launcher logic** for redirect libs
- but it does **not** currently prove a public redistributable source or bundled asset for the redirect binaries themselves

##### Other visible Winlator-family forks checked via GitHub API tree/history
- `pipetto-crypto/winlator`
- `Vivsi1/winlator`
- `longjunyu2/winlator`
- `afeimod/winlator-glibc`

For these forks, I did **not** find:
- `redirect.tzst`
- `libredirect.so`
- `libredirect-bionic.so`
- `libpluviagoldberg.so`

That makes the redirect bundle look even more like a **GameNative-local addition** rather than a standard artifact shared across the broader Android Windows-emulation ecosystem.

### Best current provenance read

The most defensible current story is:

1. **GLIBC redirect support started first**, under the older preload identity `libpluviagoldberg.so`.
2. That GLIBC preload was later **renamed** to `libredirect.so` in app-side launcher code.
3. **Bionic redirect support arrived later**, first as loose xhook-based JNI libraries on `upstream/new_vortek`.
4. Before bionic landed on mainline, those loose bionic libs were replaced by a **smaller packed bionic redirect shim** and bundled together with the GLIBC shim inside `redirect.tzst`.
5. The bundle appears to have been **hand-assembled locally** and then committed as a binary asset.
6. I still do **not** have a public source repo for either packed redirect shim.

### Feasibility implications for GN-Lime

#### What this means in practice

- `redirect.tzst` is still the biggest **opaque binary** in the package-rename story.
- It does **not** look like something we can simply pull fresh from mainstream upstream Winlator / Pluvia / MiceWine.
- The current bundle carries legacy hardcoded package lineage internally.

#### Best current options

1. **Bionic-first and minimize dependence on redirect where possible**
   - this still looks like the best route for a first `GN-Lime` build
2. **Treat GLIBC redirect behavior as unstable until proven otherwise**
   - because the GLIBC shim is clearly old, opaque, and package-bound
3. **Either find or replace the redirect layer**
   - public source discovery
   - binary reverse-mapping / reimplementation
   - or enough runtime cleanup that the redirect layer is no longer needed for key paths

#### Updated confidence statement

- **GN-Lime remains feasible** as a source-built side-by-side fork
- **Bionic remains the right first target**
- **GLIBC remains blocked partly by `wine-custom`, partly by `box64`, and partly by the opaque `redirect.tzst` layer**

---

## Follow-up: PR #191 review (`Initial bionic changes`) (2026-04-10)

User pointed me at:
- PR: `https://github.com/utkarshdalal/GameNative/pull/191`
- example commit diffs inside the PR:
  - `8dd63e4c575d07eeb44c98ded5a96d0e222b8879`
  - `b2a32907c6fe75342814deab6c8e4a687db923ba`

I reviewed the PR commit list and relevant patches.

### PR structure

PR #191:
- title: `Initial bionic changes`
- head branch: `add-bionic-containers`
- squash-merged as:
  - `20ebeaf06594fa86aa85450693a230a1edc2129b`
- commit count on the PR branch: `27`

### Most important result from reviewing the PR branch

I do **not** see evidence that PR #191 ever carried the missing redirect shim source files in-tree.

In particular, I did **not** find source files like:
- `preload_replace.c`
- `preload_replace_bionic.c`
- a checked-in source directory for `libredirect.so`
- a checked-in source directory for `libredirect-bionic.so`

What the PR branch does show is:
- launcher wiring for preloading redirect libs
- asset extraction logic for `redirect.tzst`
- but **not** the source code that produced those binaries

### What the specific PR commits do

#### `8dd63e4c` — early bionic/imagefs plumbing, not redirect source

This commit mostly does variant/imagefs prep work:
- `ImageFsInstaller.java`
  - makes imagefs install variant-aware (`imagefs_gamenative.txz` vs `imagefs_bionic.txz`)
  - adds `installWineFromAssets(...)`
  - writes variant metadata via `imageFs.createVariantFile(containerVariant)`
- `BionicProgramLauncherComponent.java`
  - starts using `box64-<version>-bionic.tzst`
  - temporarily comments out adding `evshim` to `LD_PRELOAD`
- `GlibcProgramLauncherComponent.java`
  - simplifies to `extractBox64Files()`
  - removes `box86` handling from this path

Important takeaway:
- `8dd63e4c` is **not** where `redirect.tzst` appears
- it does **not** reveal hidden redirect source

#### `b2a32907` — container switching / boot fixes, not redirect source

This commit only lightly touches the relevant files:
- `ImageFsInstaller.java`
  - uses `bionic_wine_entries`
  - fixes `containerVariant.equals(Container.GLIBC)` style checks
- `XServerScreen.kt`
  - variant-related cleanup and comparisons

Important takeaway:
- `b2a32907` also does **not** reveal redirect source or hidden path-rewrite implementation

#### `451ca4a1` — the actual `redirect.tzst` introduction point inside PR #191

This is the first PR #191 commit where the redirect bundle itself appears.

It adds:
- `app/src/main/assets/redirect.tzst`
- `ImageFsInstaller.installGuestLibs(...)`
  - extracts `redirect.tzst` into `imagefs`
  - chmods:
    - `usr/lib/libredirect.so`
    - `usr/lib/libredirect-bionic.so`

Important takeaway:
- inside PR #191, the redirect layer enters as a **binary bundle import**, not as source

### A useful transient PR commit: `9310b59c`

GitHub still exposes a PR-branch commit:
- `9310b59c550fc3ebe110a74bcd6ed5964c974f3f`
- message:
  - `removed hardcoded com.winlator.cmod`

From the patch, this commit only touched:
- `app/src/main/java/com/winlator/container/Container.java`

It replaced `com.winlator.cmod` hardcoded mediaconv paths with `app.gamenative` ones.

So this is a real transient hardcoding-cleanup commit on the PR branch, but:
- it is **not** redirect shim source
- it is just another sign that the bionic work was migrating hardcoded Winlator CMOD paths over to GameNative paths during PR development

### What PR #191 does **not** contain

As far as I can tell from the reviewed commit list and patches, PR #191 does **not** contain the transient startup-hook implementation I found elsewhere:
- no `NativeHooks` startup loader in `PluviaApp.kt`
- no `System.loadLibrary("redirect_logging-bionic")`
- no `NativeHooks.init()` app-start hook

That startup hook belongs to:
- `2df2b427` on `upstream/new_vortek`

So the strongest transient source-ish clue for bionic redirect handling still lives **outside PR #191**, on the earlier `new_vortek` branch.

### Bottom line from PR #191 review

PR #191 helps pin this down more clearly:

1. The PR shows **when** the redirect bundle got imported into the bionic line.
2. The PR shows **how** GameNative started extracting and preloading those libs.
3. But the PR still does **not** reveal the source code that built the redirect binaries.
4. The only clearly transient redirect-related implementation detail that looks source-adjacent is still the older `upstream/new_vortek` path:
   - loose `libredirect-bionic.so`
   - loose `libredirect_logging-bionic.so`
   - `NativeHooks` startup loader in `PluviaApp.kt`

So if the goal is to pin down the hardcoding/source origin as tightly as possible:
- **PR #191 is useful for the import/wiring timeline**
- **`upstream/new_vortek` remains the most important transient branch for the bionic redirect implementation itself**
