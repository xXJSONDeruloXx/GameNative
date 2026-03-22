# Package renaming / spoofed package investigation

Date: 2026-03-22
Repo: `/Users/danhimebauch/Developer/GameNative`

## Scope

Investigate whether GameNative can be safely renamed to another Android package / app ID (Antutu, Ludashi, PUBG, Wild Rift, Genshin, etc.), using:

- the linked Discord general message and nearby discussion
- related Discord discussion in `general`, `development`, and threads
- current GameNative source tree and shipped assets
- related GitHub issue/PR history where available

## Search method

### Discord
- Linked message: `general` `1412756778159964201` / message `1485360145121804460`
- Searched guild-wide for: `app.name`, `package name`, `appid`, `rename`, `hard coded`, `antutu`, `ludashi`, `pubg`, `wildrift`, `framegen`, `genshin`
- Inspected these relevant threads/messages:
  - `Package Naming chat` thread `1479449383027212451`
  - `How to PR?` thread `1481326067858804756`
  - `PR Keep/Kill thread` `1481270625657159821`
  - linked general discussion on 2026-03-22

### Code audit
- Searched the repo for:
  - `applicationId`, `namespace`, `app_name`
  - hardcoded `app.gamenative`
  - hardcoded `/data/data/app.gamenative`
  - hardcoded launch actions / component names
- Inspected compressed shipped assets for embedded `app.gamenative` paths
- Reviewed related GitHub PR / issue:
  - Issue `#618`: `Hardcoded /data/data/app.gamenative paths break variant builds`
  - PR `#585`: `fix: replace hardcoded app ID paths with BuildConfig.APPLICATION_ID`

## Discord findings

### 1) Current user/dev sentiment: renaming is known-bad, not a new surprise

Linked general discussion on 2026-03-22:
- `1485360145121804460` — pepelespooder: “idk what theyve done but litterally as soon as you change the app.name it breaks everything”
- `1485360212318748753` — “bionic works once renamed and like everything is relinked”
- `1485360844031262771` — “and it still doesnt allow you to use glibc once the name is changed”
- `1485361066119659662` — “i relinked the .so i even replaced the whole imagefs with my own server for download”
- `1485361574238486730` — “So many things are hardlinked … nearly impossiblet unless you know the code inside and out”
- `1485362831925842192` — “i just wanted to see why @max used the gamenative appid”
- `1485363017993551872` — “20-30 hours of work relinking all the elfs and stuff like that”

Older general messages line up with the same story:
- `1485357995906043995` — “holy crap... i forgot how much changing the appid breaks stuff”
- `1483521716159381665` — “renaming the app broke everything”
- `1483521814306095154` — “I can see what Gamenative performance just uses the same appid”
- `1483985629640659046` — aventrix warns some paths are hardcoded, so renaming/moving things breaks

### 2) The package spoofing idea is explicitly tied to OEM performance unlocks

From `Package Naming chat` (`1479449383027212451`):
- `1479452828782297168` — the412banner says newer Ludashi builds no longer hardcode package naming and can be package-edited while keeping Antutu/Ludashi/PUBG/Genshin benefits
- `1479453183687528601` — spacebubble confirms GameNative has references not just to app name, but files based on app name too
- `1479453357948276788` — spacebubble says a “quick and dirty blanket package name” build is possible, but proper support needs a chunky refactor
- `1479456533686259712` — the412banner explains the Antutu/PUBG spoof theory: OEMs may unlock more aggressive governors, scheduling, latency, or thermal behavior for benchmark/game package names
- `1479465569261453322` / `1479465604296605839` — “package name killer controller” / “Testing further killed it”

Related general messages:
- `1479506419068703005` — packesl: changing package to `com.riotgames.wildrift` can unlock framegen / upscaling on some phones
- `1466784119772938405` — user asks for PUBG/Genshin package version to enable frame interpolation
- `1482992438317420594` — psycho_ch says they’ve seen normal performance using the Antutu package name

### 3) Devs have already acknowledged hardcoded package constraints

- `1479448700345647255` (general) — spacebubble: goal is “remove hard-coding appNames from the sourcecode and having dynamic resolution of the appName”
- `1481328029673197779` (`How to PR?`) — “technical limitation regarding debug builds vs daily drive due to package-name hard-coded constraints”
- `1481288598702657568` (`PR Keep/Kill thread`) — “It’s all quite hard-coded and causes a bunch of issues. So we need smarter ways to deal”

