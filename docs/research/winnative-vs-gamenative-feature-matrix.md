# WinNative vs GameNative feature matrix

_Last updated: 2026-03-18_

## Scope

This document compares:

- **GameNative** at `bc369728` (`upstream/master`, also current local `master`)
- **WinNative** at `fad0594` (`main` in `/Users/danhimebauch/Developer/WinNative`)

It focuses on the checked-in product surface, implementation architecture, and repo-level capabilities that are visible in the two repositories right now.

## Goal

Answer, as concretely as possible:

1. What both apps clearly share
2. What **GameNative** has that **WinNative** lacks or only partially exposes
3. What **WinNative** has that **GameNative** lacks
4. Which differences look structural vs cosmetic

## Methodology

The comparison is based on:

- top-level repo structure
- `README.md`
- `app/build.gradle.kts` vs `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- representative frontend files
- representative service / runtime / container files
- targeted repository searches for feature signals
- source-file counts, language mix, test counts, workflow counts, and localization directories

Key evidence paths include:

- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/app/gamenative/ui/...`
- `app/src/main/java/app/gamenative/service/...`
- `/Users/danhimebauch/Developer/WinNative/README.md`
- `/Users/danhimebauch/Developer/WinNative/app/build.gradle`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/AndroidManifest.xml`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/...`

## Snapshot inventory

| Metric | GameNative | WinNative |
|---|---:|---:|
| App namespace | `app.gamenative` | `com.winlator.cmod` |
| Application ID | `app.gamenative` | `com.winnative.cmod` |
| Version | `0.8.1` | `7.1.4x-cmod` |
| Java/Kotlin source files | 555 | 415 |
| Kotlin files | 348 | 152 |
| Java files | 207 | 263 |
| Manifest activities | 4 | 13 |
| Manifest activity aliases | 2 | 0 |
| Manifest services | 4 | 4 |
| Manifest providers | 1 | 2 |
| Manifest receivers | 0 | 1 |
| Manifest permissions | 14 | 14 |
| Manifest features | 1 | 5 |
| Unit tests | 21 | 0 |
| Shared tests | 1 | 0 |
| Android tests | 11 | 0 |
| GitHub workflows | 4 | 1 |

## Executive summary

### Bottom line

**WinNative is not just a renamed GameNative build.**

The repositories point to different primary product centers:

- **GameNative** is a more storefront-first, modern-Compose, game-library product that embeds a Winlator backend.
- **WinNative** is a more Winlator-Cmod-first emulator/container product that has adopted a substantial amount of Pluvia/GameNative-style storefront and library work.

### Strongest high-level takeaways

1. **The app shells are materially different.**
   - GameNative has a leaner manifest and a more focused frontend shell.
   - WinNative ships a much broader Winlator-style app shell: setup wizard, hub, big picture mode, XR activity, document provider, classic fragments, and more platform plumbing.

2. **There is significant overlap in shared concepts and likely borrowed code.**
   - The repos share **304 source basenames** even though package roots differ.
   - Shared names include `SteamAutoCloud`, `SteamUnifiedFriends`, `EpicService`, `GOGCloudSavesManager`, `ContainerManager`, `AppInfo`, and many XServer / renderer / input classes.

3. **GameNative is more product-polished around its storefront frontend.**
   - modern library flows
   - compatibility badges and cache-backed compatibility fetching
   - quick menu + floating performance HUD
   - stronger Amazon Games implementation
   - stronger test / workflow / release scaffolding

4. **WinNative is broader on emulator-shell features and low-level environment management.**
   - first-run setup wizard
   - unified downloads tab with bulk actions
   - big picture mode
   - XR / VR path
   - document provider + accessibility-service shortcuts + Dex shortcut interception
   - more explicit Winlator-native content/runtime management posture

## Working matrix status

The detailed matrix below is intentionally expanded in stages:

