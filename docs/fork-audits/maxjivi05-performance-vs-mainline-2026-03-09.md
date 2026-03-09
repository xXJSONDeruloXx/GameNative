# maxjivi05/GameNative-Performance vs proper GameNative audit

Date: 2026-03-09  
Author: pi coding agent  
Main repo: `/Users/danhimebauch/Developer/GameNative`  
Fork repo: `/Users/danhimebauch/Developer/GameNative-Performance` (`https://github.com/maxjivi05/GameNative-Performance`)

## Working note

Per request, this file is being updated continuously during analysis and committed progressively so context is not lost.

## Baseline / refs

- local `master`: `c37289a5`
- `upstream/master`: `cf29ca74`
- performance fork `main`: `4c0d0ea1`
- merge-base of local `master` and `perfork/main`: `2ed81c4491561a411b409e472f23855cf5c175dc`

### Divergence snapshot

- `master...perfork/main`: `87 left / 63 right`
- `upstream/master...perfork/main`: `97 left / 63 right`

So this fork is **not just “ahead”**; it is a mixed divergence:

- it adds a meaningful set of custom performance / UX / container-management changes
- but it is also missing a lot of newer upstream/mainline work

## Analysis journal

### 2026-03-09 14:16

Set up comparison remotes/repos and switched GameNative worktree to branch:

- `analysis/maxjivi05-performance-audit`

Confirmed the previous unrelated unbelievableflavour audit file was removed so this branch only tracks the correct comparison target.

### 2026-03-09 14:18

Initial commit scan of performance fork indicates these likely major buckets to audit in detail:

1. Master containers / shared container model
2. Dynamic per-game drive mounting and executable isolation
3. Components manager overhaul and variant filtering
4. ImageFs install robustness changes
5. ALSA / audio-engine changes
6. In-game navigation/menu persistence and controller-first UI
7. Custom image and save management features
8. Performance tuner / root and non-root performance mode
9. Surface format and graphics settings additions
10. Repo hygiene / risks / missing upstream fixes

### 2026-03-09 14:20

Important early finding: the performance fork includes a lot of **repo pollution / non-portable baggage** in its history and tree, including things like:

- committed `.gradle/` artifacts
- committed `.cxx/` build outputs
- `local.properties`
- `keystore`
- logs and debug files in history
- vendored `OpenXR-SDK` dump in-tree
- bundled jars and other heavy artifacts
- large engine/vendor customizations (e.g. Ludashi/OpenXR-oriented changes) mixed directly into app/runtime code

This does **not** automatically make the runtime changes bad, but it is a strong signal that any adoption into proper GameNative must be done as **selective extraction**, never as a merge.

## Provisional conclusion so far

This fork should be treated as a **feature quarry**, not a merge target.

## Current findings checkpoint

### 2026-03-09 14:34

Correction after verifying the **actual current GameNative tree** instead of just diff path names: my earlier overlap assumption was too generous.

Current local `master` **does not** currently contain fork-only files/features such as:

- `PrefManager.masterContainers` / `gameContainers` / `appSpecificConfigs`
- `ManageContainersDialog`
- `GameNavigationMenu`
- `CustomImageDialog`
- `SaveManager`
- `FileExplorerDialog`
- `DownloadFolderPicker`
- `PerformanceTuner`
- unified `ComponentsManagerDialog`
- `surfaceFormat` handling in container graphics config
- global HUD / native-rendering persistence fields in `PrefManager`

What current mainline **does** already have are some adjacent foundations that the fork builds on or duplicates differently:

- `ContainerData.executablePath`
- general `ContainerUtils` launch/container plumbing
- `QuickMenu` in the in-game overlay
- `CustomGameFolderPicker`
- `SteamGridDB` fetch support
- `GameMetadataManager`
- launch-dependency abstractions
- cloud-save platform abstractions
- separate component management dialogs (`ContentsManagerDialog`, `DriverManagerDialog`, `WineProtonManagerDialog`)

So the correct framing is:

- several fork features are **genuinely absent** from current local `master`
- but many of them should still be implemented by extending existing GameNative systems, **not** by copying the fork literally

### 2026-03-09 14:38

After corrected file-level comparison, the fork's meaningfully distinct additions cluster into these buckets:

1. **Master/shared container system**
   - `PrefManager.masterContainers`
   - `PrefManager.gameContainers`
   - `PrefManager.appSpecificConfigs`
   - transient per-game A: drive remounting for shared containers
   - `ManageContainersDialog`
   - `Container.onSaveDataCallback` interception to avoid master-container pollution

2. **Unified / opinionated components manager**
   - new `ComponentsManagerDialog`
   - custom categorization for stable / nightly / gplasync / arm64ec / nvapi / sarek / bionic
   - ad-hoc version parsing and filtering logic

3. **ImageFs install hardening**
   - retry loop during imagefs extraction
   - more forceful root-dir clearing
   - package redirection symlink setup
   - recursive chmod after extraction

4. **Device-specific performance tuning**
   - `PerformanceTuner`
   - root performance mode
   - non-root Adreno clock forcing
   - launcher integration for those toggles

5. **Expanded in-game menu / controller UX**
   - `GameNavigationMenu`
   - expanded `NavigationDialog` actions
   - `XServerScreen` support for pause, HUD toggle, native-rendering toggle, touch transparency, joystick visibility, task manager, etc.

6. **ALSA / audio reflector changes**
   - major `alsa_client.c` rewrite
   - Java-side `ALSAClient` reflector mode and simulated stream path
   - new audio-driver constants including `alsa-reflector`

7. **Custom artwork + save-management UX**
   - `CustomImageDialog`
   - `SaveManager`
   - `GameMetadataManager.customImagePath`
   - artwork stored under `remote_games_metadata/<appId>`

8. **Internal utility/debug UX**
   - `FileExplorerDialog`
   - `DownloadFolderPicker`
   - `surfaceFormat` exposure in graphics/container config

### 2026-03-09 14:42

Updated first-pass recommendation quality by area:

- **Master-container ideas:** promising concept, but invasive and currently bug-prone in the fork
- **Components filtering logic:** worth selectively porting into existing manager UX
- **ImageFs retry / cleanup hardening:** promising and likely portable in pieces
- **Performance tuner:** should **not** be upstreamed as-is
- **ALSA/audio reflector changes:** do **not** adopt wholesale without strong validation
- **Expanded in-game menu actions:** worth partial adoption into current `QuickMenu`, not as a fork import
- **Custom artwork support:** likely worth adding, but with cleaner metadata/storage design
- **Manual save import/export:** potentially worth adding as an optional utility feature
- **Internal file explorer:** probably not suitable for production UI
- **Download folder picker helper:** useful as a refactor/generalization of existing picker helpers
- **Surface format option:** small, plausible feature candidate if validated per-driver

### 2026-03-09 14:45

Another important finding: the fork is missing a lot of newer mainline/upstream work.

Important nuance: most of the items below are present in `upstream/master`, not necessarily in the local `master` snapshot (`c37289a5`) because local `master` is 10 commits behind upstream.

Compared with proper current upstream/mainline, the fork still lacks many already-landed fixes and improvements, including recent upstream-side commits such as:

- `cf29ca74` – listRunningWineProcesses stream close fix
- `1f9018ca` – glibc VirGL library-path fix
- `ce241623` – Vulkan extension enumeration / edit-container crash fix
- `336653b9` – external launch while app is open fix
- `c83c7e21` – Epic language support improvements
- `8154f565` – gen1 GOG language support
- `3ff6ec1d` – diacritic-insensitive search
- `c7bac61e` – SOURCE_TOUCHPAD handling fix
- `a376d3b4` – Steam download resume fix

So even where the fork adds useful ideas, it is still a **worse overall base** than proper current GameNative.

### 2026-03-09 14:48

Current implementation-risk note on the fork's distinct features:

- the fork has a pattern of mixing legitimate runtime ideas with:
  - repo pollution
  - device-specific assumptions
  - bundled artifacts
  - invasive low-level changes
- that means every candidate feature must be split into:
  - **portable concept worth upstreaming**, versus
  - **fork-specific implementation that should not be copied directly**

## Useful fork commit anchors

These are the fork commits that most directly map to the feature buckets below.