### 4) Important wording clarification from Discord vs code

The linked message says `app.name`, but the code audit strongly suggests the real breakage is **not** the display label string (`@string/app_name`).

It is much more likely people are using “app.name” loosely to mean one of:
- Android `applicationId`
- package name
- namespace / component names
- absolute internal data path based on `app.gamenative`

## Code audit

## A. Changing the display label alone should be safe

`app_name` / `login_app_name` are used for UI/labels, not core runtime identity:
- `app/src/main/AndroidManifest.xml` → application / activity labels use `@string/app_name`
- `app/src/main/java/app/gamenative/service/NotificationHelper.kt`
- `app/src/main/java/app/gamenative/ui/screen/login/UserLoginScreen.kt`
- `app/src/main/java/app/gamenative/ui/component/dialog/ProfileDialog.kt`

I did **not** find runtime path logic keyed off `R.string.app_name`.

So if someone literally only changes the launcher label, that should not be the thing “breaking everything”.

## B. The repo still hardcodes `app.gamenative` in runtime-critical places

`app/build.gradle.kts`:
- `namespace = "app.gamenative"`
- `applicationId = "app.gamenative"`
- there is already a `release-gold` build type with `applicationIdSuffix = ".gold"`

That suffix alone is a clue: variant app IDs are already a known use case, but the rest of the codebase is not consistently prepared for it.

### Hardcoded absolute paths in source (runtime-relevant)

`rg` found these direct `/data/data/app.gamenative` references in source:

- `app/src/main/java/com/winlator/container/Container.java`
  - `MEDIACONV_*` env vars
  - `DEFAULT_DRIVES = ... E:/data/data/app.gamenative/storage`
- `app/src/main/java/com/winlator/core/DXVKHelper.java`
  - `DXVK_STATE_CACHE_PATH`
- `app/src/main/java/com/winlator/core/WineUtils.java`
  - E: drive repair path and storage path detection
- `app/src/main/java/com/winlator/winhandler/WinHandler.java`
  - controller shared-memory files: `gamepad.mem`
- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`
  - same controller shared-memory paths
- `app/src/main/cpp/extras/evshim.c`
  - same `gamepad*.mem` path baked into native code

This is enough by itself to explain:
- controller breakage
- E: drive issues
- DXVK cache path breakage
- mediaconv / helper path breakage

### Hardcoded app identity strings in source

- `app/src/main/AndroidManifest.xml`
  - `<action android:name="app.gamenative.LAUNCH_GAME" />`
- `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt`
  - `ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"`
- `app/src/main/java/app/gamenative/utils/ShortcutUtils.kt`
  - same hardcoded action for pinned shortcuts
- `app/src/main/java/app/gamenative/utils/IconSwitcher.kt`
  - hardcoded component names:
    - `app.gamenative.MainActivityAliasDefault`
    - `app.gamenative.MainActivityAliasAlt`
- service action constants are also package-scoped strings:
  - `GOG_SYNC_LIBRARY`, `EPIC_SYNC_LIBRARY`, `AMAZON_SYNC_LIBRARY`, etc.

This matters less than the absolute path problem, but it means package renaming is not centralized.

### There is already an inconsistency in launch action handling

- `IntentLaunchManager.kt` only accepts `app.gamenative.LAUNCH_GAME`
- `MainActivity.kt` has an error path checking `${BuildConfig.APPLICATION_ID}.LAUNCH_GAME`
- manifest still declares `app.gamenative.LAUNCH_GAME`

That’s not the root cause of the big breakage, but it shows package identity handling is already split-brain.

### Tests also assume the exact package name

`app/src/androidTest/java/com/utkarshdalal/PluviaGoldberg/ExampleInstrumentedTest.kt`
- asserts `appContext.packageName == "app.gamenative"`

Not a runtime blocker, but another sign rename support is incomplete.

## C. The really big finding: shipped binary assets also embed `app.gamenative`

This is the part that makes simple APK/package editing fail even after Java/Kotlin changes.

I scanned shipped compressed assets and found embedded `app.gamenative` paths in at least these archives:

