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
3. Before `redirect.tzst` ever existed, multiple GameNative branches were already assuming `libredirect.so` existed at runtime:
   - `upstream/pull-in-pluvia-changes`
   - `upstream/fix-pathing-issue`
   - `upstream/new_vortek_winlator`
   - `upstream/new_vortek`
4. Since those branches predate `redirect.tzst`, the most likely explanation is that **GLIBC `libredirect.so` originally came from an imported imagefs/rootfs lineage**, not from a source file committed in GameNative.
5. **Bionic redirect support arrived later**, first as loose xhook-based JNI libraries on `upstream/new_vortek`.
6. Before bionic landed on mainline, those loose bionic libs were replaced by a **smaller packed bionic redirect shim** and bundled together with the GLIBC shim inside `redirect.tzst`.
7. The bundle appears to have been **hand-assembled locally** and then committed as a binary asset.
8. I still do **not** have a public source repo for either packed redirect shim.

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

---

## Follow-up: checking `longjunyu2/winlator`, `longjunyu2/weston`, `ajay9634/winlator-ajay`, and `termux-pacman/glibc-packages` (2026-04-10)

User suspected the redirect or related hardcoding source might live in:
- `longjunyu2/winlator`
- `longjunyu2/weston`
- `ajay9634/winlator-ajay`
- `termux-pacman/glibc-packages`

I checked all four.

### `longjunyu2/winlator`

This one was especially useful.

#### Current code / history result

I checked current tree plus file history via GitHub API.

Important commit history:
- `d80353d3` (2024-08-03) — `add glibc env support`
  - adds `GlibcProgramLauncherComponent.java`
- `7f5887dd` (2024-08-15)
  - updates `imagefs.txz` and `imagefs_patches.tzst`
- `5030bad4` (2024-08-29)
  - updates `imagefs.txz` again

#### Critical finding: longjunyu's GLIBC path does **not** use redirect libs in code

In the `d80353d3` launcher patch, `GlibcProgramLauncherComponent.java` sets only:
- `LD_PRELOAD = "libandroid-sysvshm.so"`

I did **not** find:
- `libredirect.so`
- `libredirect-bionic.so`
- `libpluviagoldberg.so`
- `preload_replace.c`
- `preload_replace_bionic.c`

in the longjunyu codebase or its visible file history.

#### I downloaded and inspected the actual longjunyu imagefs assets

Using the raw/media GitHub URLs for commit `d80353d3`, I downloaded:
- `app/src/main/assets/imagefs.txz` (~124 MB)
- `app/src/main/assets/imagefs_patches.tzst` (~3.5 MB)

Results:

##### `imagefs.txz`
- contains lots of hardcoded `/data/data/com.winlator/files/imagefs/...` strings
- contains package-bound interpreter and library paths
- **does not contain**:
  - `libredirect`
  - `pluviagoldberg`
  - `preload_replace`
  - `old_pkg`
  - `new_pkg`
- tar listing also shows **no redirect-related file names**

##### `imagefs_patches.tzst`
- contains:
  - `usr/lib/libandroid-sysvshm.so`
  - ALSA helper
  - cursors
  - fonts
  - `winhandler.exe`
  - `wfm.exe`
- **does not contain** any redirect libs or related strings

#### What this means

`longjunyu2/winlator` looks like a strong source for the **older package-bound imagefs lineage** used in early GLIBC work, but **not** for the missing redirect shim source.

Best current read:
- longjunyu provided an imagefs/rootfs that was already hardcoded to `com.winlator`
- GameNative later layered its own redirect/preload approach on top of that kind of lineage
- but the actual `libredirect*` binaries do **not** appear to come directly from the visible longjunyu app repo or the inspected longjunyu imagefs assets

### `longjunyu2/weston`

I searched the repo for:
- `libredirect`
- `pluviagoldberg`
- `com.winlator`
- `/data/data/`

Result:
- no useful hits related to the redirect shim

So `weston` does not currently look like the origin for this layer.

### `ajay9634/winlator-ajay`

This fork also appears to be in the longjunyu/older-Winlator family.

Findings:
- current code has no `libredirect*` or `pluviagoldberg` references
- `GuestProgramLauncherComponent.java` only uses:
  - `LD_PRELOAD = "libandroid-sysvshm.so"`
- the current `imagefs_patches.tzst` is accessible and contains no redirect libs

So this fork also points away from being the source of the missing redirect binaries.

### `termux-pacman/glibc-packages`

This repo does **not** contain the GameNative redirect libs either.

I did **not** find:
- `libredirect.so`
- `libredirect-bionic.so`
- `pluviagoldberg`
- `preload_replace.c`
- `preload_replace_bionic.c`

However, it *is* very relevant architecturally.

#### Why it matters

