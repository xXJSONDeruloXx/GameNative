# Steam cloud save research

Last updated: 2026-03-10
Branch: `research/steam-cloud-saves`
Primary source thread: Discord `# Fix Steam cloud saves!` (`1378308569287622737/1440589008303292506`)

## Scope

This note captures research for fixing remaining Steam cloud save issues on top of `upstream/master`.

Research inputs so far:
- Discord thread review via the Discord desktop app (OCR snapshots across the thread timeline)
- code audit of current `upstream/master`
- inspection of historical and unmerged cloud-save work in local refs / PR heads

## Discord timeline and reported symptoms

### 2026-02-14

- `MUSH` reported a case where a save was successfully found again: _"The save is there! Thank you man!!!"_
- `Hector Manuel` replied immediately after with the complementary failure mode: _"Still not uploaded"_.
- `Heroxoot` asked whether custom save locations were only available in a nightly build.
- `Yeather Reets` reported **Telltale's The Walking Dead** still not syncing.
- `Utkarsh Dalal` asked for **remote storage screenshots** and confirmed save locations were still **hardcoded**.

### 2026-02-15

- `oracletoes` requested a way to **enable/disable Steam save syncing** per game.
- `Yeather Reets` repeated that **The Walking Dead** was still not syncing.
- `bazz` reported a concrete **Yakuza 4 / series** issue:
  - remote storage contains `.sav` files directly under `remote`
  - `remote/save` contains `index.sav`
  - GameNative was only picking up `remote/save/index.sav`
  - a Ludusavi manifest handled the title correctly
- `bazz` also summarized an important hypothesis from Steam docs reading:
  - some games depend on **Steam Cloud API behavior baked into the game**, not just Steam Auto-Cloud path manifests
  - UFS / Auto-Cloud metadata can therefore be **incomplete** for certain titles
  - Ludusavi can fill in some of those blind spots

### 2026-02-16

- `Mati` reported **Cult of the Lamb** still not working.
- `Utkarsh Dalal` responded: _"interesting. let's discuss"_.
- Later that day, a draft PR was referenced for easier discovery.

### 2026-02-17

- `Cilantro Limewire xXJSONDeruloXx bazz` said they had opened **draft PR #550** for easier discovery, but considered the code not yet merge-ready.
- `Devon` reported **Vampire Survivors** cloud saves not syncing on `0.7.2`, and suspected a mismatch around account identity / save location because the in-game account presentation differed from desktop Steam.

### 2026-02-18

- `Yeather Reets` reported **Shadow Warrior** also not pulling saves correctly.

### 2026-02-20

- `silentrald` asked for the **remote save location** and requested **logcat logs** for download failures.
- `Remph` described **ASTLIBRA** getting stuck with a `DownloadFail` error.
- `Ziad` reported a particularly useful path-shape clue for **Megabonk**:
  - two save folders existed
  - the `765611...` folder uploaded to Steam
  - the `0` folder did not
  - the `0` folder looked like the active save location inside the container
  - this correlated with a **cloud pending error**
- This suggests at least one class of bugs around **account-id placeholder substitution**, **multiple valid save roots**, or **games using a runtime folder different from the folder Steam expects**.

### 2026-02-25 to 2026-02-26

- Thread discussion moved toward **Ludusavi integration**, manual backup / import-export, or Syncthing as mitigations.
- `gonekrazy3000` reported **Rogue Prince of Persia** downloading saves to the correct place inside the game folder, but the game still could not read them inside GameNative even though the same cloud sync worked on desktop / Steam Deck.
- That is a strong signal that some failures are **not transport failures**; the files arrive, but the game cannot resolve them at runtime.

### 2026-03-03

- `randomseer` dropped a **Turnip Boy** crash report and relayed another thread claim that the issue might be tied to **large cloud save size**.
- Treat this as an **unverified hypothesis**, not a confirmed root cause.

### 2026-03-04

- `Laural` listed more affected titles:
  - **Mad Father**
  - **Sonic Adventure 1**
  - **Sonic Adventure 2**
  - **Freedom Planet**
  - **The Witch's House**
  - **Pocket Mirror GoldenerTraum**
  - **Scribblenauts Unlimited / Unmasked**
  - **The Wardrobe**

### 2026-03-08 to 2026-03-09

- `rootchord` reported **Costume Quest** refusing to sync back after replacing an out-of-sync Steam Deck save with a local backup.
- Another report for **Esoteric Ebb** described newer saves intermittently going missing on device.
- `Ichigo_Live` reported **Witcher 3** uploads not working on AYN Thor while downloads still worked.
- Another user immediately confirmed the same upload-side failure.
- `Heroxoot` reported **Sonic Lost World** was fixed for them, but **Cult of the Lamb** still was not.
- `kiequoo` reported **New Star GP** stores saves in the same directory as the game install and linked **PR #775**, which explains that GameNative downloads the file correctly but the game cannot find it because `ExeRunDir` is left blank in `ColdClientLoader.ini`.

