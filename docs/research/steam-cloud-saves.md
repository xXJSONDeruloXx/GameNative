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