1. `app/src/main/assets/redirect.tzst`
   - contains strings such as:
     - `app.gamenative/files/imagefs`
     - `/data/data/app.gamenative/files/imagefs/usr/tmp`
     - `/data/data/app.gamenative/files/imagefs/preload_loaded.txt`
     - `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so`

2. `app/src/main/assets/graphics_driver/turnip-25.2.0.tzst`
   - contains:
     - `"library_path": "/data/data/app.gamenative/files/imagefs/usr/lib/libvulkan_freedreno.so"`

3. `app/src/main/assets/graphics_driver/turnip-25.3.0.tzst`
   - same pattern

4. `app/src/main/assets/graphics_driver/vortek-2.0.tzst`
   - contains:
     - `"library_path": "/data/data/app.gamenative/files/imagefs/lib/libvulkan_vortek.so"`

5. `app/src/main/assets/graphics_driver/vortek-2.1.tzst`
   - contains:
     - `"library_path": "/data/data/app.gamenative/files/imagefs/usr/lib/libvulkan_vortek.so"`

6. `app/src/main/assets/box86_64/box64-0.3.4.tzst`
   - contains:
     - `/data/data/app.gamenative/files/imagefs/usr/lib/ld-linux-aarch64.so.1`

7. `app/src/main/assets/box86_64/box64-0.3.6.tzst`
   - same pattern

8. `app/src/main/assets/box86_64/box64-0.3.8.tzst`
   - same pattern

This is the strongest evidence for the “relink all the ELFs / replace imagefs” complaints in Discord.

Even if source code is cleaned up, these shipped assets can still pin the app to `app.gamenative`.

## D. Existing GitHub history matches the same diagnosis

### Issue #618
`Hardcoded /data/data/app.gamenative paths break variant builds`

Body summary:
- several Java files hardcode `/data/data/app.gamenative`
- breaks builds with different application IDs (debug suffixes, forks)

### PR #585
`fix: replace hardcoded app ID paths with BuildConfig.APPLICATION_ID`

PR body explicitly called out that hardcoded paths break:
- gamepad input
- Wine E: drive mount
- DXVK state cache
- mediaconv dump/transcoded paths

It also called out something extremely important:
- `evshim.c` / bundled native pieces need rebuilds in the imagefs, otherwise gamepad input remains broken even if source changes are applied.

That PR was later commented as superseded, but the current tree still clearly contains hardcoded `app.gamenative` source paths and multiple shipped asset archives with embedded paths.

## E. One commit appears to have reintroduced / entrenched some of this

`git blame` points many of the current hardcoded path lines to:
- commit `20ebeaf0` — `Initial bionic changes (#191)`

That commit introduced or reintroduced several of the current hardcoded path assumptions in:
- `Container.java`
- `WinHandler.java`
- `BionicProgramLauncherComponent.java`
- `evshim.c`

So at least part of the current breakage is not accidental folklore — it is traceable to concrete code changes in the current history.

## Known findings

### Known from Discord
- Users are actively trying package spoof variants for OEM performance unlocks (Antutu, PUBG, Ludashi, Wild Rift, Genshin).
- Devs have already acknowledged package-name hardcoding as technical debt.
- Users report controller breakage and broader failures after package renaming.
- At least one user says bionic can be made to limp along after heavy relinking, but glibc still fails.

### Known from code
- Display label (`app_name`) is not the real problem.
- Package/appId/path assumptions are hardcoded in source.
- Package/appId/path assumptions are also hardcoded in shipped binary assets.
- There is already repo history (issue/PR) documenting the same problem.

## Hypotheses / likely implications

### High confidence
1. **Changing only the Android package/app ID is currently not safe.**
   Too many runtime-critical paths still assume `app.gamenative`.

2. **Simple APK editor/package-name spoofing is insufficient.**
   The repo ships binary assets that still embed `app.gamenative` absolute paths.

3. **Controller breakage is very plausibly explained by the current code/assets.**
   `WinHandler.java`, `BionicProgramLauncherComponent.java`, and `evshim.c` all hardcode the shared `gamepad*.mem` location.

4. **glibc failures likely extend beyond the Kotlin/Java tree.**
   The source tree has fewer glibc-specific hardcodes than bionic, but the shipped archives (redirect, box64, graphics-driver bundles) still embed absolute paths. That matches the “relink all the ELFs / imagefs” complaints.

