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
- [ ] storefronts and account integrations
- [ ] library and navigation surfaces
- [ ] downloads and installs
- [ ] cloud saves and sync
- [ ] runtime / container / contents management
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

## Notes for next sections

The remaining sections will turn the initial observations above into a thorough feature-by-feature matrix with explicit "shared", "GameNative only", "WinNative only", and "partial / placeholder" calls.