## Failure buckets seen in the thread

From the reports above, the failures cluster into a few repeatable categories:

1. **Discovery / metadata mismatch**
   - UFS / Auto-Cloud metadata is incomplete or wrong for the title.
   - Example signals: Yakuza 4, Vampire Survivors, Cult of the Lamb, The Walking Dead, Shadow Warrior.

2. **Files are downloaded, but the game cannot read them**
   - The sync path is correct enough for transport, but runtime path / working-directory assumptions differ from Steam desktop behavior.
   - Example signals: Rogue Prince of Persia, New Star GP.

3. **Upload-only regressions**
   - Download works, but post-play upload does not.
   - Example signals: Witcher 3, early "still not uploaded" reports.

4. **Account-id / placeholder / multiple-root edge cases**
   - Different folders are used for active saves vs cloud-managed saves.
   - Example signals: Megabonk `0` folder vs `765611...` folder.

5. **State-loss / conflict / pending-operation issues**
   - Save swaps, out-of-sync states, or pending remote operations cause confusing behavior.
   - Example signals: Costume Quest, cloud pending errors.

6. **Large-save or high-volume edge cases**
   - Mentioned in thread, but not yet reproduced in this research pass.
   - Example signal: Turnip Boy crash / "save size too large" anecdote.

## Early takeaways from the thread alone

- There is **not one Steam cloud bug**; there are several overlapping failure modes.
- A meaningful subset of reports point to **path semantics**, not network transport.
- Another subset point to **Steam metadata incompleteness**, which explains the Ludusavi experiments.
- The request for **per-game cloud disable / local-only mode** appears multiple times and is a practical mitigation even when the root cause is unknown.
- The thread already surfaced two high-value implementation tracks:
  - **PR #550**: Ludusavi-backed fallback work
  - **PR #775**: `ExeRunDir` / working-directory fix for game-root-relative saves

## Current upstream/master: what is already covered

Several Steam cloud fixes are already present on `upstream/master` and should not be re-discovered from scratch:

- **Missing / malformed `%GameInstall%` handling is already fixed**
  - Commit: `fe6c9e33` (`Fix/cloud save gameinstall utkarsh (#508)`)
  - Current code in `app/src/main/java/app/gamenative/service/SteamAutoCloud.kt` and `app/src/main/java/app/gamenative/data/UserFileInfo.kt` already handles:
    - `%GameInstall%` being returned inside `file.filename`
    - `.` / blank prefixes
    - upload / delete prefix formatting regressions

- **Completely missing UFS metadata already has a limited fallback**
  - Commit: `90cc7a3e` (`Fixed cloud saves for games with missing ufs (#344)`)
  - Current `SteamAutoCloud.syncUserFiles()` falls back to recursively scanning the Steam userdata root only when `saveFilePatterns` are entirely absent.

- **Game-specific save location remapping infrastructure exists**
  - Commit: `61bb7a95` (`Feat: Adds game-specific save location symlink support (#441)`)
  - `app/src/main/java/app/gamenative/utils/SteamUtils.kt` calls `ensureSaveLocationsForGames(...)`.
  - `app/src/main/java/app/gamenative/enums/SpecialGameSaveMapping.kt` currently contains only a very small registry (at the moment, just one title-specific mapping).

- **Auth-loss / session-replacement state preservation already landed**
  - Commit: `b2f80321` (`fix: preserve steam cloud sync state across auth loss (#791)`)
  - This reduces one class of save loss where cloud-sync bookkeeping was being cleared too aggressively.

## Current upstream/master: gaps still visible in code

### 1. `ColdClientLoader.ini` still leaves `ExeRunDir` blank

- File: `app/src/main/java/app/gamenative/utils/SteamUtils.kt`
- Current `writeColdClientIni()` still writes:
  - `Exe=<game exe path>`
  - `ExeRunDir=`
- This matches the **New Star GP** / **Rogue Prince of Persia** style report where files are downloaded correctly but a game opening saves relative to the game root cannot see them when the executable lives in a subdirectory.
- This is the exact issue addressed by **PR #775** (`pr-775`, commit `1e1d9d4e`).

### 2. Incomplete-but-non-empty UFS still has no fallback path on master

- File: `app/src/main/java/app/gamenative/service/SteamAutoCloud.kt`
- Current local file discovery behaves roughly like this:
  - if Windows `saveFilePatterns` exist, trust them
  - only if they do **not** exist at all, recursively scan Steam userdata
- That means titles like **Yakuza 4** can still fail when Steam metadata is present but only covers part of the true cloud save set.
- This matches the thread’s repeated theme that some games mix **Steam Auto-Cloud metadata** with **Steam Cloud API behavior inside the game**, leaving UFS as an incomplete source of truth.

### 3. Upstream/master currently has no Ludusavi path at all