- [x] repository / build / manifest baseline
- [x] storefronts and account integrations
- [x] library and navigation surfaces
- [x] downloads and installs
- [x] cloud saves and sync
- [x] runtime / container / contents management
- [ ] input / controllers / overlays
- [ ] platform integration and shell features
- [ ] testing / CI / docs / provenance
- [ ] final “shared / has / lacks” summary

## Initial comparison notes

### Product positioning

**GameNative** (`README.md`)
- presents itself as a game launcher for owned Steam / Epic / GOG titles on Android
- explicitly calls itself a fork of Pluvia
- emphasizes cloud saves and direct consumer usage
- has a dedicated privacy policy folder in-repo
- tells users to seek support on Discord instead of filing GitHub issues

**WinNative** (`/Users/danhimebauch/Developer/WinNative/README.md`)
- presents itself as a Windows emulation environment for Android
- explicitly says it unifies Winlator Bionic and Pluvia
- emphasizes drivers, translators, performance, and enthusiast usage
- invites pull requests directly in the README
- does not ship an equivalent in-repo `PrivacyPolicy/README.md` in the checked clone

### Repo/build posture

**GameNative**
- Kotlin DSL build (`app/build.gradle.kts`)
- dynamic feature module: `ubuntufs`
- dual ABI output: `arm64-v8a` and `armeabi-v7a`
- release variants include a `release-gold` build with alternate icons
- has more CI / release workflow scaffolding

**WinNative**
- Groovy build (`app/build.gradle`)
- `externalNativeBuild` enabled with CMake
- explicit native-submodule gate (`checkSubmodules`) for `src/main/cpp/adrenotools`
- arm64-only packaging in default config
- mixed Compose + classic view binding app architecture

### Manifest/app-shell posture

**GameNative manifest**
- 4 activities, 2 aliases, 4 services, 1 provider
- single main launcher activity plus OAuth activities and icon aliases
- `FileProvider`, but no document provider or receiver in the checked manifest

**WinNative manifest**
- 13 activities, 4 services, 2 providers, 1 receiver
- setup wizard, unified activity, hub, main activity, big picture activity, XServer display activity, XR activity, controls editor, restore activity
- includes both `FileProvider` and a `DocumentsProvider` implementation (`WinlatorFilesProvider`)
- includes a broadcast receiver for shortcut updates

## Detailed feature matrix