This repo shows a cleaner source-level strategy for solving similar pathing problems:
- patching glibc / box64 / mesa / xtrans / pulseaudio / glib paths at build time
- using prefix macros like:
  - `@TERMUX_PREFIX@`
  - `@TERMUX_PREFIX_CLASSICAL@`
- and in the CGCT pieces, even environment-driven prefix logic such as:
  - `CGCT_APP_PREFIX`
  - `CGCT_DEFAULT_PREFIX`

Examples I found:
- `gpkg/glibc/set-dirs.patch`
  - patches `ld.so.preload` path to `@TERMUX_PREFIX@/etc/ld.so.preload`
- `gpkg/box64/setdirs.patch`
  - patches install paths and library lookup dirs
- `gpkg/mesa/virgl-socket-path.patch`
  - patches virgl socket path under a prefix-aware tmp dir
- `gpkg/xtrans/xtrans-1.4.0_Xtranssock.c.patch`
  - patches X11 socket paths to prefix-aware tmp dirs
- `gpkg/libpulse/fix-paths.patch`
  - patches machine-id and temp/runtime locations
- `cgct/cgt/cgct-app-prefix.cc`
  - supports a configurable app prefix via `CGCT_APP_PREFIX`

#### Why this is useful for GN-Lime

This repo does **not** solve the mystery of `redirect.tzst`, but it does suggest a better long-term architecture:
- replace opaque preload path-rewrite hacks with **source-level prefix-aware builds** wherever possible
- especially for glibc-side components

### Updated interpretation after checking these repos

The evidence now points more strongly to this picture:

1. `longjunyu2/winlator` is a likely ancestor for the **hardcoded package-bound imagefs/rootfs style**.
2. But it does **not** appear to be the source of the specific `libredirect*` binaries used by GameNative.
3. `ajay9634/winlator-ajay` behaves similarly and also does not surface the redirect layer.
4. `longjunyu2/weston` does not currently look relevant to redirect provenance.
5. `termux-pacman/glibc-packages` is not the redirect source either, but it is a strong **design reference** for how to make glibc pathing configurable without opaque shims.

### Practical takeaway

This makes the redirect provenance story narrower:
- the **underlying path hardcoding lineage** may well trace back through older Winlator / longjunyu imagefs roots
- but the **actual redirect shim binaries** still look like a later, more local layer added in the GameNative/PluviaGoldberg-derived stream

So the missing source is still most likely to be found in one of these places:
- an older/private/local build workspace that never got published
- a binary assembled from unpublished C sources during the `new_vortek` / bionic experimentation phase
- or some imported rootfs/imagefs artifact outside the visible app repos we checked

---

## Comprehensive asset binary analysis (2026-04-10)

Full download and inspection of every unique binary asset blob across all of GameNative history (plus live imagefs downloads). This section resolves the patching feasibility question completely.

### Method

Enumerated all unique git blob SHAs for:
- `container_pattern*.tzst` (all historical versions)
- `imagefs_patches*.tzst` (all versions)
- `box64-*.tzst` (GLIBC and bionic)
- `wrapper*.tzst` (all versions)
- `graphics_driver/*.tzst` (all versions)
- `redirect.tzst`
- `pulseaudio-gamenative.tzst`
- `extra_libs.tzst`

Also downloaded the live runtime imagefs files:
- `https://downloads.gamenative.app/imagefs_gamenative.txz` (158 MB GLIBC imagefs)
- `https://downloads.gamenative.app/imagefs_bionic.txz` (175 MB bionic imagefs)

### Key finding: `libredirect.so` internals finally mapped

**`usr/lib/libredirect.so`** (from `redirect.tzst` AND inside `imagefs_gamenative.txz`):

This is the GLIBC-side LD_PRELOAD path-rewrite shim. Its function:
- Intercepts `openat`, `openat64`, `openat2`, `fstatat`, `fstatat64` syscalls
- Rewrites any path containing `com.winlator/files/rootfs` → `app.gamenative/files/imagefs`
- Uses `preload_loaded.txt` sentinel to avoid double-loading
- Loads itself via `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so`
  (because this lib was previously named `libpluviagoldberg.so`)
- Has init symbol `pluviagoldberg_on_load` — confirms the naming lineage

Hardcoded strings (all in `.rodata`):
| Offset | String | Slot |
|--------|--------|------|
| 0x6780 | `com.winlator` | 17 bytes |
| 0x6790 | `app.gamenative` | 17 bytes |
| 0x67a0 | `com.winlator/files/rootfs` | 33 bytes |
| 0x67c0 | `app.gamenative/files/imagefs` | 33 bytes |
| 0x68d8 | `/data/data/app.gamenative/files/imagefs/usr/tmp` | 49 bytes |
| 0x6b88 | `/data/data/app.gamenative/files/imagefs/preload_loaded.txt` | 65 bytes |
| 0x80d8 | `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so` | 73 bytes |
| 0x8120 | same as above | 73 bytes |
| 0x8168 | same as above | 73 bytes |