- `4c0d0ea1` – global settings persistence + in-game menu restructure
- `44122192` – per-game executable-path and master-container pollution fixes
- `b23731ea` – dynamic game drive mounting for master containers / launcher refinements
- `2285f224` – stricter DXVK/VKD3D variant filtering
- `cd741f60` – nightly/gplasync filtering and version-sorting refinements
- `9beb5601` – ImageFs install robustness / retry logic
- `828c7669` – ALSA UnsatisfiedLinkError fix attempt + component UI feedback
- `39ab6554` – ALSA reflector / “unbreakable audio engine” work
- `e4ddb745` – dual DXVK/VKD3D selection / Proton-swap crash fixes
- `4e4f690a` – “smart save import” and components-manager changes
- `b8fdc8d4` – internal file explorer + robust save-management groundwork
- `55e0e45b` – controller-friendly in-game/container menu redesign

## Detailed audit notes

### 2026-03-09 15:02 — master/shared containers

What the fork adds:

- a shared-container assignment model via:
  - `PrefManager.masterContainers`
  - `PrefManager.gameContainers`
  - `PrefManager.appSpecificConfigs`
- dynamic A: drive remapping per app when a shared container is reused
- a UI for deleting/inspecting container assignments (`ManageContainersDialog`)
- a `Container.onSaveDataCallback` hook so game-specific edits do not overwrite the shared master container's on-disk `config.json`

Why the idea is attractive:

- fewer duplicated containers on disk
- faster setup for large libraries
- cleaner path toward “one Wine/runtime base, many app overlays”

What is wrong with the fork implementation:

- it introduces a **global persistence side-channel** (`onSaveDataCallback`) inside `Container.saveData()`; that is too implicit for upstream-quality code
- it stores per-game overrides as opaque JSON strings in preferences, which is fragile for migration/debugging
- the drive-remounting implementation is inconsistent:
  - one code path correctly rebuilds drives with no separators
  - another path in `getOrCreateContainer()` uses `joinToString(",")`, even though nearby comments explicitly say the drive string format is parsed as contiguous entries like `D:/pathE:/path`
  - so there is a real bug/risk here, not just style disagreement
- the model mixes persistent container state with transient per-launch state

Recommendation: **partial yes, but only as a fresh implementation**.

What proper GameNative should do instead:

- introduce an explicit `ContainerAssignmentRepository` / `SharedContainerRepository`
- treat shared-container state and per-app overlay state as separate persistence domains
- build per-launch drive mappings in memory, without writing them back to the shared container unless explicitly requested
- replace the callback hack with a deliberate save API, e.g. “save shared base” vs “save app overlay”

### 2026-03-09 15:08 — components manager overhaul / filtering

What the fork adds:

- a single large `ComponentsManagerDialog`
- DXVK/VKD3D/Box64/FEXCore/Wine filtering for:
  - stable
  - nightly
  - gplasync
  - arm64ec
  - nvapi
  - sarek
  - bionic
- custom version parsing / category sorting

What looks genuinely useful:

- the stricter DXVK/VKD3D filtering ideas are good
- the category-aware version sorting is useful
- a shared classification utility could reduce confusion in component selection

What is not good upstream shape:

- the dialog is ~1,100 lines and duplicates logic already spread across:
  - `ContentsManagerDialog`
  - `DriverManagerDialog`
  - `WineProtonManagerDialog`
- it hardcodes naming heuristics tied to one release ecosystem
- its `installWcpRobustly()` flow is simpler than current mainline's more careful validation / trust-review UX

Recommendation: **yes for the filtering/sorting concepts; no for the giant dialog as-is**.

What proper GameNative should do instead:

- extract a shared release-classification utility
- reuse the existing dialogs, or first build a shared backend model before any UI merge
- keep current trust / validation flows from mainline

### 2026-03-09 15:11 — container config robustness / Wine-version-change handling

Separate from shared containers, the fork also carries a useful cluster of container-application hardening in `ContainerUtils`:

- safer `user.reg` reads/writes behind existence checks and `try/catch`
- guard against missing GPU vendor mapping when writing `VideoPciVendorID`
- on Wine version change:
  - re-extract container pattern files
  - clear cached Steam DLL markers
  - clear stale original-DLL cache

This is one of the more cherry-pickable areas in the fork because it is solving concrete failure cases, not inventing a whole new architecture.

Recommendation: **yes, likely worth selectively porting**.