| Area | Shared baseline | GameNative edge / unique strength | WinNative edge / unique strength | What each lacks against the other |
|---|---|---|---|---|
| Product focus | Both combine storefront logic with Winlator-derived runtime code. | Clearer consumer-facing identity as a game library / launcher product. | Clearer emulator-shell identity with Winlator-Cmod-style environment tooling. | GameNative lacks WinNative’s broader emulator shell; WinNative lacks GameNative’s cleaner product focus. |
| Store logins | Both clearly implement Steam, GOG, and Epic authentication and background services. | Adds a real Amazon Games stack: OAuth activity, service, DAO, app screen, launch path, SDK deployment. | Keeps setup/store configuration more centralized inside setup and store screens. | WinNative lacks a full checked-in Amazon implementation; GameNative lacks WinNative’s setup-first store onboarding. |
| Library layouts | Both expose library browsing with carousel/list/grid-style layouts and controller-friendly navigation. | More specialized library frontend: `LibraryCarouselPane`, `LibraryListPane`, search bar, compatibility badges, focused library components. | Unified hub can switch between Library, Downloads, and Store tabs in one shell, with AIO / per-store tab building. | GameNative home currently resolves to library-only content; WinNative library is broader but less focused. |
| Downloads UX | Both support resumable downloads, cancellation, and Wi‑Fi-aware pausing for core stores. | Strong per-game install UX across Steam, GOG, Epic, and Amazon app screens. | Dedicated unified Downloads tab with queue-size control, pause/resume all, cancel all, clear completed, and selection-aware actions. | GameNative lacks a checked-in equivalent unified cross-store downloads control surface; WinNative lacks GameNative’s Amazon download path. |
| Cloud saves | Both carry Steam, GOG, and Epic cloud-save components. | Strong storefront-facing conflict and sync messaging integrated into the app UX, plus explicit compatibility with launch flows. | Adds a `CloudSyncHelper` orchestration layer and launch-time sync hooks in `XServerDisplayActivity`. | WinNative lacks GameNative’s Amazon launch distinction and consumer-facing compatibility overlays; GameNative lacks WinNative’s explicit cross-launch sync helper. |
| Runtime / containers / contents | Both retain container, contents, and Winlator-derived runtime management. | Storefront flow is better connected to runtime launch entrypoints, test graphics, and per-game actions. | Setup wizard, preferred-container selection, runtime prerequisite prompting, explicit content installation flow, and prefix repair are more first-class. | GameNative lacks a first-run setup wizard; WinNative lacks GameNative’s tighter storefront-to-launch polish. |
| Settings / downloads paths / storage | Both support external storage usage and Steam download server settings. | Has per-game move-to-external / move-to-internal actions and stronger storefront-centric storage handling. | Has shared vs per-store download-folder settings surfaced in `StoresScreen`, plus document-provider exposure of Winlator files. | WinNative lacks GameNative’s checked-in per-game storage move actions; GameNative lacks WinNative’s shared/per-store download folder UX. |
| UI architecture | Both use Compose somewhere in the product. | More Kotlin-heavy and more consistently Compose-fronted in the app-facing surface. | Explicit mixed-mode architecture: Compose where helpful, classic fragments / dialogs / view binding where Winlator shell tooling remains useful. | GameNative lacks some shell breadth; WinNative lacks a consistently modernized frontend. |
| Support surface | Both link Discord/community support. | Privacy policy is checked into the repo and release/support workflows are richer. | README is more contribution-oriented for external PRs. | WinNative lacks an in-repo privacy policy in this clone; GameNative is less contributor-inviting in README policy. |

## Storefronts and account integrations

### Shared

Both repos clearly implement full stacks for:

- **Steam**
  - GameNative: `app/src/main/java/app/gamenative/service/SteamService.kt`
  - WinNative: `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/steam/service/SteamService.kt`
- **GOG**
  - GameNative: `app/src/main/java/app/gamenative/service/gog/...`
  - WinNative: `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/gog/service/...`
- **Epic**
  - GameNative: `app/src/main/java/app/gamenative/service/epic/...`
  - WinNative: `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/epic/service/...`

Both also carry the same broad shape of supporting objects around those stores:

- auth manager / auth client
- background service
- download manager
- cloud save manager for GOG and Epic
- DAO / DB-backed models
- OAuth activity for GOG and Epic

### GameNative advantage: full Amazon Games implementation

GameNative ships a real Amazon Games feature stack in the checked repo, including:

- `app/src/main/java/app/gamenative/ui/screen/auth/AmazonOAuthActivity.kt`
- `app/src/main/java/app/gamenative/service/amazon/AmazonService.kt`
- `app/src/main/java/app/gamenative/service/amazon/AmazonDownloadManager.kt`
- `app/src/main/java/app/gamenative/service/amazon/AmazonSdkManager.kt`
- `app/src/main/java/app/gamenative/db/dao/AmazonGameDao.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/AmazonAppScreen.kt`
- `app/src/main/java/app/gamenative/data/AmazonGame.kt`

It is not just a label. The checked code covers:

- OAuth
- library sync
- install/download support
- launch path resolution
- Wine-prefix SDK deployment for Amazon-specific runtime files
- Amazon-specific library tab / app screen integration

### WinNative limitation: Amazon appears present in UI copy, but not as a full stack

WinNative contains Amazon-facing strings and UI placeholders, for example:

- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/UnifiedActivity.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/StoresScreen.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/res/values/strings.xml`

But the checked Java/Kotlin tree contains **no Amazon-named implementation files** comparable to GameNative’s Amazon stack.

The most concrete checked-in behavior is placeholder-like:

- an Amazon tab can be built into the unified hub
- `StoresScreen` shows an **Amazon Games** card marked `isComingSoon = true`
- folder settings include **Amazon Downloads**

So the safest conclusion is:

- **GameNative has real Amazon Games implementation**
- **WinNative has partial Amazon-facing surface area, but not an equivalent checked-in backend implementation**

## Library and navigation surfaces

### Shared

Both apps support the same broad library ideas:

- controller-friendly navigation
- carousel/list/grid style presentation
- store-separated and mixed library views
- search/filtering concepts
- custom/local game support
- shortcut creation

### GameNative edge: more specialized storefront-library frontend

GameNative’s library stack is more decomposed and frontend-specific. Evidence includes:

- `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryCarouselPane.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibrarySearchBar.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryTabBar.kt`

Notable strengths:

- library-specific composables are cleaner and more specialized
- explicit carousel/grid/list separation
- compatibility badges and compatibility-cache plumbing show up in the library flow
  - `app/src/main/java/app/gamenative/ui/component/CompatibilityBadge.kt`
  - `app/src/main/java/app/gamenative/utils/GameCompatibilityService.kt`
  - `app/src/main/java/app/gamenative/utils/GameCompatibilityCache.kt`
- storefront tabs include Steam, GOG, Epic, Amazon, and Local in one consistent library model

### WinNative edge: broader unified hub shell

WinNative’s newer Compose shell is concentrated in `UnifiedActivity.kt`, which builds:

- **Library** tab
- **Downloads** tab
- **Store** tab, or separate Steam / Epic / GOG / Amazon tabs depending on mode

Evidence:

- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/UnifiedActivity.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/ui/CarouselView.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/ui/ListView.kt`

Notable strengths:

- unified shell can build tabs dynamically (`Library`, `Downloads`, `Store`, or store-specific tabs)
- includes AIO-vs-per-store tab logic
- downloads are treated as a first-class peer to library browsing
- older `HubActivity` and `BigPictureActivity` still provide alternate library shells

### Important asymmetry

GameNative still defines `HomeDestination.Library`, `HomeDestination.Downloads`, and `HomeDestination.Friends`, but `HomeScreen.kt` currently always renders `HomeLibraryScreen`.

Evidence:

- `app/src/main/java/app/gamenative/ui/enums/HomeDestination.kt`
- `app/src/main/java/app/gamenative/ui/screen/HomeScreen.kt`

So in the current checked tree:

- **WinNative has a clearly implemented unified Downloads tab in the main Compose hub**
- **GameNative enumerates broader home destinations but currently behaves as a library-first home shell**

## Downloads and installs

### Shared

Both repos clearly support:

- resumable downloads
- per-download cancel flow
- Wi‑Fi / LAN-aware download pausing for Steam and other stores
- active download tracking per store

Evidence exists in both repos’ Steam / GOG / Epic service trees.

### GameNative strength: polished per-game install surfaces

GameNative’s install / resume / cancel flows are tightly integrated into store-specific app screens:

- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/GOGAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/EpicAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/AmazonAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt`

This is especially notable because it extends through Amazon as well, not just Steam/GOG/Epic.

### WinNative strength: unified downloads command center

WinNative has the stronger checked-in **global downloads UX**.

Evidence:

- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/UnifiedActivity.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/service/DownloadService.kt`

Checked-in capabilities include:

- `DownloadService.getAllDownloads()` across Steam / Epic / GOG
- `pauseAll()` / `resumeAll()` / `cancelAll()`
- `pauseDownload(id)` / `resumeDownload(id)` / `cancelDownload(id)`
- `clearCompletedDownloads()`
- a Downloads tab with:
  - queue-size control
  - selection-aware buttons
  - pause all / resume all
  - cancel all / cancel selection
  - clear completed / cancelled

### Relative lack

