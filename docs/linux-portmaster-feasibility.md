# Linux/PortMaster Support Feasibility (Research Branch)

Date: 2026-02-22  
Branch: `research/linux-portmaster-feasibility` (from `master`)

## Executive summary

- Technical feasibility: **possible**, but high integration risk if implemented as a first-class in-app feature.
- Upstream merge likelihood: **low under current stated direction** (maintainer comment in provided discussion says no plans for Linux games right now).
- Recommended path: **experimental fork/spike first**, behind a feature flag, with strict isolation from the current bionic-first Windows path.

## What exists in the codebase today

The runtime is not currently designed as a generic "any Linux app launcher". It is primarily a Wine/Proton pipeline with variant handling.

- Launcher selection is variant-based (`glibc` or `bionic`) in `XServerScreen.kt`.
  - `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt:1814`
- Container model stores `containerVariant` and variant constants.
  - `app/src/main/java/com/winlator/container/Container.java:64`
  - `app/src/main/java/com/winlator/container/Container.java:668`
- Base guest launcher still contains a proot command path (`libproot.so ... --rootfs ...`).
  - `app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java:257`
- Active bionic/glibc launcher implementations execute direct commands (no base proot path in normal flow).
  - `app/src/main/java/com/winlator/xenvironment/components/GlibcProgramLauncherComponent.java:172`
  - `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java:339`
- Native build currently comments out proot subdirectory.
  - `app/src/main/cpp/CMakeLists.txt:7`
- Proot shared objects are present only in `app/src/main/jniLibs/armeabi-v7a` in this snapshot (no corresponding `app/src/main/jniLibs/arm64-v8a/libproot*.so` files found).
- Packaging/defaults/content pipeline are bionic-first:
  - Defaults force bionic in `ContainerUtils`.
    - `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:44`
  - Wine/Proton import rejects glibc binaries.
    - `app/src/main/java/app/gamenative/ui/screen/settings/WineProtonManagerDialog.kt:296`
  - `manifest.json` has bionic variants only (no glibc entries found).
    - `manifest.json`
- `ubuntufs` dynamic feature exists but is effectively placeholder (`hasCode="false"`).
  - `app/build.gradle.kts:179`
  - `ubuntufs/src/main/AndroidManifest.xml:5`

## Feasibility assessment

### 1. Technical feasibility (can it be built?)

**Yes**, in a fork, using one of these approaches:

- Proot-based Linux source (closest to your PoC): Android -> rootfs -> box64/fex -> Linux game binary.
- Hybrid approach: reuse existing runtime pieces where possible (audio/input/X stack), add Linux-specific launcher path for PortMaster payloads.

Key caveat: current code and content pipeline are tightly optimized for Wine/Proton flows. Linux game support requires new lifecycle and compatibility logic, not just a new executable command.

### 2. Product feasibility (can it be maintainable?)

**Medium-low without strict scoping**.

- PortMaster content is heterogeneous and often expects distro-like assumptions.
- Android constraints (SELinux, fs, ptrace/syscalls, audio stack differences) increase long-tail breakage.
- Support burden likely rises quickly unless there is a curated compatibility list and clear "experimental" UX framing.

### 3. Upstream feasibility (chance to merge)

Based on the provided maintainer response ("no plans right now to support linux games"), **merge probability is currently low** unless:

- Feature remains optional/hidden.
- Does not regress current game flows.
- Maintenance ownership and support policy are explicit.

## Scope of implementation

### Runtime/backend scope

Minimum work items:

- Add a Linux game launch path distinct from Wine/Proton startup.
- Define rootfs strategy (prebuilt image, patching, updates, integrity checks).
- Package/install PortMaster and dependency chain in an app-compatible structure.
- Build/route process env vars for display, audio, input, and storage binds.
- Add robust process supervision and crash capture for non-Wine binaries.

Likely new components:

- `LinuxProgramLauncherComponent` (or similar) with explicit mode selection.
- `PortMasterService` for install/index/launch metadata.
- Compatibility layer for controller and audio defaults.

### Content/distribution scope

- New manifest content types for:
  - Linux runtime image(s)
  - PortMaster base package
  - Optional dependency bundles
- Download/install validation and rollback logic.
- Storage sizing strategy (runtime + ports can grow quickly).

### Data model scope

- New source/type in app model (e.g., `GameSource.PORTMASTER`).
- Container profile fields specific to Linux game launches:
  - runtime mode (proot/native)
  - display mode
  - audio backend
  - controller profile mapping
- Migration for existing containers/settings without side effects.

### UI/UX scope (required, not optional)

To keep support load manageable, UI must clearly separate Linux support from regular flows.

Core UX surfaces:

- Library/source onboarding:
  - Add "Linux/PortMaster (Experimental)" source entry
  - Show prerequisites and expected limitations before enabling
- Runtime setup screens:
  - guided install for runtime + PortMaster
  - disk usage estimate and progress
- Container editor:
  - variant/runtime controls must avoid conflicting with bionic Wine defaults
  - hide irrelevant Wine-only controls in Linux mode
- Launch and compatibility UX:
  - per-port status tags (`Works`, `Audio issues`, `Needs keyboard`, `Untested`)
  - first-run hints for control mapping and audio routing
- Diagnostics UX:
  - one-tap log bundle export (launcher logs + stderr + selected runtime metadata)
  - actionable error messages instead of generic launch failure dialogs

Without these UX changes, triage volume will likely outweigh engineering throughput.

## Effort estimate (single experienced engineer baseline)

Assumes existing app familiarity and no major architecture rewrite.

- Phase 0: Research spike (2-3 weeks)
  - Hidden prototype launch path for 2-3 known ports
  - Validate audio, input, and stability envelope
- Phase 1: Experimental feature in fork (6-10 weeks)
  - Basic source onboarding, runtime install flow, per-port launch
  - Minimal compatibility UI + diagnostics
- Phase 2: Merge-ready candidate (12-20+ weeks)
  - Hardened lifecycle, migration, error handling, localization, docs, QA matrix
  - Performance and supportability tuning across devices

## Major risks

- Audio path instability across device vendors and Android versions.
- Input/controller inconsistencies for SDL/non-SDL ports.
- Performance regression due to translation + nested runtime overhead.
- Runtime image size and update complexity.
- Increased support load from edge-case Linux dependencies.
- Architectural drift from project's current bionic-first positioning.

## Recommended implementation strategy

1. Keep this as a forked experimental track first.
2. Gate with a feature flag and explicit "Experimental" UX.
3. Define a narrow compatibility target (small curated port list).
4. Build automated smoke checks for runtime install and launch.
5. Reassess upstream proposal only after proving:
   - no regressions to existing flows
   - reproducible wins on at least several device classes
   - sustainable maintenance ownership

## Suggested go/no-go criteria

Proceed past spike only if all are true:

- 3-5 representative ports launch consistently across at least 2 device classes.
- Audio and controller input are reliable on those ports.
- Crash rate and startup times are within acceptable bounds for "experimental" release.
- Support/diagnostic tooling is sufficient for remote triage.

If these are not met, keep Linux/PortMaster support out of mainline and continue as fork-only.
