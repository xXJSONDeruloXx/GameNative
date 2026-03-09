# Mainline implementation drafts from `maxjivi05/GameNative-Performance`

Date: 2026-03-09  
Author: pi coding agent  
Related audit: `docs/fork-audits/maxjivi05-performance-vs-mainline-2026-03-09.md`

## Purpose

This follow-up document turns the audit recommendations into **mainline-shaped implementation drafts**.

The goal is not to describe how to copy the fork. The goal is to describe how each promising feature should look **if implemented cleanly in proper GameNative**.

For each feature, this doc covers:

- where it would fit in current mainline
- what the implementation shape should be
- what user/developer benefit it brings
- what to avoid from the fork implementation

## Summary table

| Feature | Recommendation | Mainline shape | Primary benefit |
|---|---|---|---|
| Container config / Wine-version-change hardening | Yes | Small targeted fixes in existing container apply/launch flow | Fewer broken containers and edit-time crashes |
| Components filtering / sorting | Yes | Shared classifier/sort utility reused by current manager dialogs | Easier version selection, less UI duplication |
| ImageFs installer hardening | Yes, selective | Safer temp-install + validate + swap flow | Fewer failed/corrupted base installs |
| Custom install paths | Yes | Central install-path resolver/repository across stores | Real user control over storage layout |
| Custom artwork overrides | Yes | Metadata-backed artwork repository + resolver | User-chosen box art without UI hacks |
| Folder picker refactor | Yes | One reusable SAF folder picker utility | Less duplicated path/permission logic |
| QuickMenu expansion | Partial yes | Extend existing Compose `QuickMenu` only | Better in-game usability |
| Shared/master containers | Partial yes | Fresh repository + overlay model | Less duplicated container state on disk |
| Manual save import/export | Maybe | Utility flow built on existing save-path detection | Simple manual backup/restore option |
| Download queue / concurrency control | Maybe | Backend queue service with per-store adapters | Predictable cross-store downloads |
| Surface format option | Maybe | Advanced graphics option via `graphicsDriverConfig` | Potential device-specific compatibility fix |

---

## 1. Container config / Wine-version-change hardening

### Current mainline anchor points