All patch to `app.gnlime` with ≥4 bytes slack.

**`usr/lib/libredirect-bionic.so`** (from `redirect.tzst`):

This is the Android-bionic-side LD_PRELOAD path-rewrite shim. Its function:
- Intercepts `openat`, `fstatat`, `ioctl` syscalls
- Rewrites any path: `old_pkg` → `new_pkg`
- Current values: `old_pkg = com.winlator.cmod` (17 chars), `new_pkg = app.gamenative` (14 chars)
- Symbol exports: `old_pkg` and `new_pkg` are the configurable string slots
- Debug strings: `rewrite (openat): %s -> %s`, `rewrite (ioctl): %s -> %s`, etc.

Hardcoded strings:
| Offset | String | Slot |
|--------|--------|------|
| 0x8ab | `com.winlator.cmod` | 19 bytes |
| 0x949 | `app.gamenative` | 16 bytes |

To patch for GN-Lime: replace `app.gamenative` → `app.gnlime` at 0x949.
The `old_pkg = com.winlator.cmod` stays unchanged because the bionic wine binaries
still have `com.winlator.cmod` hardcoded as their original source package path.

### Key finding: box64 GLIBC PT_INTERP confirmed

All three current GLIBC box64 builds have:

| File | PT_INTERP offset | Slot size | Content |
|------|-----------------|-----------|---------|
| `box64-0.3.4.tzst` | `0x2a8` | 70 bytes | `/data/data/app.gamenative/files/imagefs/usr/lib/ld-linux-aarch64.so.1` |
| `box64-0.3.6.tzst` | `0x2a8` | 70 bytes | same |
| `box64-0.3.8.tzst` | `0x2a8` | 70 bytes | same |

With `app.gnlime` (10 chars vs 14 chars → 4 chars shorter):
- New PT_INTERP = `/data/data/app.gnlime/files/imagefs/usr/lib/ld-linux-aarch64.so.1` (65 chars + null = 66 bytes)
- Fits in 70-byte slot with 4 bytes slack
- Simple in-place binary patch, no `patchelf` needed

Bionic box64 builds (`box64-0.3.x-bionic.tzst`) use only `com.termux` paths — no `app.gamenative` references. No patching needed.

### Key finding: wrapper libs use `com.winlator.cmod` NOT `app.gamenative`

All wrapper versions (`wrapper-leegao.tzst`, `wrapper-v2.tzst`, `wrapper.tzst`, etc.) have:
- `com.winlator.cmod/files/imagefs/usr/lib` hardcoded in `libvulkan_wrapper.so` RPATH
- `com.termux` paths throughout

No `app.gamenative` references in any wrapper. The wrappers were compiled against the cmod package.
For GN-Lime, the bionic redirect shim (`libredirect-bionic.so`) handles the `com.winlator.cmod` → `app.gnlime` rewrite at runtime, so wrapper libs do NOT need patching.

### Key finding: container patterns have no hardcoded paths

All `container_pattern*.tzst` versions (earliest Winlator through bionic) contain only Windows filesystem structure (`.wine/`, fonts, DLLs, registry). No `/data/data/` path strings found.

### Key finding: live imagefs analysis

**GLIBC imagefs (`imagefs_gamenative.txz`, 158 MB):**
- Contains `opt/wine/bin/wineserver` with `com.winlator/files/rootfs` paths
- Contains `usr/bin/aserver` with `com.winlator/files/imagefs/usr/lib` RPATH
- Contains `usr/lib/libredirect.so` — this is the SAME binary as in `redirect.tzst`
  (the APK asset `redirect.tzst` is extracted AFTER the imagefs and overwrites this file)
- **Conclusion**: patching `redirect.tzst` in the APK is sufficient; no server-side imagefs patching needed

**Bionic imagefs (`imagefs_bionic.txz`, 175 MB):**
- Contains `usr/bin/aserver` (Android native, `/system/bin/linker64`) with `com.winlator` RPATH
- Contains `usr/bin/cacaserver` (Android native) with `com.termux` and `com.winlator` paths
- Does NOT contain `app.gamenative` paths
- **Conclusion**: bionic redirect shim handles runtime rewriting; these files are not critical

### Complete binary patch map for GN-Lime (`app.gnlime`)

#### APK assets — patchable via `tools/lime-asset-patcher/patch_assets.py`

| Asset | Patch count | What changes |
|-------|-------------|--------------|
| `box86_64/box64-0.3.4.tzst` | 1 | PT_INTERP `app.gamenative` → `app.gnlime` |
| `box86_64/box64-0.3.6.tzst` | 1 | same |
| `box86_64/box64-0.3.8.tzst` | 1 | same |
| `redirect.tzst` | 8 | `libredirect.so` × 7, `libredirect-bionic.so` × 1 |
| `graphics_driver/vortek-2.0.tzst` | 2 | ELF + ICD JSON |
| `graphics_driver/vortek-2.1.tzst` | 2 | ELF + ICD JSON |
| `graphics_driver/turnip-25.2.0.tzst` | 1 | ICD JSON |
| `graphics_driver/turnip-25.3.0.tzst` | 1 | ICD JSON |