### Lower confidence / needs deeper binary inspection
1. There may be additional embedded `app.gamenative` paths in other binary assets not surfaced by quick `strings` scanning.
2. Some glibc breakage may come from ELF interpreter / RPATH / loader assumptions that only show up after unpacking or launching the real runtime.
3. Supporting side-by-side installs may need migration logic for persistent container metadata, not just path derivation.

## Is there a path forward?

## Yes, but it is a real project, not a one-line rename

### Minimum credible path

#### Phase 1 — source-level de-hardcoding
Create a single canonical app path / identity helper and replace runtime hardcodes with values derived from:
- `context.getFilesDir()` / `context.getDataDir()` / `context.packageName`
- `ImageFs.find(context).getRootDir()`
- `BuildConfig.APPLICATION_ID` only where a `Context` is genuinely unavailable

At minimum this has to cover:
- `Container.java`
- `DXVKHelper.java`
- `WineUtils.java`
- `WinHandler.java`
- `BionicProgramLauncherComponent.java`
- `evshim.c`
- launch actions / alias component handling (`IntentLaunchManager`, `ShortcutUtils`, `IconSwitcher`, manifest)

#### Phase 2 — rebuild shipped binary assets with dynamic paths
This is the part the Discord complaints are pointing at.

At minimum the following assets need review/rebuild/repack:
- `redirect.tzst`
- affected `box64-*.tzst`
- affected `turnip-*.tzst`
- affected `vortek-*.tzst`
- any imagefs/runtime bundles that still resolve library paths against `/data/data/app.gamenative/...`

If this phase does not happen, forks/variants will still randomly fail even after Java/Kotlin cleanup.

#### Phase 3 — migration / compatibility policy
Decide whether the goal is:
- **side-by-side dev/debug builds**, or
- **OEM spoof package variants**, or
- **both**

Those are related, but not identical.

You probably want:
- one-time migration for old container drive strings / cached paths
- possibly support for both legacy launch action and package-scoped launch action
- explicit CI-generated variants instead of asking users to hand-edit APKs

#### Phase 4 — test matrix
Need real device/runtime validation for:
- default package
- suffixed debug/dev build
- at least one spoof package build
- bionic + glibc
- controller input
- graphics driver loading
- front-end / external launch intents
- clean install vs upgrade vs side-by-side install

## Suggested product stance

If the team wants a real path forward, I would avoid “random APK editor spoofing” entirely and instead support one of these deliberately:

1. **dev/debug side-by-side package support** first (lowest-risk practical win)
2. then optionally **CI-generated spoof variants** for benchmark/game package names as experimental builds

That order matters. If side-by-side variant app IDs are not stable, Antutu/PUBG/Wild Rift/Genshin variants will be chaos.

## Bottom line

- **Changing the display app label is not the real issue.**
- **Changing package/app ID absolutely still breaks real runtime paths in current GameNative.**
- The breakage is not just folklore: it is directly visible in current source and in shipped binary assets.
- There **is** a path forward, but it requires both:
  - source refactoring, and
  - rebuilding/repacking shipped runtime assets
- So the honest answer is:
  - **yes, there is a path forward**
  - **no, it is not currently a “just rename the package” task**
  - **and glibc likely stays broken until the binary/imagefs layer is cleaned up too**

## Follow-up: upstream-owner repo audit

I checked repos under the upstream owner (`utkarshdalal`) to see which remaining hardcoded binaries are rebuildable from source.

### Rebuildable from upstream-owner repos

#### `utkarshdalal/box64`
This repo does contain the Box64 source and Android packaging workflow.

Strong evidence:
- `.github/workflows/release.yml` explicitly runs:
  - `patchelf --set-interpreter /data/data/com.winlator/files/imagefs/usr/lib/ld-linux-aarch64.so.1 ./box64`

So the hardcoded interpreter path in the shipped `box64-*.tzst` assets is not mysterious — it is being baked in at packaging time and can be rebuilt/fixed from this repo.

#### `utkarshdalal/bionic-vulkan-wrapper`
This repo contains source for the wrapper / Vulkan-side pieces and ICD generation logic.

Strong evidence:
- `src/freedreno/vulkan/meson.build`
  - generates `freedreno_icd.<arch>.json` via `vk_icd_gen.py`