- `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- `app/src/main/java/com/winlator/core/WineUtils.java`
- `app/src/main/java/com/winlator/xenvironment/ImageFsInstaller.java`

### Current mainline shape

Mainline already has the right places for this work:

- `ContainerUtils.applyToContainer(...)` writes registry-backed container settings
- `ContainerUtils.toContainerData(...)` reads container state back out
- `XServerScreen.kt` already handles several Wine-version-sensitive refresh/extraction tasks at launch time
- `ImageFsInstaller` already has logic for clearing some DLL markers during image refreshes

### Mainline implementation draft

Implement this as a **small hardening pass**, not a new subsystem.

1. Add a safe helper around `user.reg` access
   - either in `ContainerUtils.kt`
   - or a tiny new utility such as `WineRegistryUtils.kt`
   - behavior:
     - check file existence before opening
     - wrap registry reads/writes in `try/catch`
     - log and continue with sane defaults when the file is missing or malformed

2. Remove unsafe GPU vendor assumptions in `ContainerUtils.applyToContainer(...)`
   - current code uses `getGPUCards(context)[containerData.videoPciDeviceID]!!`
   - replace that with a nullable lookup and fallback path
   - only write `VideoPciVendorID` when a valid mapping exists

3. Add explicit container-change detection
   - compute a small diff before mutating the container
   - at minimum detect changes in:
     - `wineVersion`
     - `containerVariant`
     - possibly `dxwrapper`

4. When Wine version or variant changes, run a focused refresh step
   - re-extract pattern/runtime files that are version-coupled
   - clear cached original DLL copies under `ImageFs.CACHE_PATH + "/original_dlls"`
   - clear stale Steam DLL markers through a shared utility, not ad hoc scattered logic

5. Keep refresh behavior explicit
   - do not hide persistence behavior inside `Container.saveData()`
   - do not mix this with any shared/master-container design

### Benefit

User-facing:

- fewer broken containers after editing settings
- fewer failures when switching Wine versions
- fewer crashes when registry files are missing or partially corrupted

Engineering:

- better fault tolerance around one of the riskiest parts of container editing
- smaller support/debug burden than broad architectural changes

### Avoid from the fork

- coupling this to shared/master-container persistence
- implicit callback-driven save behavior
- broad invasive rewrite when targeted hardening is enough

---

## 2. Components filtering / sorting

### Current mainline anchor points

- `app/src/main/java/app/gamenative/ui/screen/settings/ContentsManagerDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/settings/DriverManagerDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/settings/WineProtonManagerDialog.kt`
- `app/src/main/java/com/winlator/contents/ContentsManager.java` (existing backend contract)

### Current mainline shape

Mainline already has separate manager UIs for:

- content installs
- graphics drivers
- Wine/Proton profiles

That is good architecture for now. What is missing is a **shared classification/sorting layer**.

### Mainline implementation draft

Implement this as a **backend/model utility first**.

1. Add a shared classifier utility
   - example path: `app/src/main/java/app/gamenative/utils/content/ContentReleaseClassifier.kt`
   - responsibilities:
     - parse release names/identifiers
     - detect tags like stable, nightly, gplasync, arm64ec, bionic, nvapi, sarek
     - generate sort keys and display badges

2. Add a normalized model
   - example data class: `ClassifiedContentProfile`
   - fields could include:
     - base name
     - variant tags
     - stability channel
     - parsed version tuple
     - install state

3. Reuse it in existing dialogs instead of replacing them
   - `ContentsManagerDialog.kt`
   - `WineProtonManagerDialog.kt`
   - optionally container-config dropdowns where DXVK/VKD3D/Wine are selected

4. Keep current trust/install flows intact
   - no shortcut around manifest validation
   - no downgrade of current untrusted-content review

5. Add light UI improvements only after the shared model exists
   - chips/toggles for stable/nightly/etc.
   - more predictable version ordering
   - grouped sections when it meaningfully reduces confusion

### Benefit

User-facing:

- easier to understand which component build is which
- less confusing DXVK/VKD3D/Wine selection
- more predictable ordering of installed vs downloadable entries

Engineering:

- one classification implementation instead of repeated string heuristics
- lets current dialogs improve without merging them into a giant monolith

### Avoid from the fork

- importing the fork’s huge all-in-one dialog
- hardwiring UI behavior to one release ecosystem’s naming quirks
- weakening current validation/trust review flows

---

## 3. ImageFs installer hardening

### Current mainline anchor points

- `app/src/main/java/com/winlator/xenvironment/ImageFsInstaller.java`
- `app/src/main/java/app/gamenative/service/SteamService.kt`
- `app/src/main/java/app/gamenative/ui/PluviaMain.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt`

### Current mainline shape

Mainline already has:

- a dedicated `ImageFsInstaller`
- version / validity checks before install
- preservation logic for imported Wine/Proton directories in `opt/`

That is the right home for further hardening.

### Mainline implementation draft

Implement this as a **safer install transaction**.

1. Install into a temporary sibling directory first
   - example: `imagefs.tmp.<timestamp>`
   - never extract directly over the active root as the first step

2. Validate before swap
   - verify required directories/files exist
   - verify version/variant markers
   - verify redirect targets / important symlinks are in expected shape

3. Only after validation, atomically swap into place as much as Android filesystem constraints allow
   - rename/move the old root aside
   - promote validated temp root
   - delete old root after successful promotion

4. Add bounded retry behavior
   - retry only around clearly transient failures
   - log retry reason and retry count
   - stop after a small max retry count

5. Narrow permission repair
   - if extraction leaves bad permissions, repair only known required paths
   - do not recursive-`chmod 0755` the entire tree indiscriminately

6. Centralize package redirect repair
   - derive app/package redirect targets from runtime metadata (`context.packageName` / build config)
   - keep any compatibility aliases in one place

### Benefit

User-facing:

- fewer broken imagefs installs
- fewer cases where reinstall is required after a partial failure
- less risk of destructive half-updated runtime state

Engineering:

- easier to diagnose install failures from logs
- safer updates as imagefs contents evolve

### Avoid from the fork

- broad destructive clear-and-retry as the primary strategy
- hardcoded package aliases spread through install code
- blanket permission rewriting across the entire extracted tree

---

## 4. Custom install paths across stores

### Current mainline anchor points

- `app/src/main/java/app/gamenative/service/SteamService.kt`
- `app/src/main/java/app/gamenative/service/epic/EpicConstants.kt`
- `app/src/main/java/app/gamenative/service/gog/GOGConstants.kt`
- `app/src/main/java/app/gamenative/service/amazon/AmazonConstants.kt`
- `app/src/main/java/app/gamenative/service/DownloadService.kt`
- `app/src/main/java/app/gamenative/ui/components/CustomGameFolderPicker.kt`

### Current mainline shape

Mainline currently computes install paths mostly from:

- internal vs external storage preference
- per-store default root helpers
- sanitized game-title-derived folder names

What is missing is a clean **cross-store override model**.

### Mainline implementation draft

Implement this as a **central install-path service**, not store-by-store duplicated logic.

1. Add a small persistence layer
   - example: `InstallPathRepository`
   - stores:
     - optional per-store root override
     - optional per-game install override
     - maybe persisted SAF URI permission metadata when needed

2. Add a resolver layer
   - example: `InstallPathResolver`
   - responsibilities:
     - normalize chosen folder
     - decide whether the user picked a parent folder or final game folder
     - sanitize final leaf folder name when necessary
     - produce a canonical final path for each store

3. Keep per-store code thin
   - `SteamService`, `EpicService`, `GOGService`, `AmazonService` should ask the resolver for the final path
   - the store service should persist the chosen final path into its own DB/model where appropriate
   - the resolver should own the path-shape rules, not each service

4. Add UI at the install flow entry points
   - “Install here” / “Choose folder” action in install dialogs/screens
   - show final resolved path before confirming
   - allow reset back to store default

5. Make `DownloadService` aware of overrides
   - directory scanning helpers should use the resolver/repository
   - not hardcoded Steam internal/external paths only

### Benefit

User-facing:

- users can install large games where they want
- works better for multi-store libraries and SD-card style layouts
- reduces surprises about where content ends up

Engineering:

- one place to fix path normalization bugs
- avoids four near-duplicate implementations diverging over time

### Avoid from the fork

- copy-pasting `setCustomInstallPath()` variants into every service with slightly different rules
- scattering parent-vs-leaf-folder logic across UI screens
- treating picker behavior as the core feature instead of the resolver/persistence model

---

## 5. Custom artwork overrides

### Current mainline anchor points

- `app/src/main/java/app/gamenative/utils/GameMetadataManager.kt`
- `app/src/main/java/app/gamenative/utils/SteamGridDB.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/data/GameDisplayInfo.kt`
- `app/src/main/java/app/gamenative/ui/enums/AppOptionMenuType.kt`

### Current mainline shape

Mainline already supports:

- SteamGridDB fetches
- local SteamGridDB image files for at least some library flows
- game metadata stored in `.gamenative`

What is missing is a proper **user-selected override pipeline**.

### Mainline implementation draft

Implement this as a **repository + resolver**, not as UI-local file handling.

1. Extend metadata shape carefully
   - expand `GameMetadata` in `GameMetadataManager.kt`
   - instead of storing absolute paths, store stable asset keys or relative filenames
   - example fields:
     - `heroArtworkKey`
     - `capsuleArtworkKey`
     - `logoArtworkKey`

2. Add a dedicated artwork storage convention
   - example root: `files/game_artwork/<appId>/`
   - example assets:
     - `hero.jpg`
     - `capsule.jpg`
     - `logo.png`
   - app-private storage is preferable to arbitrary external absolute paths

3. Add an artwork resolver
   - example: `GameArtworkResolver.kt`
   - resolution order:
     1. explicit user override
     2. locally fetched SteamGridDB asset
     3. remote/source-provided URL
   - centralize this so `LibraryGridCard`, app screens, and dialogs do not each reimplement lookup rules

4. Add user actions to app menus
   - choose artwork
   - reset artwork
   - possibly keep “fetch images” alongside override actions

5. Keep import UX simple
   - use Android picker for image files
   - copy selected image into app-managed storage
   - optionally normalize/crop later, but that can be phase 2

### Benefit

User-facing:

- real box-art customization
- lets users fix bad/missing art without relying only on SteamGridDB
- useful for custom games and non-Steam titles especially

Engineering:

- one image-resolution pipeline instead of ad hoc local-file checks in several UI surfaces
- easier future expansion to more artwork types

### Avoid from the fork

- storing raw absolute paths in metadata
- sprinkling override logic directly through UI components
- tying the feature too tightly to one dialog implementation

---

## 6. Folder picker refactor / generalization

### Current mainline anchor points

- `app/src/main/java/app/gamenative/ui/components/CustomGameFolderPicker.kt`
- `app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`

### Current mainline shape

Mainline already has a small reusable folder picker helper, but it is still framed around custom-game import behavior.

### Mainline implementation draft

Implement this as a **general SAF folder-picker utility**.

1. Rename/reframe the helper
   - something like `FolderPicker.kt` or `rememberFolderPicker(...)`
   - keep `CustomGameFolderPicker` as a thin compatibility wrapper if desired

2. Return richer data
   - not just resolved path string
   - also return:
     - original `Uri`
     - resolved path if available
     - whether persistable URI permission was granted

3. Centralize validation modes
   - install-root selection
   - final game folder selection
   - container drive-mount path selection
   - generic folder selection

4. Centralize permission handling
   - SAF permission capture
   - `MANAGE_EXTERNAL_STORAGE` fallback request behavior where still needed
   - common failure messages

5. Reuse it in new features
   - custom install paths
   - save export/import destination picking
   - any future library folder selection flows

### Benefit

User-facing:

- more consistent picker behavior across the app
- fewer cases where the same folder works in one flow but fails in another

Engineering:

- less repeated URI-to-path and permission code
- easier to improve SAF handling once, everywhere

### Avoid from the fork

- introducing a second parallel picker abstraction without consolidating the existing one
- treating URI permission persistence as optional glue scattered around callers

---

## 7. QuickMenu expansion

### Current mainline anchor points

- `app/src/main/java/app/gamenative/ui/component/QuickMenu.kt`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- `app/src/main/java/app/gamenative/PrefManager.kt`

### Current mainline shape

Mainline already has:

- a Compose quick menu
- existing action IDs and handlers
- overlay pause/focus state in `XServerScreen.kt`

So this should be an **extension of the existing overlay**, not a replacement.

### Mainline implementation draft

1. Keep `QuickMenu.kt` as the only menu UI
   - extend action modeling rather than introducing a second legacy menu stack

2. Move toward state-driven action lists
   - let `XServerScreen` build a list of `QuickMenuItem`s based on session capabilities
   - example conditional actions:
     - pause / resume
     - HUD toggle
     - touchscreen transparency
     - joystick visibility
     - task-manager shortcut if a safe hook already exists

3. Persist only the right toggles
   - per-container or global settings should go through current `PrefManager` / container extras patterns
   - ephemeral session actions should stay ephemeral

4. Reuse the current pause/focus model
   - do not bolt in a separate pause implementation
   - do not fork overlay lifecycle state unnecessarily

5. Ship incrementally
   - phase 1: pause/resume + joystick visibility + HUD toggle
   - phase 2: more advanced controller/display tools if still justified

### Benefit

User-facing:

- better controller-first in-game UX
- quicker access to genuinely useful runtime toggles
- less need to leave the game for common adjustments

Engineering:

- preserves the existing Compose overlay direction
- incremental additions are easy to review and revert if one action proves fragile

### Avoid from the fork

- replacing `QuickMenu` with a separate old-style menu/dialog stack
- mixing persistent settings and transient runtime actions without clear boundaries
- adding every fork action just because it exists

---

## 8. Shared/master containers

### Current mainline anchor points

- `app/src/main/java/com/winlator/container/Container.java`
- `app/src/main/java/com/winlator/container/ContainerManager.java`
- `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`
- `app/src/main/java/com/winlator/container/ContainerData.kt`

### Current mainline shape

Mainline currently assumes a container is a real persisted unit with its own `config.json` and root directory.

That is simple and robust, but it duplicates state.

### Mainline implementation draft

If mainline wants this, it should be done as a **new overlay model**, not a hack inside `Container.saveData()`.

1. Add explicit repositories
   - `SharedContainerRepository`
   - `ContainerAssignmentRepository`
   - optional `AppContainerOverlayRepository`

2. Separate persistence domains
   - shared/base container config
   - per-app overlay config
   - per-launch transient drive/executable mapping

3. Build launch config in memory
   - do not rewrite the shared base config just to launch one game
   - generate merged drive mappings and launch extras at runtime

4. Keep saves explicit
   - “save shared base” and “save app overlay” should be different operations
   - no global callback inside `Container.saveData()`

5. Roll out in phases
   - phase 1: read-only assignment to an existing base container
   - phase 2: explicit overlay settings
   - phase 3: migration helpers if the model proves stable

### Benefit

User-facing:

- less disk duplication
- potentially faster onboarding for large libraries with similar runtime needs

Engineering:

- creates a future path to proper overlay/container composition
- cleaner than ad hoc cloning and mutation patterns

### Avoid from the fork

- opaque JSON blobs in prefs for per-game overrides
- callback-based persistence side channels
- mixing transient launch mappings into persisted shared container state

---

## 9. Manual save import/export

### Current mainline anchor points

- `app/src/main/java/app/gamenative/service/SteamAutoCloud.kt`
- `app/src/main/java/app/gamenative/service/epic/EpicCloudSavesManager.kt`
- `app/src/main/java/app/gamenative/service/gog/GOGManager.kt`
- `app/src/main/java/app/gamenative/enums/PathType.kt`
- app screens / option menus under `app/src/main/java/app/gamenative/ui/screen/library/appscreen/`

### Current mainline shape

Mainline already has substantial logic for finding or syncing save locations for several stores.

That means a manual backup feature should build on those existing path-detection concepts, not invent a separate heuristic world.

### Mainline implementation draft

1. Add a save-location resolver
   - example: `SaveLocationResolver.kt`
   - responsibilities:
     - ask Steam/GOG/Epic helpers for known save roots where possible
     - fall back to best-effort rules only when platform-specific detection is unavailable

2. Add a manual utility layer
   - example: `SaveBackupManager.kt`
   - capabilities:
     - enumerate candidate save paths
     - preview files/folders to export
     - create ZIP backup
     - import ZIP into a chosen destination with overwrite policy

3. Add explicit preview/confirm UX
   - before export: show which folders will be included
   - before import: show destination and overwrite mode
   - make “best effort” visible in the UI for uncertain cases

4. Keep the feature optional and bounded
   - manual utility, not a replacement for cloud sync
   - no aggressive blind path guessing without user confirmation

### Benefit

User-facing:

- easy manual backup/restore
- useful for offline users, testing, migration, or safety copies before modding

Engineering:

- reuses existing save-path knowledge instead of duplicating it in one-off scripts/dialogs

### Avoid from the fork

- title-only export heuristics presented as if they are exact
- blind import into guessed destinations without preview or overwrite confirmation

---

## 10. Download queue / concurrency control

### Current mainline anchor points

- `app/src/main/java/app/gamenative/service/DownloadService.kt`
- `app/src/main/java/app/gamenative/data/DownloadInfo.kt`
- `app/src/main/java/app/gamenative/service/SteamService.kt`
- `app/src/main/java/app/gamenative/service/epic/EpicService.kt`
- `app/src/main/java/app/gamenative/service/gog/GOGService.kt`
- `app/src/main/java/app/gamenative/service/amazon/AmazonService.kt`
- `app/src/main/java/app/gamenative/PrefManager.kt`

### Current mainline shape

Mainline already has:

- a shared `DownloadInfo` progress object
- per-store active-download tracking
- cancellation hooks in some services

What it does not yet have is a **unified scheduler**.

### Mainline implementation draft

1. Add a backend queue service
   - example: `UnifiedDownloadQueue.kt`
   - queue item key: store + app/game identifier
   - status: queued / starting / active / paused / failed / complete

2. Add small per-store adapters
   - `SteamDownloadAdapter`
   - `EpicDownloadAdapter`
   - `GOGDownloadAdapter`
   - `AmazonDownloadAdapter`
   - each adapter exposes common operations:
     - start
     - cancel
     - current status/progress
     - canPause/canResume if available

3. Add a concurrency preference
   - new `PrefManager` setting for max simultaneous cross-store downloads
   - distinct from low-level Steam chunk/download-speed tuning

4. UI comes second
   - first land queue semantics and eventing
   - later add queue list, reorder, or controller-first download UI if desired

### Benefit

User-facing:

- clearer and more predictable download behavior
- better when multiple stores are in use
- avoids accidental download pileups

Engineering:

- one scheduler for cross-store orchestration
- easier future notifications / queue UI

### Avoid from the fork

- tying queue behavior to one large frontend/store redesign
- building UI-heavy abstractions before the backend contracts exist

---

## 11. Surface format advanced option

### Current mainline anchor points

- `app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- `app/src/main/java/com/winlator/container/ContainerData.kt`
- `app/src/main/cpp/winlator/gpu_image.c`