- A repository search on current `upstream/master` turns up no active `Ludusavi` integration.
- The thread’s Ludusavi work therefore survives as **historical side work**, not as current master behavior.
- That makes **PR #550** / branch work especially relevant for titles where Steam metadata is incomplete.

### 4. Automatic exit sync is effectively silent to the user

- Files involved:
  - `app/src/main/java/app/gamenative/ui/model/MainViewModel.kt`
  - `app/src/main/java/app/gamenative/service/SteamService.kt`
- Current exit flow awaits `SteamService.closeApp(...)`, but `closeApp()` does not surface a rich result back to the UI.
- In practice this means **upload-only failures** can be hard for users to distinguish from success unless they manually inspect logs or run a manual force-sync.
- That lines up with thread reports like **Witcher 3 downloads fine, uploads fail**.

### 5. There is no test coverage for `ColdClientLoader.ini` working-directory behavior

- Existing cloud-save tests are concentrated around `SteamAutoCloud` path handling.
- There does not appear to be a regression test covering `writeColdClientIni()` or the runtime consequence of `ExeRunDir` being blank.
- That makes PR #775 low risk conceptually, but currently under-tested.

## Historical work worth reviving or forward-porting

### PR #775 — `ExeRunDir` fix

- Ref fetched locally as: `pr-775`
- Commit: `1e1d9d4e`
- Summary:
  - sets `ExeRunDir=steamapps\common\<gameName>`
  - aligns GameNative working-directory behavior with desktop Steam
  - directly addresses titles whose save files live relative to the game root while the executable sits in a subdirectory

### PR #550 / Ludusavi branch

- PR head: `ddfdb435` (`feat: add preferLudusavi option to SteamService and related components`)
- Earlier base commit: `90fced26` (`feat: initial ludusavi fallback and opt in toggle in container settings`)
- Follow-up fix: `9e5d5df5` (`fix: save ludusavi pref in container config`)
- Why it matters:
  - it is the clearest existing answer to the **Yakuza / incomplete UFS** class of problems
  - it gives users an explicit escape hatch when Steam metadata is not sufficient

### Per-game local-only / disable-cloud branch

- Commit: `f56fea59` (`feat: allow disabling of cloud saves per game`)
- Why it matters:
  - this was explicitly requested in-thread
  - even if root-cause fixes take time, a **local-only mode** is a practical safety valve for titles that repeatedly corrupt, overwrite, or fail to upload correctly

## Suggested priority order

1. **Land or recreate PR #775 first**
   - smallest, best-understood, highest-confidence fix
   - clear user evidence
   - easy to regression test with a title whose exe sits below the game root

2. **Add regression coverage for `ColdClientLoader.ini` generation**
   - unit test the emitted `ExeRunDir`
   - if possible, add an integration fixture around a subdirectory exe + root-relative save file

3. **Improve post-exit sync observability**
   - surface automatic upload result to the UI or persistent logs
   - make it obvious when auto-upload failed versus succeeded
   - this will shorten future research loops for titles like Witcher 3

4. **Forward-port Ludusavi fallback work behind an explicit toggle (or smart fallback)**
   - best fit for incomplete-UFS titles
   - especially relevant for Yakuza-like reports where master already has *some* path handling, but still lacks a better save manifest source

5. **Forward-port per-game cloud-disable / local-only mode**
   - useful as a safety mechanism even after other fixes land

6. **Grow the title-specific fixture / mapping corpus**
   - remote storage screenshots
   - failing app IDs
   - save root shape (`0`, `765611...`, `remote/save`, game-root-relative, etc.)
   - whether the title uses Steam Auto-Cloud only, or appears to depend on Steam Cloud API behavior

## Suggested validation matrix

| Title / signal | Failure class | Why it matters | Best current fix track |
| --- | --- | --- | --- |
| New Star GP | Files present, game cannot read them | Confirms working-directory mismatch | `ExeRunDir` / PR #775 |
| Rogue Prince of Persia | Downloads land, game still misses saves | Another working-directory / runtime-path candidate | `ExeRunDir` / runtime path audit |
| Yakuza 4 | UFS covers only part of remote set | Best evidence for incomplete metadata | Ludusavi fallback |
| Witcher 3 | Download works, upload fails | Best evidence for silent exit-sync failures | exit-sync observability + upload audit |
| Megabonk | `0` folder vs `765611...` folder | Good placeholder / multi-root fixture | account-id / multi-root investigation |
| Costume Quest | out-of-sync replacement confusion | Good conflict / pending-op fixture | conflict-state audit |
| Turnip Boy | possible large-save issue | Useful stress fixture, but unverified | size / quota / buffering audit |

## Working hypothesis after code + thread review

The remaining Steam cloud problems on `upstream/master` are likely the combination of:

- one **runtime working-directory bug** (`ExeRunDir`)
- one **metadata completeness problem** (UFS is sometimes not enough)
- one **product/visibility gap** (automatic upload failures are too quiet)
- a smaller set of **title-specific path quirks** that probably need either mappings or a better manifest source