- `src/vulkan/wrapper/graphics_env_hooks.cpp`
  - hardcodes:
    - `/data/data/com.winlator.cmod/files/imagefs/usr/lib`
    - `/data/data/com.micewine.emu/files/usr/lib`
- `src/vulkan/wrapper/wrapper_instance.c`
  - also logs / expects validation layers under `/data/data/com.winlator.cmod/files/imagefs/usr/lib/`

So the remaining wrapper / ICD hardcoding is also rebuildable from upstream-owner source, but would need a package-aware patch before rebuilding.

#### `utkarshdalal/vortek-patcher`
This looks like a patcher for Vortek binaries, not the full original Vortek implementation.

Useful, but not sufficient by itself to fully regenerate every Vortek-related shipped binary from first principles.

### Not found in upstream-owner repos

I did **not** find source for these remaining pieces in the upstream owner’s repos:
- `libredirect.so`
- `libredirect-bionic.so`
- `redirect.tzst` build source / packaging source
- any checked-in source for the old path-rewrite shim symbols like:
  - `preload_replace_bionic.c`
  - `old_pkg`
  - `new_pkg`
  - the `rewrite (openat): %s -> %s` style hooks seen in the shipped binary

I also did **not** find checked-in evshim source in the upstream-owner repos besides what already exists in GameNative itself.

### What that means

- **Box64 can likely be rebuilt from `utkarshdalal/box64`.**
- **The Vulkan wrapper / ICD artifacts can likely be rebuilt from `utkarshdalal/bionic-vulkan-wrapper`.**
- **The redirect/path-rewrite libs do not appear to be rebuildable from repos under the upstream owner alone.**

So I expanded the search outside the upstream owner namespace.

## Follow-up: external repo source mapping

I checked these additional repos:
- `coffincolors/winlator`
- `brunodev85/winlator`
- `pipetto-crypto/winlator`
- `leegao/bionic-vulkan-wrapper`
- `bylaws/libadrenotools`
- `ganyao114/libadrenotools`

### Source origins I could positively identify

#### 1) `libevshim.so`
This one is now clearly sourced.

Found in:
- `coffincolors/winlator`
  - `app/src/main/cpp/winlator/evshim.c`
  - `app/src/main/cpp/CMakeLists.txt` builds `evshim`

It hardcodes:
- `/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad%s.mem`

That matches the binary currently shipped in multiple Winlator-derived trees.

#### 2) `wrapper.tzst` / `wrapper-leegao.tzst`
These archives are a combination of:
- **wrapper runtime source** from `bionic-vulkan-wrapper`
- **hook/helper libs** from `libadrenotools`

From current GameNative assets, `wrapper.tzst` contains:
- `usr/lib/libvulkan_wrapper.so`
- `usr/lib/libadrenotools.so`
- `usr/lib/libhook_impl.so`
- `usr/lib/libmain_hook.so`
- `usr/lib/libfile_redirect_hook.so`
- `usr/lib/libgsl_alloc_hook.so`
- `usr/share/vulkan/icd.d/wrapper_icd.aarch64.json`

##### `libvulkan_wrapper.so`
Mapped to:
- `utkarshdalal/bionic-vulkan-wrapper`
- `leegao/bionic-vulkan-wrapper`

Relevant source files:
- `src/vulkan/wrapper/wrapper_instance.c`
- `src/vulkan/wrapper/graphics_env_hooks.cpp`

These contain hardcoded package paths like:
- `/data/data/com.winlator.cmod/files/imagefs/usr/lib`
- `/data/data/com.micewine.emu/files/usr/lib`

##### `libmain_hook.so`, `libfile_redirect_hook.so`, `libgsl_alloc_hook.so`, `libhook_impl.so`
Mapped to:
- `bylaws/libadrenotools`
- `ganyao114/libadrenotools`

Relevant source files:
- `src/hook/main_hook.c`
- `src/hook/file_redirect_hook.c`
- `src/hook/gsl_alloc_hook.c`
- `src/hook/hook_impl.cpp`
- `src/hook/CMakeLists.txt`

So the helper libs inside wrapper archives are rebuildable from public source.

#### 3) Box64 assets
Mapped to:
- `utkarshdalal/box64`
- also historically mirrored in Winlator repos as packaged artifacts