**Total: 17 patches via the asset patcher tool**

#### Source code — manual changes

| File | Change needed |
|------|--------------|
| `app/build.gradle.kts` | `applicationId = "app.gnlime"` |
| `app/src/main/cpp/extras/evshim.c` | `app.gamenative` → `app.gnlime` |
| `app/src/main/AndroidManifest.xml` | `app.gamenative.LAUNCH_GAME` → `app.gnlime.LAUNCH_GAME` |
| `WinHandler.java` | `PACKAGE_NAME` constant |
| `BionicProgramLauncherComponent.java` | imagefs path construction |
| `GlibcProgramLauncherComponent.java` | imagefs path construction |
| `WineUtils.java` | wine path helpers |
| `DXVKHelper.java` | path helpers |
| `Container.java` | container path constants |
| `IntentLaunchManager.kt` | intent action string |
| `ShortcutUtils.kt` | shortcut URI |
| `IconSwitcher.kt` | icon URI |
| `GOGService.kt`, `EpicService.kt`, `AmazonService.kt` | service URIs |

Recommend using `AppPaths.java` abstraction from `origin/feat/package-rename-support` to centralize all path construction rather than individual string edits.

### Architecture diagram (GN-Lime runtime path rewriting)

```
┌─────────────────────────────────────────────────────────┐
│ Android layer (bionic)                                  │
│  wine-bionic, box64-bionic executables                  │
│  have com.winlator.cmod paths hardcoded                 │
│       ↓ preloaded via LD_PRELOAD                        │
│  libredirect-bionic.so                                  │
│    old_pkg = com.winlator.cmod  (unchanged)             │
│    new_pkg = app.gnlime         (patched from           │
│                                  app.gamenative)        │
│  → rewrites com.winlator.cmod → app.gnlime at runtime  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ GLIBC layer (inside imagefs)                            │
│  wineserver, wine binaries have com.winlator paths      │
│       ↓ preloaded via LD_PRELOAD                        │
│  libredirect.so (= libpluviagoldberg.so)                │
│    rewrite: com.winlator/files/rootfs                   │
│          →  app.gnlime/files/imagefs (patched)          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ GLIBC box64                                             │
│  PT_INTERP: /data/data/app.gnlime/files/imagefs/        │
│             usr/lib/ld-linux-aarch64.so.1 (patched)     │
└─────────────────────────────────────────────────────────┘
```

### Remaining unknowns / limitations

1. **wine-bionic direct `app.gamenative` references**: The bionic wine binaries (served from `imagefs_bionic.txz`) may have `app.gamenative` paths hardcoded in wine server/socket code (confirmed in `utkarshdalal/wine-custom` source). The bionic redirect shim currently catches `com.winlator.cmod` → `app.gamenative` only. If wine-bionic has `app.gamenative` direct hardcodes, those would NOT be caught. A source rebuild of wine-bionic targeting `app.gnlime` is the clean fix.

2. **`imagefs_bionic.txz` aserver/cacaserver**: Have `com.winlator` RPATHs. Minor issue — audio may break. Can be patched server-side or replaced.

3. **GLIBC wine direct `app.gamenative` references**: Similar to above but in GLIBC wine. `libredirect.so` only catches `com.winlator` → `app.gnlime`. Any direct `app.gamenative` in wine-GLIBC code would be wrong. Investigation of `utkarshdalal/wine-custom` confirmed `app.gamenative` hits in `server/request.c`, `server/esync.c`, etc. For a correct GLIBC container, wine-custom needs a source rebuild for `app.gnlime`.

4. **Bionic-first is the recommended starting point**: Build bionic wine + bionic box64 with `app.gnlime` from source. The GLIBC path can remain experimental with known caveats.

### Patcher tool

`tools/lime-asset-patcher/patch_assets.py` — see `tools/lime-asset-patcher/README.md`

```bash
# Preview all changes
python3 tools/lime-asset-patcher/patch_assets.py --dry-run --verbose

# Apply
python3 tools/lime-asset-patcher/patch_assets.py
```

---

## VibeNative Imagefs Analysis (2026-04-10)

**Repo**: `https://github.com/Pepelespooder/vibenative-imagefs`
**Author**: Pepelespooder (also maintains a fork of `utkarshdalal/GameNative`, identical to upstream)
**Commit**: `399ec3f` — single commit with all assets

### What VibeNative ships

