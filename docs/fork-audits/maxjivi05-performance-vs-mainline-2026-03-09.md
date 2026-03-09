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

This does **not** automatically make the runtime changes bad, but it is a strong signal that any adoption into proper GameNative must be done as **selective extraction**, never as a merge.

## Provisional conclusion so far

This fork should be treated as a **feature quarry**, not a merge target.

## Current findings checkpoint

### 2026-03-09 14:34

A very important correction versus the fork README: a surprising amount of the fork's headline feature list is **already present in current GameNative master** in some form.

Confirmed current mainline already contains code for things like:

- master containers
- app-specific container overrides
- custom image management
- save import/export tooling
- download folder picker helpers
- surface-format selection
- global in-game HUD / native-rendering persistence
- in-game navigation menu support
- launch dependency abstractions
- cloud-save platform abstractions

So the fork README is **not a good novelty map** when comparing against today's proper GameNative.

### 2026-03-09 14:38

After deeper file-level comparison, the areas that still look meaningfully distinct are:

1. **More aggressive master-container implementation**
   - `PrefManager.masterContainers`
   - `PrefManager.gameContainers`
   - `PrefManager.appSpecificConfigs`
   - dynamic per-game A: drive remounting
   - `ManageContainersDialog`
   - interception of container `saveData()` to prevent shared-container pollution

2. **A more opinionated Components Manager**
   - custom filtering / categorization for:
     - stable
     - nightly
     - gplasync
     - arm64ec
     - nvapi
     - sarek
   - more custom install / uninstall flows
   - heavier bespoke version parsing

3. **ImageFs install hardening**
   - retry loop on install
   - more forceful directory clearing
   - package redirection symlink setup

4. **Device-specific performance tuning**
   - root performance mode
   - non-root Adreno clock forcing
   - runtime launcher integration for those toggles

5. **Controller-first / in-game menu redesign**
   - substantial changes in `GameNavigationMenu`, `XServerScreen`, and `NavigationDialog`

6. **ALSA / audio-layer changes**
   - substantial native diff in `alsa_client.c`
   - likely targeted at crash / unsatisfied-link resilience, but needs more scrutiny before recommending

### 2026-03-09 14:42

First-pass recommendation quality by area:

- **Master-container ideas:** promising, but high-risk / invasive
- **Components filtering logic:** probably worth selectively porting
- **ImageFs retry / cleanup hardening:** promising and likely portable in pieces
- **Performance tuner:** probably **not** suitable for proper upstream GameNative as-is
- **ALSA native changes:** unclear / needs careful validation before any recommendation
- **Menu redesign:** mostly UX taste unless it solves concrete usability bugs

### 2026-03-09 14:45

Another important finding: the fork is missing a lot of newer mainline/upstream work.

Compared with current mainline, the fork still lacks many already-landed fixes and improvements, including recent upstream-side commits such as:

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

## Next analysis steps

Still to finish in detail:

1. separate **already-in-mainline** features from truly unique ones
2. inspect the master-container implementation in more depth
3. inspect unique components-manager logic for portable snippets
4. inspect ImageFs hardening for cherry-pickable pieces
5. inspect ALSA changes enough to say whether they are good, risky, or junk
6. write final recommendation table with “good / maybe / no” for each feature area
