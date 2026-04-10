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