Proper GameNative implementation should:

- port the defensive registry-read/write handling
- port Wine-version-change refresh logic as an isolated fix
- keep it separate from the fork's master-container machinery

### 2026-03-09 15:14 — ImageFs installer hardening

What the fork adds:

- bounded retry loop during imagefs install
- more aggressive directory clearing
- recursive chmod after extraction
- package redirection symlink repair for bionic installs

What looks useful:

- retries and better cleanup are reasonable responses to flaky installs
- symlink repair likely addresses real package-name assumptions in redirected guest paths

What is risky or sloppy:

- hardcoded package aliases (`com.winlator.cmod`, `app.gamenative`)
- recursive `0755` chmod across the extracted tree is broad and not obviously the right permission model
- destructive clear-and-retry is not as good as extract-to-temp + verify + atomic swap

Recommendation: **partial yes**.

What proper GameNative should do instead:

- extract to a temp directory first, validate, then swap into place
- keep retries bounded and logged
- derive redirect targets from `BuildConfig.APPLICATION_ID` / package metadata, with compatibility aliases centralized in one place

### 2026-03-09 15:20 — performance tuner / aggressive clocks

What the fork adds:

- `PerformanceTuner.java`
- root performance mode that loops and rewrites CPU/GPU governor/sysfs values every second
- non-root Adreno mode via native helper
- launcher integration that enables those modes on game start

Why this is not acceptable upstream as-is:

- it does **not restore prior CPU/GPU governor/frequency state** on shutdown; it mostly just stops reapplying
- it assumes Adreno-specific and device-specific sysfs nodes
- it keeps a persistent `su` shell and repeatedly writes to kernel nodes
- failures are easy to hide and hard to support across device vendors

Recommendation: **no**.

If upstream ever wants a performance mode, it should be:

- capability-detected
- opt-in
- fully restorable
- heavily device-gated
- accompanied by telemetry / user warnings

### 2026-03-09 15:24 — ALSA / audio reflector work

What the fork adds:

- major native rewrite in `alsa_client.c`
- Java-side `ALSAClient` reflector mode with simulated stream + mirror stream behavior
- new `alsa-reflector` driver mode constants

Why this is risky:

- it is a large low-level audio redesign, not a narrow bugfix
- “simulated” writes can mask correctness problems
- it is difficult to reason about without device-level testing and regression coverage

Small potentially-portable idea:

- graceful fallback when native symbols / JNI methods are unavailable, instead of crashing

Recommendation: **do not adopt wholesale**. At most, consider a much smaller defensive fallback patch after reproducing a real crash in mainline.

### 2026-03-09 15:30 — in-game menu / controller-first UX

Current mainline already has a Compose `QuickMenu` plus suspend/resume handling in `XServerScreen`.

What the fork adds beyond that:

- pause/resume game action
- HUD toggle + global persistence
- native-rendering toggle + global persistence
- touch transparency dialog
- joystick visibility toggle
- task manager, controller manager, motion controls, screen effect shortcuts
- nested controller/touch/tools/display menu structure

Recommendation: **partial yes**.

Proper GameNative implementation should:

- extend the current `QuickMenu`, not replace it with fork `GameNavigationMenu` / old-style `NavigationDialog`
- reuse current overlay pause/focus model
- add only the actions that are actually useful and maintainable
- keep persistence in current settings architecture, not bolt on unrelated dialog code

### 2026-03-09 15:33 — frontend/store UX, setup wizard, and download queueing

The fork also adds a broader frontend/product layer that is separate from the in-game menu work:

- `DownloadQueueManager`
- `PrefManager.maxConcurrentDownloads`
- `PrefManager.aioStoreEnabled`
- `SetupWizardScreen` + `PrefManager.setupCompleted`
- a more console/TV-style storefront flow (`LibraryFrontendPane`, AIO Store tab model)

What looks useful:

- a cross-store download queue is a real feature
- configurable concurrent-download count is potentially valuable
- the fork already has a `DownloadService.getAllDownloads()` aggregator, so a queue layer is a natural extension rather than a total rewrite

What is mostly product/UX taste:

- the AIO Store tab structure
- the full frontend pane experience
- the first-run setup wizard

Recommendation:

- **download queue / max-concurrency control:** **maybe / partial yes**
- **AIO store / frontend pane / setup wizard:** mostly **product-direction dependent**, not an obvious upstream must-have

Proper GameNative implementation should:

- add queue semantics at the service/backend layer first
- define clear per-service pause/cancel/retry contracts
- avoid coupling queue logic tightly to one large controller-first frontend UI

### 2026-03-09 15:35 — custom artwork support

Important nuance: current mainline already supports **fetching SteamGridDB images**, but it does **not** provide a proper user-selected artwork override flow.

What the fork adds:

- `CustomImageDialog`
- artwork select / fetch / reset actions
- `GameMetadataManager.customImagePath`
- storage under `files/remote_games_metadata/<appId>/custom_artwork.jpg`

What is good:

- the user-facing feature is legitimate and useful
- it fits well with GameNative's existing image-fetch story

What should be cleaned up:

- storing an **absolute path** in metadata is weaker than storing a stable relative asset key / convention
- the override should plug into the existing image-resolution pipeline cleanly, rather than being scattered through UI code

Recommendation: **yes, likely worth adding**, but with cleaner metadata and storage semantics.

### 2026-03-09 15:39 — save import/export tooling

What the fork adds:

- `SaveManager.exportSave()`
- `SaveManager.importSave()`
- UI hooks for manual ZIP-based save backup/restore

What is useful:

- manual save backup/restore is a real quality-of-life feature
- it complements cloud saves instead of replacing them

What is risky:

- export is heuristic/title-based and may miss or over-include folders
- import does aggressive path guessing for “blind” ZIPs
- overwrite/preview behavior is minimal

Recommendation: **maybe / partial yes**.

Proper implementation should include:

- preview of detected save paths
- overwrite confirmation
- clearer per-platform path detection
- possibly a “best-effort manual utility” label so users understand its limitations

### 2026-03-09 15:42 — internal file explorer

What the fork adds:

- `FileExplorerDialog` with copy/cut/paste/delete over app-internal paths

Recommendation: **no for production UI**.

Reason:

- too much destructive power for too little user value
- better suited to debug builds or developer tools only

### 2026-03-09 15:45 — download folder picker helper

What the fork adds:

- `DownloadFolderPicker` helper around `OpenDocumentTree`
- persistable URI-permission attempt

Current mainline already has an adjacent helper:

- `CustomGameFolderPicker`

Recommendation: **yes as a refactor target**, not as a separate parallel abstraction.

Proper implementation:

- generalize current picker helper into a reusable folder-picker utility
- keep persistable URI permissions where appropriate

### 2026-03-09 15:47 — surface format option

What the fork adds:

- `surfaceFormat=BGRA8` in default graphics driver config
- graphics UI exposure for surface format
- propagation into wrapper env / xserver configuration

Recommendation: **maybe**.

This is a plausible advanced compatibility/perf knob, but it needs validation per driver/runtime combination before landing.

## What the performance fork lacks vs proper GameNative

The fork is not just “customized”; it is also behind important mainline/upstream work.

For clarity: this section uses **proper GameNative = upstream/mainline**, not just the slightly older local `master` snapshot in this worktree.

### Security / request integrity features missing in the fork

Current proper GameNative contains infrastructure the fork lacks, including:

- `app/src/main/java/app/gamenative/utils/PlayIntegrity.kt`
- `app/src/main/java/app/gamenative/utils/KeyAttestationHelper.kt`
- upstream attestation / namespace-verification request work

That means the fork is missing significant newer security / abuse-hardening work.

### Notable local-`master` subsystems absent in the fork

Even before considering the extra 10 upstream commits, the local `master` snapshot already contains subsystems the fork does not, including:

- `app/src/main/java/app/gamenative/ui/component/QuickMenu.kt`
- `app/src/main/java/app/gamenative/externaldisplay/IMEInputReceiver.kt`
- `app/src/main/java/com/winlator/xenvironment/components/WineRequestComponent.java`
- the local `PlayIntegrity` / `KeyAttestationHelper` stack already mentioned above

That means the fork is missing some newer GameNative architecture around:

- in-game overlay UX
- external-display keyboard handling
- Wine-to-Android external request handling
- request-integrity/security plumbing

### Stability / platform fixes missing in the fork