| File | Size | Purpose |
|------|------|---------|
| `imagefs_vibenative.txz` | 161 MB | GLIBC imagefs (rebuilt wineserver) |
| `imagefs_bionic_vibenative.txz` | 181 MB | Bionic imagefs (adrenotools hooks, NOT rebuilt) |
| `imagefs_patches_vibenative.tzst` | 188 MB | Wineprefix + libredirect.so + evshim + sysvshm + SDL2 |
| `proton-9.0-arm64ec.txz` | 76 MB | Proton ARM64EC wine build (com.winlator.cmod paths) |
| `proton-9.0-x86_64.txz` | 56 MB | Proton x86_64 wine build (com.termux paths) |
| `steam.tzst` | 196 MB | Steam Windows binaries |
| `steam-token.tzst` | 11 KB | Steam token executable |
| `experimental-drm-20260116.tzst` | 14 MB | Experimental DRM/Steam overlay |

### VibeNative's approach: a hybrid of source rebuild + redirect

#### GLIBC layer — wineserver rebuilt from source

VibeNative **rebuilt the GLIBC wineserver from source** with `app.vibenative` hardcoded. Evidence:
- The VN wineserver uses `/data/data/app.vibenative/files/imgs/` paths (NOT `com.winlator`)
- The VN wineserver has **zero** `com.winlator` references
- The VN wineserver uses `/files/imgs/` instead of `/files/imagefs/` — suggesting they changed the base path in their wine source
- File date: `Mar 16 18:17` — rebuilt 2026-03-16

The GLIBC `libredirect.so` (in both patches and imagefs) was binary-patched with a simple `gam` → `vib` byte replacement (confirmed: same 43344 bytes, exactly 7 three-byte changes, turning `app.gamenative` → `app.vibenative`). The redirect still maps `com.winlator/files/rootfs` → `app.vibenative/files/imagefs`.

**KEY DISCREPANCY**: The wineserver uses `/files/imgs/` but the redirect maps to `/files/imagefs/`. This means:
- The VN wineserver bypasses the redirect entirely (no `com.winlator` paths to intercept)
- The wineserver directly writes to `/data/data/app.vibenative/files/imgs/`
- Other GLIBC binaries with `com.winlator` paths still get caught by the redirect
- The app code presumably extracts the imagefs to `/files/imgs/` instead of `/files/imagefs/`

The `evshim.so` was also rebuilt from source (contains `/data/data/app.vibenative/files/imagefs/tmp`).

#### Bionic layer — adrenotools hook system, NOT rebuilt

VibeNative uses a **completely different bionic redirect mechanism** from GameNative:

**GameNative**: `libredirect-bionic.so` (12 KB, custom preload_replace_bionic.c)
- Hooks `openat`, `fstatat`, `ioctl`, `read`
- Has `old_pkg`/`new_pkg` string pair: `com.winlator.cmod` → `app.gamenative`

**VibeNative**: `libfile_redirect_hook.so` + `libhook_impl.so` (adrenotools-based)
- Source: `../subprojects/libadrenotools/src/hook/file_redirect_hook.c`
- Hooks `fopen` only
- Uses XXXX/ZZZZ placeholder pattern (adrenotools convention)
- `init_hook_param` receives the target package path at runtime from the app
- The hook libs themselves have `com.winlator/files/imagefs` hardcoded
- Also includes `libgsl_alloc_hook.so` for GPU memory allocation hooks

**All bionic imagefs binaries still have `com.winlator` paths** — VibeNative did NOT rebuild any bionic components. The adrenotools hook system redirects `fopen` calls from `com.winlator` → target package at runtime.

#### Proton wine builds — not rebuilt

Proton ARM64EC wineserver has `com.winlator.cmod` paths (unchanged).
Proton x86_64 wineserver has `com.termux` paths (unchanged).
These rely on the redirect shim to work.

### Comparison with GN-Lime

| Aspect | GN-Lime (our plan) | VibeNative (actual) |
|--------|--------------------|--------------------|
| GLIBC box64 PT_INTERP | Binary patch in tzst | N/A (separate imagefs) |
| GLIBC libredirect.so | Binary patch in redirect.tzst | Binary patch `gam→vib` in imagefs/patches |
| GLIBC wineserver | Relies on redirect shim | **Rebuilt from source** |
| Bionic redirect | libredirect-bionic.so (binary patch) | adrenotools libfile_redirect_hook + libhook_impl |
| Bionic wine | Needs investigation | **Not rebuilt** (uses fopen hooks) |
| App code changes | applicationId + path constants | **None** (identical fork) |
| Side-by-side? | Yes (different app ID) | **No** (same app ID, replacement only) |

### Critical insights for GN-Lime

1. **VibeNative does NOT do side-by-side** — their GameNative fork is identical to upstream. They only change the imagefs files. The APK still has `applicationId = app.gamenative`. This means VibeNative is a **replacement** install, not a concurrent one.