Strongest evidence remains:
- `utkarshdalal/box64/.github/workflows/release.yml`
  - runs `patchelf --set-interpreter /data/data/com.winlator/files/imagefs/usr/lib/ld-linux-aarch64.so.1 ./box64`

This explains the hardcoded interpreter path found in packaged `box64-*.tzst` binaries.

#### 4) GLIBC-side hardcoded runtime paths
Mapped to:
- `utkarshdalal/wine-custom`

This repo contains multiple hardcoded app-specific Android paths in source and packaging patches, including:
- `/data/data/app.gamenative/files/imagefs/...`
- `/data/data/com.utkarshdalal.PluviaGoldberg/files/usr/...`

Examples found in:
- `server/request.c`
- `dlls/ntdll/unix/server.c`
- `programs/winebrowser/main.c`
- many `packages/*` patch files

This is the strongest evidence so far that **glibc rename breakage is not just in GameNative Java/Kotlin/assets** — it is also upstream in the custom Wine source/patch layer.

### Still unresolved / not found in searched public repos

I still could **not** find public source for the `redirect.tzst` preload libs used by current GameNative.

Git history does at least narrow down when they entered this repo:
- `app/src/main/assets/redirect.tzst` was introduced in commit `20ebeaf0` (`Initial bionic changes (#191)`)
- `app/src/main/jniLibs/arm64-v8a/libredirect-bionic.so` first appeared earlier in commit `2df2b427` (`Progress - patched libvortekrenderer and now it doesn't crash but i get a black screen hehe`)

But I still did not find corresponding checked-in source files in this repo history.

Current binary evidence from `redirect.tzst`:
- `libredirect.so` contains source filename string: `preload_replace.c`
- `libredirect-bionic.so` contains source filename string: `preload_replace_bionic.c`
- `libredirect-bionic.so` also exposes symbols / strings:
  - `old_pkg`
  - `new_pkg`
  - `open_common`
  - `is_event_node`
  - `rewrite (openat): %s -> %s`
- `libredirect.so` contains:
  - `pluviagoldberg_on_load`
  - `libpluviagoldberg.so`
  - `preload_loaded.txt`

I searched:
- upstream owner repos
- major external Winlator forks above
- GitHub code search via `gh`

and did **not** find a public repo containing:
- `preload_replace.c`
- `preload_replace_bionic.c`
- `pluviagoldberg_on_load`
- the exact rewrite-hook source matching those binaries

### Redirect shim timeline / provenance hints

Even though I still have not found the source, repo history gives a fairly strong provenance trail.

#### GLIBC-side timeline

- `e1f09f22` (`It works!`, 2025-05-17)
  - `GlibcProgramLauncherComponent.java` starts setting:
    - `LD_PRELOAD="libpluviagoldberg.so libandroid-sysvshm.so"`
  - This is the earliest concrete repo-history evidence I found for the GLIBC preload shim.

- `2e936d7e` (`Updated name of preload`, 2025-05-27)
  - changes:
    - `libpluviagoldberg.so` → `libredirect.so`
  - This is the clearest in-repo hint that the current GLIBC redirect shim is a renamed / repackaged descendant of an older **PluviaGoldberg-specific preload library**.

- `00889106` (`Run steamless on executable before running`, 2025-06-11)
  - continues to preload `libredirect.so libandroid-sysvshm.so`
  - confirms the renamed GLIBC shim remained part of the standard launch path.

- `1f9018ca` (`fix(glibc): ... VirGL ... Library path fix`, 2026-03-09)
  - still treats `libredirect.so` as operationally important
  - commit message explicitly says VirGL on GLIBC depended on `libredirect.so` path translation and that preload failures broke socket discovery.

#### Bionic-side timeline

- `2df2b427` (`Progress - patched libvortekrenderer and now it doesn't crash but i get a black screen hehe`, 2025-07-15)
  - first adds:
    - `app/src/main/jniLibs/arm64-v8a/libredirect-bionic.so`
  - this commit currently survives only on branch/ref `upstream/new_vortek`, which suggests the bionic redirect shim first appeared in an experimental Vortek/bionic work stream rather than landing directly as a standalone source addition.

- `b548a30e` (`Initial bionic changes`, 2025-10-06)
  - introduces `BionicProgramLauncherComponent.java`
  - at this point the new bionic launcher is wiring `LD_PRELOAD`, but not yet clearly loading the redirect shim asset bundle.