Notable missing upstream-side fixes include:

- `cf29ca74` – stream/resource close fix in `listRunningWineProcesses`
- `1f9018ca` – glibc VirGL library-path fix
- `ce241623` – Vulkan extension enumeration / edit-container crash fix
- `336653b9` – external launch while app is open fix
- `a376d3b4` – Steam download resume fix
- upstream cloud-save retry/error-handling work (for example the AsyncJobFailedException retry fix)
- pointer/input/external-display fixes such as `c7bac61e` and related upstream controller/input work

### Store / content handling improvements missing in the fork

The fork also misses newer GameNative work around:

- Epic language support
- gen1 GOG language support
- GOG partial-download / script-interpreter handling
- more recent cloud-save / download manager fixes
- diacritic-insensitive search
- skip-login / optional-login support already present in proper GameNative (`login_skip_login` in current mainline resources/UI)

This last point is worth calling out because the fork README markets “optional login” as a feature, but proper GameNative already has that capability while the fork does not appear to carry the newer skip-login UI.

## Recommendation summary

### Strongest adoption candidates

- container-config / Wine-version-change hardening from `ContainerUtils`
- selective component filtering/sorting improvements
- selective ImageFs installation hardening
- custom artwork override feature (implemented cleanly)
- reusable folder-picker refactor
- maybe a backend download-queue/concurrency layer

### Plausible but needs design work

- shared/master containers
- manual save import/export
- expanded in-game quick-menu actions
- surface-format advanced setting

### Reject as-is

- whole-fork merge
- `PerformanceTuner`
- internal production file explorer
- ALSA reflector / simulated audio engine wholesale

## Final recommendation table (working draft)

| Area | In current local `master`? | Recommendation | Proper GameNative implementation |
|---|---:|---|---|
| Shared/master containers | No | Partial yes | Fresh overlay-based design, not fork callback hack |
| Container config / Wine-version-change hardening | Partly | Yes, selective | Port defensive registry/prefix-refresh fixes independently |
| Components filtering/sorting | Partly (via separate managers, not fork dialog) | Yes, selective | Shared classifier utilities inside existing managers |
| ImageFs hardening | Partly | Yes, selective | Temp-dir + validate + swap, bounded retries |
| Performance tuner / aggressive clocks | No | No | Do not upstream without full restore/device gating |
| ALSA reflector/audio rewrite | No | No | At most small crash-proofing fallback patches |
| Expanded in-game menu actions | Partly (`QuickMenu`) | Partial yes | Extend current `QuickMenu` |
| Frontend/store UX + download queue | Partly (downloads exist, queue/frontend layer does not) | Maybe / partial yes | Backend queue first, UI second |
| Custom artwork override | No | Yes, selective | Add clean metadata/storage-backed override flow |
| Manual save import/export | No | Maybe | Add guarded utility flow with preview/confirm |
| Internal file explorer | No | No | Debug-only at most |
| Download folder picker helper | Partly (`CustomGameFolderPicker`) | Yes, selective | Generalize existing picker helper |
| Surface format option | No | Maybe | Advanced graphics option after validation |

## Provisional chat-ready summary

Short version:

- `maxjivi05/GameNative-Performance` has some genuinely interesting ideas
- but it is also behind important newer GameNative/upstream work and contains a lot of repo hygiene / implementation-quality problems
- it should be mined selectively, **never merged wholesale**

Best feature candidates to adopt in proper GameNative:

- container-config / Wine-version-change hardening
- component filtering/sorting improvements
- parts of ImageFs install hardening
- custom artwork override support
- a generalized download-folder picker helper
- possibly some extra in-game quick-menu actions

Features that are interesting but need a fresh design:

- shared/master containers
- manual save import/export
- surface-format advanced graphics option
- controller-first storefront/frontend UX if product direction wants it

Features that should not be imported as-is:

- `PerformanceTuner`
- ALSA reflector / simulated audio engine redesign
- internal production file explorer
- vendored engine / OpenXR / Ludashi-style tree imports

## Next analysis steps

Remaining work before final user-facing summary:

1. double-check a few omission claims against local `master` vs `upstream/master`
2. keep expanding the journal only where new evidence changes the recommendation
3. do one last pass for any small but high-value fork features that have not been bucketed yet