### Current mainline shape

Mainline already has a flexible `graphicsDriverConfig` pipeline:

- UI writes key/value graphics options
- `XServerScreen.kt` translates them into env vars and runtime behavior

That makes this a plausible advanced option if validated.

### Mainline implementation draft

1. Add a gated advanced UI dropdown in `GraphicsTab.kt`
   - stored in `graphicsDriverConfig`, e.g. `surfaceFormat=rgba8|bgra8`
   - hide behind an advanced section or only show on relevant drivers

2. Read and apply in `XServerScreen.kt`
   - map the config to the relevant env var / runtime switch
   - pass through only when supported by the active wrapper/driver path

3. Validate against real devices before enabling broadly
   - keep default conservative
   - gate per driver family if needed

4. Keep rollback simple
   - if unsupported, ignore and log rather than fail launch

### Benefit

User-facing:

- may resolve rendering/corruption issues on some drivers/devices
- useful as an expert compatibility toggle

Engineering:

- fits naturally into the existing graphics config architecture
- small enough to ship independently once validated

### Avoid from the fork

- enabling as a universal default before device validation
- exposing it as a flashy feature without understanding driver impact

---

## Suggested implementation order

If these are turned into real work items, the most sensible order is:

1. container config / Wine-version-change hardening
2. components filtering / sorting utility
3. ImageFs installer hardening
4. folder picker refactor
5. custom install paths
6. custom artwork overrides
7. QuickMenu expansion
8. manual save import/export
9. download queue / concurrency control
10. surface format option
11. shared/master containers

Reasoning:

- the first six items are the most concrete and least architecturally risky
- QuickMenu expansion is straightforward if kept incremental
- save queueing and shared containers are more design-heavy and should follow the smaller wins

## Out of scope for mainline porting

These remain poor mainline candidates as currently implemented in the fork:

- aggressive performance tuner / governor rewriting
- ALSA reflector / simulated audio redesign
- internal production file explorer
- Ludashi / OpenXR / XR tree imports

Those should stay out of any mainline port plan unless separately re-justified with new evidence.