- **GameNative lacks an equivalent checked-in cross-store downloads control surface** like WinNative’s Downloads tab + global `DownloadService` façade.
- **WinNative lacks GameNative’s Amazon install/download path**, so even where both are strong, GameNative covers more storefronts end-to-end.

## Cloud saves and sync behavior

### Shared

Both repos carry the same broad cloud-save shape for the stores that visibly support it:

- Steam Auto Cloud
- GOG cloud saves manager
- Epic cloud saves manager
- sync result enums / result messaging
- launch-time sync hooks and conflict-related strings

Evidence examples:

- GameNative:
  - `app/src/main/java/app/gamenative/service/SteamAutoCloud.kt`
  - `app/src/main/java/app/gamenative/service/gog/GOGCloudSavesManager.kt`
  - `app/src/main/java/app/gamenative/service/epic/EpicCloudSavesManager.kt`
  - `app/src/main/java/app/gamenative/enums/SyncResult.kt`
- WinNative:
  - `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/steam/service/SteamAutoCloud.kt`
  - `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/gog/service/GOGCloudSavesManager.kt`
  - `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/epic/service/EpicCloudSavesManager.kt`
  - `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/steam/enums/SyncResult.kt`

### GameNative edge

GameNative’s checked storefront/frontend layer exposes cloud-save concerns very directly in user-facing library flows and launch dialogs.

Examples visible in `app/src/main/res/values/strings.xml` and app-screen code include:

- cloud sync success / failure messages
- save-conflict and "launch anyway" messaging
- sync-in-progress launch prompts
- Amazon-specific skip-cloud-sync behavior because Amazon does not support it in this integration

### WinNative edge

WinNative adds a more explicit launch-shell helper layer via:

- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/CloudSyncHelper.kt`
- launch-time sync handling inside `XServerDisplayActivity.java`

So relative to GameNative, WinNative’s checked shell makes cloud sync feel slightly more centralized around the runtime launch path.

### Relative lack

- **GameNative lacks WinNative’s explicit `CloudSyncHelper` façade.**
- **WinNative lacks GameNative’s Amazon-specific launch distinction and broader storefront-facing polish around compatibility + app-screen integration.**
- **Neither repo shows Amazon cloud saves as a supported, end-to-end capability.** In GameNative, Amazon is explicitly treated as a no-cloud-sync launch path.

## Runtime, containers, contents, and setup

### Shared

Both repos are still fundamentally backed by Winlator-style runtime/container infrastructure:

- `ContainerManager`
- `ContentsManager`
- XServer runtime / renderer / env-var code
- content profiles for Wine / Proton / box64 / related pieces

This is visible throughout both source trees.

### GameNative edge

GameNative keeps these features connected to its storefront shell via modern settings/dialog surfaces such as:

- `app/src/main/java/app/gamenative/ui/screen/settings/DriverManagerDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/settings/ContentsManagerDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/settings/WineProtonManagerDialog.kt`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`

It also exposes storefront-adjacent runtime actions that matter to players, including:

- launch as container / test graphics pathing from the app frontend
- per-game storage movement actions
- compatibility context in the library itself

### WinNative edge

WinNative makes runtime/bootstrap management much more first-class in the app shell.

Evidence:

- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/SetupWizardActivity.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/SetupWizardDriversDialogFragment.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/ContentsFragment.kt`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/ContainerDetailFragment.java`
- `/Users/danhimebauch/Developer/WinNative/app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java`

Checked-in WinNative strengths include:

- first-run setup wizard
- default / preferred container selection
- prompting users to install Wine/Proton or create a usable container before launch
- more explicit contents/driver bootstrap flow
- runtime repair hooks such as Wine-prefix repair
- wider sense that the app is managing an emulator environment, not just launching storefront games

### Relative lack

- **GameNative lacks WinNative’s setup wizard and explicit first-run runtime bootstrap flow.**
- **WinNative lacks GameNative’s tighter storefront-facing integration around library polish and compatibility presentation.**