- `2c25981f` (`Got aarch64 proton working with LD_PRELOAD`, 2025-10-18)
  - adds `imageFs.getLibDir() + "/libredirect-bionic.so"` to the bionic `LD_PRELOAD` chain
  - this is the first clear launcher-side use of the bionic redirect shim.

- `451ca4a1` (`Fixed wowbox64 for arm64ec bionic containers`, 2025-10-20)
  - adds `app/src/main/assets/redirect.tzst`
  - also adds `redirect.tzst` deployment wiring in `ImageFsInstaller`
  - this is the first point where the redirect bundle is clearly shipped as an asset archive rather than just as a loose checked-in `.so`.

- `20ebeaf0` (`Initial bionic changes (#191)`, 2025-10-23)
  - lands the bionic stream into mainline history
  - carries forward `redirect.tzst` and the bionic launcher integration.

#### Later maintenance touches

After the binaries landed, later commits mostly changed **how the app loads them**, not the binary payloads themselves. Notable examples:
- `cd804388` (2026-03-04): adjusts bionic/glibc launcher `LD_PRELOAD` handling
- `b23731ea` (2026-03-04): more launcher-side preload-path handling and comments about `libredirect.so`
- `1f9018ca` (2026-03-09): switches GLIBC preload handling to absolute library paths for reliability

So the binaries themselves look comparatively opaque/static, while the Java-side plumbing around them kept evolving.

#### Strongest Pluvia / upstream provenance hints

These are the strongest hints I found that the redirect shim was inherited or renamed from Pluvia/PluviaGoldberg-era work:

1. **The old preload name was literally `libpluviagoldberg.so`.**
   - Proven directly by commit `e1f09f22`.
   - Commit `2e936d7e` then renames that preload to `libredirect.so`.

2. **The current `libredirect.so` binary still contains PluviaGoldberg-era symbols/strings.**
   - `pluviagoldberg_on_load`
   - `[INIT] libpluviagoldberg.so loaded`
   - `LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so`
   - `preload_loaded.txt`

3. **Repo history shows broader Pluvia lineage and imports nearby.**
   - history includes merge references to `https://github.com/utkarshdalal/PluviaGoldberg`
   - branch/commit `upstream/fix-pathing-issue` is described as `pulled in fix-pathing-issue from pluvia`
   - another upstream branch references `pull in improvements from https://github.com/oxters168/Pluvia/pull/275/files`

4. **The repo itself still has substantial PluviaGoldberg naming residue.**
   - package paths and tests under `com/utkarshdalal/PluviaGoldberg`
   - older class/package naming in history

#### Best current conclusion on provenance

My current best explanation is:
- the **GLIBC redirect shim** was very likely introduced first as a PluviaGoldberg-specific preload library (`libpluviagoldberg.so`)
- it was later **renamed/repackaged** to `libredirect.so`
- the **bionic redirect shim** (`libredirect-bionic.so`) appears to have been added later during experimental Vortek/bionic work
- both were then bundled together into `redirect.tzst`
- but the actual C source files (`preload_replace.c`, `preload_replace_bionic.c`) still do not appear in the public repos I searched

So there is strong evidence of **inheritance/renaming**, but not yet a public source repo I can point to as the definitive origin.

### Current best map of the remaining binary problem

- **Found / source-known**
  - `libevshim.so` → `coffincolors/winlator`
  - wrapper Vulkan runtime → `bionic-vulkan-wrapper`
  - wrapper hook libs → `libadrenotools`
  - `box64` packaging/interpreter hardcoding → `utkarshdalal/box64`
  - glibc hardcoded paths → `utkarshdalal/wine-custom`

- **Not yet found publicly**
  - `libredirect.so`
  - `libredirect-bionic.so`
  - the build source for `redirect.tzst`

### Bottom line after the wider repo search

At this point the remaining blockers are more precisely understood:

1. **Bionic package-rename support is partially unblockable from public source**
   - evshim source is public
   - wrapper/hook sources are public
   - box64 source/packaging is public

2. **GLIBC package-rename support is definitely blocked by upstream custom Wine patches too**
   - those patches are public, and they contain hardcoded app paths

3. **One important preload/redirect layer is still source-missing in public repos searched so far**
   - the `redirect.tzst` libs are still the main unresolved binary-source gap