2. **The `/imgs/` vs `/imagefs/` path change** — VibeNative's wineserver uses `/files/imgs/` instead of `/files/imagefs/`. If the app code is unchanged, this path must come from the imagefs extraction code in the app, which would mean VN patches the APK too (but hasn't pushed those changes). OR they're using a fork that uses a different download URL that serves VN-branded imagefs files.

3. **The adrenotools hook approach** is interesting for bionic — it's a different design than GameNative's `libredirect-bionic.so`. It hooks `fopen` instead of syscall-level `openat`. This is less complete (won't catch direct `openat` calls) but simpler and sourced from a public project (adrenotools).

4. **VibeNative confirms the redirect binary-patching approach works** — they successfully patched `libredirect.so` with a simple `gam` → `vib` replacement. Our more sophisticated `app.gamenative` → `app.gnlime` replacement is even safer because it's shorter.

5. **VibeNative did NOT solve the bionic wine problem** — their bionic imagefs still has `com.winlator` everywhere. They rely on the fopen hook to catch path lookups. This is the same problem space we identified — bionic wine executables (loaded separately from the imagefs) likely have `app.gamenative` paths that the fopen hook might not catch if they use `openat` directly.

6. **Proton wine builds are an additional wrinkle** — VN ships Proton builds with `com.winlator.cmod` paths. These need the redirect shim to work, just like our GLIBC case.

### What this means for our 100% path assessment

VibeNative's real-world example **confirms** several things:
- Binary patching `libredirect.so` works ✅
- The wineserver needs source rebuild for GLIBC ✅ (VN did this)
- The bionic side can use hooks instead of full rebuild ⚠️ (VN used adrenotools fopen hook, untested for our use case)

**But VibeNative also shows us that side-by-side is NOT their goal** — they're a drop-in replacement. For GN-Lime to be truly side-by-side, we DO need the APK-level changes (applicationId, path constants, etc.) that VibeNative avoided.

### Regarding GLIBC handling

VibeNative **does NOT properly handle GLIBC renaming** in the way we need:
1. They rebuilt wineserver from source (good for them, hard for us without build infrastructure)
2. Their redirect still maps to `/files/imagefs/` but their wineserver uses `/files/imgs/` — potential inconsistency
3. They didn't touch the GLIBC box64, vortek, turnip, or other APK-bundled assets (their repo only contains imagefs files)
4. For GN-Lime side-by-side, we need to handle ALL of these, which our patcher does


---

## GameNative Discord Research: Package Rename History (2026-04-10)

Guild: `1378308569287622737`. Searched for `decompil`, `hardcod`, `package name`, `vibenative`, `evshim`, `libredirect`, and related terms.

### Key Discord evidence (chronological)

#### 2026-02-20 — sockmonkey72 (jeremybernstein) — PR #585 `fix: replace hardcoded app ID paths with BuildConfig.APPLICATION_ID`
**Channel**: `#development` (`1386424596449988709`)

> This turns out to be a problem on my Thor — I was able to create the Island, but because I can't install the debug apk directly to it via Android Studio, it doesn't really solve my problem (not screwing up my working install). So I went my previous route, which was to add a suffix to the app's appID. It turns out that this causes other breakage because the appID string is hardcoded in a number of places, including in the imagefs (`evshim`). I was able to get this working locally, though, and I've made a PR for it: https://github.com/utkarshdalal/GameNative/pull/585
>
> The only gotcha is that imagefs needs to be recompiled ... A rebuild of imagefs to use the exported `EVSHIM_DATA_DIR` env var would solve that problem (and eliminate all of the hackery [which is excluded from the PR branch]).

**PR #585 status**: Closed without merging. Superseded by `jb/dev-env` branch. **None of the changes from PR #585 were merged to master.** All hardcoded paths it addressed remain in the codebase.

**raynoxu1337's review comment** on `evshim.c`:
> Lol this is funny, i also tried replacing all the hardcoded strings, there is a branch in my repo for exactly this, but the i thought, why not use a regex or something similar here to get the path, evshim's working path should be in that path anyways, no? Maybe its more convenient with some trivial string parsing here instead of ONE MORE env variable??

**GN-Lime relevance**: PR #585 mapped exactly the same source files we identified. Their solution (BuildConfig.APPLICATION_ID + EVSHIM_DATA_DIR env var) is the clean Java-side fix. We should adopt this approach for our fork.

#### 2026-02-23 — zi3d — On GLIBC + package name feasibility
**Channel**: Thread `1475421160865923306`

> I don't think that's possible *right now*. Glibc will break if you changed pkg name (that's why there's no winlator glibc with custom pkg name), and the controller impl used in Bionic have some hardcoded paths (can get fixed)

**Context**: Discussion about Antutu/Ludashi/PUBG package name variants for OEM governor tricks.

**GN-Lime relevance**: zi3d confirms our finding — GLIBC rename is the hard part, bionic controller paths are fixable. Our analysis shows GLIBC is feasible with redirect-layer patches + box64 PT_INTERP patching.

#### 2026-03-02 — ribbit_68832 — Package rename attempt
**Channel**: `#development` (`1386424596449988709`)

> How could one go about modifying imagefs? All I see is its download link but does it have a repo or something?

> Remove any static reference to 'app.gamenative' so variant package name can be added

> I marked that PR as a draft, it wont work properly unless I swap out all hardcoded references to app.gamenative

> its more complicated than that, theres dependancies or something with references

> so atm best I can do is get games to launch but controls wont work lol

> funny enough cursor shows up on screen and it works

**Utkarsh's response**:
> that's not in the imagefs file, and i don't want to do that

> it's hardcoded in the wine and sdl files

> i don't want to do this yet while we figure out how to deal with forks like Max's. I'm adding extra security layers so they can't simply make a new namespace and piggy back off our paid infra.

**GN-Lime relevance**: Utkarsh explicitly acknowledges the wine/SDL hardcoding problem. His reluctance is strategic (fork control), not technical impossibility. Ribbit confirmed games launch but controls break — exactly our "evshim gamepad.mem path" finding.

#### 2026-03-06 — the412banner — APK editor approach
**Channel**: Thread `1479449383027212451`

> I think it was CMOD winlator that had everything hard coded in where if you try to open a APK editor and change the package name it would break the controller

> So you ultimately want to make it so you could just open the APK editor and change the package name rather than having to re-harcode it?

> The new ludashi changed gears where things are no longer hard coded in like Game hub and I could just whip out an APK editor, change the package name and everything work as is originally with the benefits of antutu Ludashi PuBG Genshin etc

**GN-Lime relevance**: Confirms that older Winlator cmod had the same hardcoding problem, and newer Ludashi-based builds relaxed it somewhat. GameNative's situation is harder because of wine/SDL/evshim deep hardcoding.

#### 2026-03-11 — raynoxu — Debug build with env variable
**Channel**: Thread `1481326067858804756`

> ok for the sake of exercise i tried to make it so debug build is separate and made it so evshim would take env variable GAMENATIVE_HOME_DIR_NAME=gamenative.app.debug and put it instead of hardcoded path. And when evshim inits i placed extra log to make sure it my version. My issue is that evshim did not rebuild, what am i missing? do i need to run cmake manually?

**GN-Lime relevance**: Same evshim problem we identified. The env var approach works for the Java side but evshim.c needs to be recompiled into libevshim.so and injected into the imagefs.

#### 2026-03-15 — arkhamantis + pepelespooder — Hardcoding provenance
**Channel**: `#general` (`1412756778159964201`)

**arkhamantis**:
> Those hardcoded values were done by Coffincolors for Cmod 13, which is what GameNative is based on. Utkarsh didn't do it, Coffincolors did.

**pepelespooder**:
> Utkarsh you are a mad man for hardcoding so many values

**GN-Lime relevance**: Confirms the hardcoding lineage traces back to coffincolors/cmod, consistent with our finding of `com.winlator.cmod` paths throughout the wrapper and bionic layers.

#### 2026-03-17 — pepelespooder — Decompiling libredirect for VibeNative GLIBC
**Channel**: `#general` (`1412756778159964201`)

> I got vibenative working properly on glibc

> i had to decompile libredirect

> Like litterally i had to decompile Libredirect just to figure out that it does SOOO MUCH

**GN-Lime relevance**: Pepelespooder is the author of `vibenative-imagefs` that we analyzed. He confirms he decompiled libredirect.so to understand its function and then patched it for VibeNative. Our binary analysis confirmed his approach: simple `gam` → `vib` byte replacement.

#### 2026-03-22 — VibeNative abandoned
**Channel**: `#general`

**pepelespooder**:
> i deleted vibenative

**spacebubble**:
> RIP vibenative 2026-2026

**pepelespooder** (2026-03-28):
> Lol vibenative is dead

> Lol I told anyone using vibenative in a popup dont ask utkarsh for support

**GN-Lime relevance**: VibeNative was a short-lived experiment. The author ran into issues (missing libredirect/libsdl2 in extraction) and abandoned it. The imagefs repo remains public but is not maintained.

#### 2026-04-07 — avalumi — Current state
**Channel**: `#general`

> there is some hardcoding done with certain files and they expect app.gamenative, I don't know which or where to change them

#### 2026-04-10 — omnisoju — The right solution
**Channel**: `#general`

> another solution is to just fix the underlying code so that changing the package name does not influence anything (like controllers right now)

### Summary of Discord findings for GN-Lime

1. **Utkarsh explicitly acknowledges the hardcoding problem** but won't fix it for strategic reasons (fork control, infra protection)
2. **sockmonkey72's PR #585 mapped the exact same files we identified** and proposed `BuildConfig.APPLICATION_ID` + `EVSHIM_DATA_DIR` — this is the clean Java-side fix we should adopt
3. **PR #585 was closed without merging** — all those hardcoded paths remain in master
4. **zi3d confirms GLIBC is the hard part** — consistent with our finding that wine, box64, and the redirect layer all have deep path hardcodes
5. **ribbit_68832 tried the naive approach** (just change package name) and got games to launch but controls broke — exactly the evshim/gamepad.mem path problem
6. **pepelespooder confirmed our binary patching approach works** for libredirect.so via decompilation and byte replacement
7. **VibeNative was abandoned** after hitting libredirect extraction issues and other problems
8. **The community continues to request** package name variants (Antutu, Ludashi, PUBG for OEM governor tricks) but the core team won't prioritize it

### Actionable takeaways for GN-Lime

1. **Adopt sockmonkey72's `BuildConfig.APPLICATION_ID` approach** from PR #585 for Java-side path construction — this is proven and clean
2. **The `EVSHIM_DATA_DIR` env var approach** is the right fix for evshim.c — we just need to rebuild libevshim.so
3. **Our asset patcher already handles the binary layer** that everyone on Discord says is the hard part
4. **We should NOT expect upstream to accept package-rename PRs** — Utkarsh explicitly declined for strategic reasons. GN-Lime should be a fork, not a PR.


---

## Resolution of remaining unknowns (2026-04-10)

### The bionic wine socket/esync question — RESOLVED ✅

**Previous concern**: Wine-bionic executables might have `app.gamenative` hardcoded in socket/esync paths (based on `wine-custom` source code inspection of `server/request.c`, `server/esync.c`, `dlls/ntdll/unix/server.c`).

**Resolution**: Downloaded and scanned the ACTUAL compiled binaries:

1. **Proton ARM64EC wineserver** (`proton-9.0-arm64ec.txz` from `downloads.gamenative.app`):
   - Has `com.winlator.cmod` paths only (NOT `app.gamenative`)
   - Caught by `libredirect-bionic.so` (old_pkg = com.winlator.cmod → new_pkg = app.gnlime)
   - Socket/esync paths use RELATIVE format: `/wine-%lx-esync`, `%s/.wineserver/server-%s`
   - NO `/data/data/` prefix in socket or esync paths

2. **Proton x86_64 wineserver** (`proton-9.0-x86_64.txz`):
   - Has `com.termux` paths (NOT `app.gamenative`)
   - Not our package, no redirect needed

3. **GLIBC wineserver** (`imagefs_gamenative.txz`):
   - Has `com.winlator/files/rootfs` paths (NOT `app.gamenative`)
   - Socket path: `/data/data/com.winlator/files/rootfs/tmp/.wine-%u` → caught by `libredirect.so`
   - Esync: relative `/wine-%lx-esync` (no package prefix)
   - BuildID `5b72efc190d06d72d9a9a13a2bbd0735d957d4fb` — same binary as VibeNative's

4. **GLIBC ntdll.so** (`imagefs_gamenative.txz`):
   - Server socket: `/data/data/com.winlator/files/rootfs/tmp/.wine-%u/server-%s` → caught by `libredirect.so`
   - Esync: `/wine-%lx-esync` (relative, no prefix)

**Key insight**: The `wine-custom` source code has `app.gamenative` in `server/request.c` etc., but the COMPILED binaries in the distributed imagefs have `com.winlator` paths. This means either:
- The binaries were compiled from different source (pre-rename)
- Or there's a build-time transformation step
- The VibeNative binary diff confirms: same BuildID, different string content (com.winlator → app.vibenative via byte replacement)

**Bottom line**: The compiled wine binaries NEVER have `app.gamenative` in socket/esync paths. They use `com.winlator` (GLIBC) or `com.winlator.cmod` (bionic Proton), both of which are caught by the appropriate redirect shim.

### The GLIBC ld-linux/libc compiled-in defaults — ACCEPTABLE RISK ✅

`ld-linux-aarch64.so.1` and `libc.so.6` in the GLIBC imagefs have `app.gamenative` compiled in as default paths for:
- Dynamic linker cache: `/data/data/app.gamenative/files/imagefs/usr/etc/ld.so.cache`
- Library search: `/data/data/app.gamenative/files/imagefs/usr/lib/`
- Gconv modules: `/data/data/app.gamenative/files/imagefs/usr/lib/gconv`
- Locale: `/data/data/app.gamenative/files/imagefs/usr/share/locale`

These are **fallback defaults** that only matter if no explicit paths are set. In GameNative's runtime:
- No `ld.so.cache` exists in the imagefs (confirmed)
- `LD_LIBRARY_PATH` is set explicitly by the launcher components
- `PT_INTERP` is patched to the correct `app.gnlime` path (Phase 1)
- Games rarely need gconv/locale from the system fallback path

**Verdict**: Acceptable risk. If encoding issues surface in testing, a targeted workaround can be added.

