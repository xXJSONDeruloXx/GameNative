# Itch.io Storefront Compatibility Living Doc

Last updated: 2026-02-22  
Primary branch: `feat/itchio` (`77cb59e5bd883980c2e8854244121e6c305a106a`)  
Baselines: `origin/master` (`969110555f84b66786d10bb49d116cb30511c864`), `origin/feat/amazon-games-support` (`00af266e4ef29ea6a4051f46e5893e693ca8734a`)

## 1) Purpose
This file is the long-term working memory for itch.io integration in GameNative. It tracks:
- Current implementation state vs `origin/master`
- Parity against existing stores (Steam, GOG, Epic) and the Amazon PR branch
- Gaps vs itch.io's own ecosystem (itch app, butler, wharf, docs)
- Prioritized implementation proposals
- Session-by-session troubleshooting and progress artifacts

## 2) Operating Protocol (Progressive Updates)
Use this protocol every session so context compaction does not lose state.
All commands are designed to be copy-pasted verbatim.

### Session Start Checklist (Deterministic)
Run these commands **in order** at the start of every session:

```bash
# 1. Confirm branch, HEAD, and remote tracking
cd /home/kurt/GameNative
git branch --show-current           # expect: feat/itchio
git rev-parse --short HEAD          # record in session log
git log --oneline -1                # capture commit message

# 2. Check for upstream changes
git fetch origin
git log --oneline HEAD..origin/master | head -5   # any new master commits?

# 3. Capture build health (MUST pass before any code changes)
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20

# 4. Quick diff summary since last session
git diff --stat HEAD~1              # what changed in last commit?
git status --short                  # any uncommitted work?

# 5. Scan for unresolved exhaustive-when issues (regression guard)
grep -rn 'GameSource\.' app/src/main/java --include='*.kt' \
  | grep -i 'when' | grep -v 'ITCH' | head -20
```

After these commands, update the Status Dashboard section with current values.

### Session End Checklist (Deterministic)
Run these **before** closing the session:

```bash
# 1. Build verification
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5

# 2. Run lint check (if available)
./gradlew :app:lintDebug --no-daemon 2>&1 | tail -10

# 3. Capture diff for session log
git diff --stat
git diff --name-only

# 4. Stage, commit, and push (see Section 17 for commit message format)
# Only if self-assessment gates pass (see Section 16)
```

Then update in this document:
- [ ] Status dashboard counts
- [ ] Move completed items across phase checklists
- [ ] Append troubleshooting notes and outcomes
- [ ] Add new citations to the citation index
- [ ] Append session log entry

### Short Session Log Template
```md
### Session YYYY-MM-DD (owner)
- Branch/HEAD: feat/itchio / <short-sha>
- Build at start: PASSING | FAILING
- Scope:
- Commands run:
- Findings added:
- Changes made:
- Validation (build): PASSING | FAILING
- Self-assessment gate: PASSED | BLOCKED (reason)
- Commit SHA:
- Next handoff:
```

## 3) Scope Baseline

### 3.1 Branch Delta vs `origin/master`
Current itch branch delta is large and broad: 27 files changed, 3954 insertions, 7 deletions, including DB schema v13, new `service/itch/*`, DAO/entity additions, settings integration, library model/filter plumbing, and app-screen wiring. [L-01]

### 3.2 Branch Delta vs Amazon PR Branch
Amazon PR branch shows mature parity patterns across source filter chips, installed-count inclusion, container source extraction, prelaunch executable handling, and app-screen download info wiring that itch branch can mirror structurally. [A-01] [A-02] [A-03] [A-04] [A-05] [A-06]

## 4) Status Dashboard

### Build Health
- Status: PASSING
- Current compile check:
- `./gradlew :app:compileDebugKotlin --no-daemon` passes. [L-32]

### Integration Surface Health
- Database model + DAO + module wiring: present. [L-14] [L-15]
- Service lifecycle + background sync: present. [L-17] [L-19]
- Library ingest/filter state in VM: mostly present. [L-13] [L-21]
- UI source filter parity: present (itch toggle exposed in bottom sheet and wired to state). [L-33] [L-34]
- Launch/container source parity: improved (`extractGameSourceFromContainerId` handles `ITCH_`; install-path mapping wired for itch containers). [L-35]
- App card status parity: explicit itch install/status handling is present. [L-36]
- App screen download plumbing: coherent (`BaseAppScreen` now queries itch download state). [L-37] [L-10]

## 5) What Is Good
- `GameSource.ITCH` exists and library items can resolve itch cover URLs. [L-20]
- Preferences include `showItchInLibrary`, `itchGamesCount`, and `itchInstalledGamesCount`. [L-14]
- `LibraryState` includes `showItchInLibrary`. [L-21]
- `LibraryViewModel` collects itch DAO flow, toggles visibility, adds entries, and persists counts. [L-13]
- `ItchApiClient` implements paged `owned-keys` fetch and parsing. [L-16]
- `ItchManager` refreshes library and preserves install state during upsert. [L-15]
- Android manifest and activity lifecycle integration exists for itch auth + service. [L-18] [L-19]

## 6) What Is Flawed

### P0 (Must Fix Before Any Merge)

#### I-001: Compile break in prelaunch flow
- Severity: P0
- Status: Closed (2026-02-22)
- Finding: `preLaunchApp` now includes explicit `GameSource.ITCH` handling and launch path.
- Impact: Compile blocker removed.
- Evidence: [L-33] [L-32]
- Implementation note: Added explicit ITCH launch-executable branch and non-Steam launch path.

#### I-002: Compile break in feedback flow
- Severity: P0
- Status: Closed (2026-02-22)
- Finding: `GameFeedbackUtils` now includes explicit `GameSource.ITCH` title lookup.
- Impact: Compile blocker removed.
- Evidence: [L-38] [L-32]

### P1 (Parity Breaks / Behavioral Bugs)

#### I-003: Source filtering UI omits itch
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: Bottom sheet accepts only Steam/Custom/GOG/Epic toggles; no itch chip and no `showItch` prop path.
- Impact: Users cannot toggle itch source from filtering UI despite state support.
- Evidence: [L-33] [L-34]
- Resolution: Added `showItch` prop, itch chip UI, preview wiring, and list-pane call-site plumbing.

#### I-004: Installed count excludes itch
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: `calculateInstalledCount` sums Steam+Custom+GOG+Epic only.
- Impact: Installed count in main library header is wrong when itch games are installed.
- Evidence: [L-34]
- Resolution: Included `PrefManager.itchInstalledGamesCount` gated by `showItchInLibrary`.

#### I-005: Container source extraction misclassifies itch IDs
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: `extractGameSourceFromContainerId` handles Steam/Custom/GOG/Epic only; fallback is Steam.
- Impact: `ITCH_*` IDs are interpreted as Steam in downstream flows (launch, feedback, container operations).
- Evidence: [L-35]
- Resolution: Added `ITCH_` mapping in source extraction.

#### I-006: Card install/status logic has no explicit itch path
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: install checks and status text switch do not implement itch and fall through to generic `else` behavior.
- Impact: Incorrect install indicators and status rendering for itch cards.
- Evidence: [L-36]
- Resolution: Added explicit itch install/status branches backed by `ItchService.getItchGameOf(...).isInstalled`.

#### I-007: Download-state plumbing inconsistency
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: `ItchAppScreen` has download/cancel/progress paths, but `BaseAppScreen` hardcodes itch downloadInfo as `null` with comment "don't support downloads yet".
- Impact: UI state divergence and potentially missing progress/controls in shared content.
- Evidence: [L-37] [L-10]
- Resolution: Wired itch `getDownloadInfo` into base app-screen download switch.

#### I-008: OAuth flow is internally inconsistent (three variants)
- Severity: P1
- Status: Open
- Finding: codebase currently mixes conflicting assumptions:
- `ItchConstants` says auth-code/PKCE in comments but builds `response_type=token` and `scope=profile:me` URL.
- `app.gamenative.ui.ItchOAuthActivity` uses OOB + `response_type=code` + PKCE and manual code entry.
- `ui/screen/auth/ItchOAuthActivity` implements implicit token extraction from fragment but appears unused.
- Manifest registers custom-scheme callback (`gamenative://itch/callback`) that is not used by current OOB flow.
- Impact: Fragile auth behavior, maintenance confusion, unclear security model.
- Evidence: [L-11] [L-23] [L-24] [L-25] [L-18]
- Proposal: Pick one canonical flow and delete/deprecate the others; align constants, manifest, activity, and service comments.

#### I-009: Itch imagery parity gap in game cards
- Severity: P1
- Status: Closed (2026-02-22)
- Finding: itch entries set only `iconHash=coverUrl`, but card image selection for `ITCH` piggybacks on custom-game capsule/header logic.
- Impact: grid/list image fallbacks are likely blank/low-quality for itch cards.
- Evidence: [L-36]
- Resolution: `ITCH` now uses `iconHash` directly for non-list card media.

#### I-010: Itch launch/container assumptions are contradictory
- Severity: P1
- Status: Open
- Finding: some paths say itch has no local installation/container tracking, while download and install paths exist.
- Impact: inconsistent behavior and future regressions.
- Evidence: [L-22] [L-10] [L-17]
- Proposal: define one canonical install model for itch in this app and align all comments + branching.

### P2 (Design / Hardening Gaps)

#### I-011: Download pipeline is zip-only and bypasses itch install planning semantics
- Severity: P2
- Status: Open
- Finding: current download manager chooses first upload (windows preferred), generates URL with API key in query string, downloads file, always treats artifact as zip and extracts with `ZipInputStream`.
- Impact: upload format mismatches, weaker security posture, no butler/wharf parity, likely poor update behavior.
- Evidence: [L-26] [E-10] [E-11] [E-12] [E-06]
- Proposal: add upload planning, format-aware installers, and safer auth transport; roadmap in Phase 3.

#### I-012: Localization and icon parity not yet completed
- Severity: P2
- Status: Open
- Finding: itch strings were added only in `values/strings.xml`; no localized string updates were included unlike Amazon PR pattern.
- Impact: incomplete UX parity for non-English locales.
- Evidence: [L-31] [A-08]
- Proposal: propagate new string keys into maintained locale files and add dedicated itch icon asset(s).

#### I-013: No tests for itch integration paths
- Severity: P2
- Status: Open
- Finding: no itch-specific unit/integration tests currently present in `app/src/test`.
- Impact: regressions likely during parity work.
- Evidence: local grep result (no itch test hits), and no new itch tests in branch file list. [L-01]
- Proposal: add VM filtering tests, auth parser tests, download-state tests, and service orchestration tests.

## 7) Missing for Store Parity

### Minimum Parity Items Not Yet Complete
- [x] Build must pass with exhaustive `GameSource` handling.
- [x] Library source chips include itch toggle.
- [x] Installed count includes itch.
- [x] Container source extraction recognizes `ITCH_`.
- [x] Card install/status behavior has explicit itch branch.
- [x] Download state plumbing is coherent across base + itch screens.
- [ ] OAuth/auth strategy is unified and documented in code.
- [x] Card media handling is store-specific for itch.
- [ ] Basic itch integration tests exist.

## 8) Parity Matrix (Current)

| Capability | Steam | GOG | Epic | Amazon PR branch | Itch branch | Notes |
|---|---|---|---|---|---|---|
| Source enum + library item | Yes | Yes | Yes | Yes | Yes | Itch enum exists and compile-critical switch handling is now exhaustive. [L-20] [L-33] [L-38] [L-32] |
| Source filter chip in UI | Yes | Yes | Yes | Yes | Yes | Itch toggle is now exposed and wired through list pane state. [L-33] [L-34] |
| Installed count in header | Yes | Yes | Yes | Yes | Yes | Itch installed count is included in `calculateInstalledCount`. [L-34] |
| Service lifecycle integration | Yes | Yes | Yes | Yes | Yes | Main activity restart hook + service start/sync exists. [L-19] [L-29] |
| Library sync pipeline | Yes | Yes | Yes | Yes | Yes | Itch manager/api/dao flow exists. [L-15] [L-16] |
| Launch executable resolver in prelaunch | Yes | Yes | Yes | Yes | Partial | ITCH branch now exists; launch behavior still depends on install-path/exe model hardening. [L-33] [L-35] |
| Container source extraction | Yes | Yes | Yes | Yes | Yes | `ITCH_` mapping is now explicit. [L-35] [A-04] |
| App screen download info plumbing | Yes | Yes | Yes | Yes | Yes | Base app screen now requests itch download info. [L-37] [L-10] [A-06] |
| Card install/status logic | Yes | Yes | Yes | Partial | Yes | Itch now has explicit install/status branches. [L-36] |
| Auth flow coherence | Stable | Stable | Stable | In progress | Inconsistent | Three conflicting itch auth paths. [L-11] [L-23] [L-24] [L-25] |
| Tests in branch | Existing | Existing | Existing | Added (Amazon manifest test) | None seen | Itch adds no obvious tests. [A-09] [L-01] |

## 9) itch.io Ecosystem Analysis

### 9.1 How itch Infra Works Upstream
- itch app architecture is explicitly split between Electron UI and a Go daemon (`butler`) for install/download/update operations. [E-07]
- butler is used by itch app and relies on wharf and related packages for patch/install behavior. [E-05] [E-06]
- wharf is designed for incremental build transfer to reduce bandwidth/time and is used in production at itch.io. [E-06]
- Upstream install planning is split into fast upload listing (`Install.GetUploads`) and slower per-upload planning (`Install.PlanUpload`), and explicit error signaling exists for no compatible uploads. [E-10] [E-11] [E-12]
- Official docs emphasize manifests/actions, limited documented game scope (`profile:me`) for game-injected API keys, and butler-backed update/patch behavior. [E-01] [E-02] [E-04]

### 9.2 How Current Itch Branch Differs
- Current branch uses direct REST + custom downloader/extractor, not butlerd semantics.
- Download path is simplified and assumes zip extraction for selected upload.
- Auth path is unsettled compared to upstream's coherent callback-based flow model.
- Upload compatibility and installer-type planning are not yet represented.

### 9.3 Comparison With Other Store Tooling

| Store/tooling | Upstream model | Key implication for GameNative |
|---|---|---|
| Legendary (Epic CLI) | Full CLI lifecycle: auth, install, patch/update, cloud saves, launch metadata. [E-14] | Epic parity usually means richer lifecycle state + install/update handling, not only library listing. |
| gogcli | Cookie-based auth due lack of official user-generated API key model; manifest/actions mirror workflow. [E-15] [E-16] | GOG integration must tolerate auth constraints and action-oriented sync models. |
| Heroic | Multi-store orchestrator using store-specific backends (Legendary, gogdl, Nile) with broad lifecycle features. [E-17] | Store adapters should be explicit and backend-aware; parity requires common UI contracts + store-specific internals. |
| itch upstream (itch+butler+wharf) | Dedicated daemon + incremental patch protocol + upload planning. [E-05] [E-06] [E-07] [E-10] | A naive zip downloader is unlikely to achieve long-term parity for updates/repair. |

## 10) Proposed Implementation Roadmap

### Phase 0: Build Green + Core Parity Surface
- [x] Fix `PluviaMain` exhaustive `when` with ITCH behavior.
- [x] Fix `GameFeedbackUtils` exhaustive `when` with ITCH lookup.
- [x] Add `ITCH_` mapping in `ContainerUtils.extractGameSourceFromContainerId`.
- [x] Add itch source chip and `showItch` prop in `LibraryBottomSheet` + call sites.
- [x] Include itch installed count in `calculateInstalledCount`.
- [x] Add explicit itch status/install branches in `LibraryAppItem`.

Exit criteria:
- [x] `./gradlew :app:compileDebugKotlin --no-daemon` passes.
- [ ] Library filter toggles itch visibility and installed count correctly.

### Phase 1: Auth Consolidation

**Entry criteria:** Phase 0 complete, build passing.

**Task list:**
- [ ] Audit all auth touchpoints (run: `grep -rn 'OAuth\|oauth\|apiKey\|api_key\|PKCE\|code_verifier\|response_type' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'`)
- [ ] Select one canonical auth flow (recommended: standards-based auth-code + PKCE + callback).
- [ ] Remove or deprecate duplicate/unused itch auth activity.
- [ ] Align `ItchConstants`, manifest redirect URI, and activity handling.
- [ ] Update service/auth comments to match reality.
- [ ] Run `grep -rn 'ItchOAuthActivity' app/src/main/ --include='*.kt' --include='*.xml'` to confirm only one activity remains.

**Known code locations to touch:**
| File | What to change |
|---|---|
| `ItchConstants.kt:37-58` | Fix `ITCH_SCOPES` (currently `profile:me`, needs `profile:owned`), clarify `response_type` |
| `ItchConstants.kt:23-24` | Replace placeholder `OAUTH_CLIENT_ID` (TODO in code) |
| `ItchAuthManager.kt:164-281` | `exchangeOAuthCode()` — validate or remove PKCE flow |
| `ui/ItchOAuthActivity.kt` | Primary OOB + code + PKCE activity — keep or replace |
| `ui/screen/auth/ItchOAuthActivity.kt` | Implicit token extraction — likely delete |
| `AndroidManifest.xml:81-82` | `gamenative://itch/callback` deep link — wire or remove |

**Verification script:**
```bash
# After auth consolidation, run:
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5

# Verify single auth activity
grep -rn 'ItchOAuthActivity' app/src/main/ --include='*.kt' --include='*.xml' | wc -l
# Expected: references from exactly 1 activity + manifest entry

# Verify scope is correct
grep -n 'ITCH_SCOPES' app/src/main/java/app/gamenative/service/itch/ItchConstants.kt
# Expected: "profile:me profile:owned" or just use API key flow
```

**Exit criteria:**
- [ ] One flow in code, one flow in UI, one flow in docs/comments.
- [ ] Build passes.
- [ ] Manual QA confirms login/logout/restart/session restore behavior.
- [ ] All self-assessment gates pass (Section 16).

### Phase 2: UI/UX Parity Hardening

**Entry criteria:** Phase 1 complete, auth flow unified, build passing.

**Task list:**
- [ ] Dedicated itch icon asset and source icon usage parity.
- [x] Fix itch image selection strategy for list/capsule/hero cards.
- [ ] Localize new itch strings across maintained locale files.
- [ ] Ensure app-screen download controls render consistently.
- [ ] Add uninstall support (`ItchAppScreen` currently has no delete/uninstall path for installed games).

**Known code locations to touch:**
| File | What to change |
|---|---|
| `res/drawable/` | Add itch.io icon asset |
| `LibraryAppItem.kt:219` | Itch icon rendering in cards |
| `ItchAppScreen.kt:193-200` | `onDeleteDownloadClick()` only cancels active downloads; no uninstall |
| `res/values/strings.xml:1016+` | Itch strings present but not propagated to locale variants |

**Verification script:**
```bash
# Check for missing itch icon
find app/src/main/res -name '*itch*' -type f

# Check localizations
for f in app/src/main/res/values-*/strings.xml; do
    echo "=== $f ==="
    grep -c 'itch' "$f" || echo "(no itch strings)"
done

# Build verification
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

**Exit criteria:**
- [ ] Itch cards look correct in all three library layouts.
- [ ] Non-English locales do not show missing keys for itch flows.
- [ ] Uninstall removes game files and resets `isInstalled` in DB.
- [ ] All self-assessment gates pass (Section 16).

### Phase 3: Install/Update Architecture Upgrade

**Entry criteria:** Phase 2 complete, UI parity validated, build passing.

**Task list:**
- [ ] Introduce upload compatibility selection comparable to `Install.GetUploads` semantics.
- [ ] Add installer-type awareness (zip/exe/other) instead of zip-only extraction.
- [ ] **Fix Zip Slip vulnerability** in `ItchDownloadManager.kt:271` (see Code-Level Fix Catalog, F-001).
- [ ] **Reuse shared OkHttpClient** instead of creating `new OkHttpClient()` at `ItchDownloadManager.kt:218` (see F-002).
- [ ] **Move API key from URL to header** in `ItchDownloadManager.kt:191-192` (see F-003).
- [ ] **Fix `gameId = 0` hardcode** in `ItchService.kt:244` (see F-004).
- [ ] **Replace `runBlocking`** in `ItchService.kt:175` with suspend or coroutine-safe call (see F-005).
- [ ] Define update/repair strategy (incremental patching where feasible, fallback full download).
- [ ] Add error mapping for incompatible uploads and user-facing messaging.
- [ ] Add pause/resume download support (`ItchAppScreen.kt:189-191` is currently a no-op).

**Known code locations to touch:**
| File | Line(s) | What to change |
|---|---|---|
| `ItchDownloadManager.kt:271` | `File(destDir, entry.name)` | Add canonical path validation (Zip Slip) |
| `ItchDownloadManager.kt:218` | `val client = OkHttpClient()` | Replace with `Net.http` singleton |
| `ItchDownloadManager.kt:191-192` | `api_key=$apiKey` in URL | Move to `Authorization: Bearer` header |
| `ItchService.kt:242-246` | `gameId = 0` in DownloadInfo | Use actual `gameId.toIntOrNull()` |
| `ItchService.kt:174-178` | `runBlocking(Dispatchers.IO)` | Refactor to `suspend fun` or `withContext` |
| `ItchDownloadManager.kt:257-312` | `extractZip()` | Add format detection for non-zip uploads |
| `ItchAppScreen.kt:189-191` | `onPauseResumeClick()` | Implement pause/resume with OkHttp streaming |

**Verification script:**
```bash
# After security fixes, verify no raw File(dir, entry) without validation
grep -n 'File(destDir, entry' app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt
# Expected: should see canonicalPath check or extractInto helper

# Verify no new OkHttpClient instantiation
grep -n 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt
# Expected: 0 matches

# Verify API key not in URL
grep -n 'api_key=' app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt
# Expected: 0 matches

# Verify no runBlocking
grep -n 'runBlocking' app/src/main/java/app/gamenative/service/itch/ItchService.kt
# Expected: 0 matches

# Build verification
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

**Exit criteria:**
- [ ] Download/install handles at least top expected upload formats.
- [ ] Zip Slip vulnerability is patched.
- [ ] No API key in URL query strings.
- [ ] No `runBlocking` in service companion object.
- [ ] Update path is deterministic and tested on repeat installs.
- [ ] All self-assessment gates pass (Section 16).

### Phase 4: Test + Observability

**Entry criteria:** Phase 3 complete, build passing, manual QA on download/install/uninstall.

**Task list:**
- [ ] Unit tests for library filtering/source toggles/counting.
- [ ] Unit tests for auth URL parsing/state checks.
- [ ] Service tests for sync/download transitions.
- [ ] Telemetry or structured logs for auth/download/install failure classes.
- [ ] Integration test: mock API → sync → filter → card render cycle.

**Test file locations (create if absent):**
| Test file | Tests |
|---|---|
| `app/src/test/java/app/gamenative/service/itch/ItchApiClientTest.kt` | Pagination, parse errors, empty library |
| `app/src/test/java/app/gamenative/service/itch/ItchAuthManagerTest.kt` | Key validation, credential save/load, OAuth exchange |
| `app/src/test/java/app/gamenative/service/itch/ItchDownloadManagerTest.kt` | Zip extraction, Zip Slip guard, format detection |
| `app/src/test/java/app/gamenative/ui/model/LibraryViewModelItchTest.kt` | Filter toggling, installed count, itch visibility |

**Verification script:**
```bash
# Run itch-specific tests
./gradlew :app:testDebugUnitTest --tests '*Itch*' --no-daemon 2>&1 | tail -20

# Run all tests
./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | tail -20

# Check test report
ls -la app/build/reports/tests/testDebugUnitTest/
```

**Exit criteria:**
- [ ] CI catches regressions in key itch flows.
- [ ] Test report shows ≥80% pass rate for new itch test suite.
- [ ] All self-assessment gates pass (Section 16).

## 11) Active Issue Tracker

| ID | Severity | Status | Owner | Summary |
|---|---|---|---|---|
| I-001 | P0 | Closed | Unassigned | `PluviaMain` missing ITCH branch in launch executable switch |
| I-002 | P0 | Closed | Unassigned | `GameFeedbackUtils` missing ITCH branch |
| I-003 | P1 | Closed | Unassigned | Bottom-sheet source filtering UI lacks itch toggle |
| I-004 | P1 | Closed | Unassigned | Installed-count logic omits itch |
| I-005 | P1 | Closed | Unassigned | Container source extraction missing `ITCH_` mapping |
| I-006 | P1 | Closed | Unassigned | Card install/status logic does not model itch explicitly |
| I-007 | P1 | Closed | Unassigned | Base app-screen download info contradicts itch download support |
| I-008 | P1 | Open | Unassigned | Auth flow inconsistency across constants/activities/manifest |
| I-009 | P1 | Closed | Unassigned | Itch card media path unsuitable for pane variants |
| I-010 | P1 | Open | Unassigned | Contradictory assumptions about itch install/container model |
| I-011 | P2 | Open | Unassigned | Download pipeline is zip-only and lacks robust planning |
| I-012 | P2 | Open | Unassigned | Localization/icon parity incomplete |
| I-013 | P2 | Open | Unassigned | Missing itch tests |

### Issue Update Template
```md
#### I-XXX Update (YYYY-MM-DD)
- Status:
- Change made:
- Validation:
- Risk remaining:
- Linked commit/PR:
```

## 12) Troubleshooting Log

### 2026-02-22: Kotlin compile failure on exhaustive `when`
- Symptom: `:app:compileDebugKotlin` failed.
- Root cause: newly introduced `GameSource.ITCH` not handled in all exhaustive switches.
- Evidence: compiler diagnostics for `PluviaMain.kt:1139` and `GameFeedbackUtils.kt:66`. [L-30]
- Resolution status: Resolved (2026-02-22). [L-32]

### 2026-02-22: Phase 0 core parity patch
- Symptom: Core itch parity items were still open across filters/counts/source extraction/card status and base download plumbing.
- Root cause: initial itch branch landed with partial UI/service wiring but missing parity touchpoints.
- Steps tried: patched Phase 0 files (`PluviaMain`, `GameFeedbackUtils`, `ContainerUtils`, `LibraryBottomSheet`, `LibraryListPane`, `LibraryAppItem`, `BaseAppScreen`) and recompiled.
- Outcome: compile now passes and Phase 0 checklist is mostly complete.
- Evidence refs: [L-32] [L-33] [L-34] [L-35] [L-36] [L-37] [L-38]
- Next action: manual QA for source toggles/counts + itch launch path behavior, then move to auth consolidation (Phase 1).

### Troubleshooting Entry Template
```md
### YYYY-MM-DD HH:MM
- Symptom:
- Hypothesis:
- Steps tried:
- Outcome:
- Evidence refs:
- Next action:
```

## 13) Session Log

### Session 2026-02-22 (Codex)
- Scope: Initial full audit + scaffold living doc.
- Completed:
- Captured branch baselines and diff scope.
- Captured compile health with hard blockers.
- Mapped good/flawed/missing areas.
- Compared against Amazon PR parity patterns.
- Pulled upstream itch/butler/wharf + comparator tooling evidence.
- Output: this initial living doc scaffold with actionable roadmap.
- Next handoff:
- Begin Phase 0 implementation in small verifiable patches.

### Session 2026-02-22 (Codex, Phase 0 pass)
- Scope: Implement and validate Phase 0 core parity patches.
- Commands run:
- `./gradlew :app:compileDebugKotlin --no-daemon` (before and after patch set)
- Findings added:
- Closed P0 compile blockers (I-001, I-002).
- Closed core P1 parity gaps for source chip/count/source extraction/card status/download plumbing/media handling (I-003, I-004, I-005, I-006, I-007, I-009).
- Changes made:
- Added explicit ITCH branches in launch + feedback flows.
- Added `ITCH_` container source extraction and itch install-path mapping hooks in container wiring.
- Added itch source chip plumbing and itch installed-count inclusion.
- Added explicit itch install/status/card-media logic and base-screen downloadInfo wiring.
- Validation:
- `:app:compileDebugKotlin` passes. [L-32]
- Next handoff:
- Run manual UI QA for source toggles/count rendering and itch launch behavior, then start Phase 1 auth consolidation.

## 14) Decision Register

### Open Product/Architecture Decisions

#### D-001: Canonical itch auth flow
- Status: Open
- Options:
- Use OOB manual code flow (current `app.gamenative.ui.ItchOAuthActivity` shape).
- Use callback flow with app deep link (`gamenative://itch/callback`) and PKCE.
- Use static API key only (no OAuth in-app), keep OAuth optional for future.
- Why this matters: code currently mixes all three assumptions and will keep regressing until one is selected. [L-11] [L-18] [L-23] [L-24] [L-25]

#### D-002: Near-term download/update ambition
- Status: Open
- Options:
- Keep pragmatic zip-based installer for initial release and document limitations.
- Implement butler-like planning semantics (`GetUploads` + per-upload planning) before release.
- Why this matters: parity scope and timeline are materially different depending on choice. [L-26] [E-10] [E-12]

#### D-003: Definition of parity for v1 itch support
- Status: Open
- Candidate v1 definition:
- Build passes.
- Library sync/filter/card/install status parity at UI level.
- Basic download/install for a bounded set of upload types.
- Known limitations explicitly documented in settings/help.
- Why this matters: prevents gold-plating and keeps acceptance criteria objective.

### Decision Entry Template
```md
#### D-XXX (YYYY-MM-DD)
- Decision:
- Rationale:
- Alternatives considered:
- Expected impact:
- Follow-up actions:
```

## 15) Citation Index

### Local GameNative Evidence
- [L-01] `git diff --name-status origin/master..HEAD` and `git diff --stat origin/master..HEAD` (27 files, +3954/-7).
- [L-02] `app/src/main/java/app/gamenative/ui/PluviaMain.kt:1139`
- [L-03] `app/src/main/java/app/gamenative/utils/GameFeedbackUtils.kt:66`
- [L-04] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryBottomSheet.kt:41`
- [L-05] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:83`
- [L-06] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:448`
- [L-07] `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:1023`
- [L-08] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:343`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:534`
- [L-09] `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt:634`
- [L-10] `app/src/main/java/app/gamenative/ui/screen/library/appscreen/ItchAppScreen.kt:104`
- [L-11] `app/src/main/java/app/gamenative/service/itch/ItchConstants.kt:43`
- [L-12] `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:577`
- [L-13] `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt:148`, `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt:465`
- [L-14] `app/src/main/java/app/gamenative/PrefManager.kt:762`
- [L-15] `app/src/main/java/app/gamenative/service/itch/ItchManager.kt:97`
- [L-16] `app/src/main/java/app/gamenative/service/itch/ItchApiClient.kt:24`
- [L-17] `app/src/main/java/app/gamenative/service/itch/ItchService.kt:365`
- [L-18] `app/src/main/AndroidManifest.xml:81`, `app/src/main/AndroidManifest.xml:134`
- [L-19] `app/src/main/java/app/gamenative/MainActivity.kt:292`
- [L-20] `app/src/main/java/app/gamenative/data/LibraryItem.kt:6`, `app/src/main/java/app/gamenative/data/LibraryItem.kt:64`
- [L-21] `app/src/main/java/app/gamenative/ui/data/LibraryState.kt:24`
- [L-22] `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:577`, `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:892`
- [L-23] `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:598`, `app/src/main/AndroidManifest.xml:82`
- [L-24] `app/src/main/java/app/gamenative/ui/ItchOAuthActivity.kt:195`
- [L-25] `app/src/main/java/app/gamenative/ui/screen/auth/ItchOAuthActivity.kt:16`
- [L-26] `app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt:68`, `app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt:190`, `app/src/main/java/app/gamenative/service/itch/ItchDownloadManager.kt:257`
- [L-27] `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt:484`
- [L-28] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:216`
- [L-29] `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:206`
- [L-30] Local command output: `./gradlew :app:compileDebugKotlin --no-daemon` (2026-02-22)
- [L-31] `app/src/main/res/values/strings.xml:1016`
- [L-32] Local command output: `./gradlew :app:compileDebugKotlin --no-daemon` (2026-02-22, build success after Phase 0 patch set).
- [L-33] `app/src/main/java/app/gamenative/ui/PluviaMain.kt:235`, `app/src/main/java/app/gamenative/ui/PluviaMain.kt:1144`, `app/src/main/java/app/gamenative/ui/PluviaMain.kt:1306`
- [L-34] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryBottomSheet.kt:51`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryBottomSheet.kt:139`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:121`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:469`
- [L-35] `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:578`, `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:904`, `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:1042`
- [L-36] `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:219`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:351`, `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt:552`
- [L-37] `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt:638`
- [L-38] `app/src/main/java/app/gamenative/utils/GameFeedbackUtils.kt:82`

### Amazon Branch Pattern Evidence
- [A-01] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/ui/screen/library/components/LibraryBottomSheet.kt:43`
- [A-02] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:83`
- [A-03] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt:457`
- [A-04] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/utils/ContainerUtils.kt:1041`
- [A-05] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/ui/PluviaMain.kt:1140`
- [A-06] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt:634`
- [A-07] `origin/feat/amazon-games-support:app/src/main/java/app/gamenative/utils/GameFeedbackUtils.kt:66`
- [A-08] `git diff --name-status origin/master..origin/feat/amazon-games-support` (locale string file updates present)
- [A-09] `origin/feat/amazon-games-support:app/src/test/java/app/gamenative/service/amazon/AmazonManifestTest.kt`

### External Upstream / Tooling Evidence
- [E-01] itch docs API integration: `/tmp/gamenative-research/itch-docs/integrating/api/README.md:3`  
  Link: <https://github.com/itchio/itch-docs/blob/e990f8c4de15779658ec9a6a56f06a699b1c4791/integrating/api/README.md>
- [E-02] itch manifest-actions scope/API key env vars: `/tmp/gamenative-research/itch-docs/integrating/manifest-actions.md:121`  
  Link: <https://github.com/itchio/itch-docs/blob/e990f8c4de15779658ec9a6a56f06a699b1c4791/integrating/manifest-actions.md#L121>
- [E-03] itch login flow via browser OAuth: `/tmp/gamenative-research/itch-docs/using/login.md:3`  
  Link: <https://github.com/itchio/itch-docs/blob/e990f8c4de15779658ec9a6a56f06a699b1c4791/using/login.md#L3>
- [E-04] itch updates + butler patching: `/tmp/gamenative-research/itch-docs/integrating/updates.md:18`  
  Link: <https://github.com/itchio/itch-docs/blob/e990f8c4de15779658ec9a6a56f06a699b1c4791/integrating/updates.md#L18>
- [E-05] butler role and architecture links: `/tmp/gamenative-research/butler/README.md:6`  
  Link: <https://github.com/itchio/butler/blob/7cabcc01b5863a559d56bb0a90291be245c08d36/README.md#L6>
- [E-06] wharf incremental protocol and production usage: `/tmp/gamenative-research/wharf/README.md:7`  
  Link: <https://github.com/itchio/wharf/blob/5e6e2731db7b9033c5c4ea994e345afd3481d0f0/README.md#L7>
- [E-07] itch app architecture and butlerd linkage: `/tmp/gamenative-research/itch/README.md:49`  
  Link: <https://github.com/itchio/itch/blob/953bf3ff2a5b86ca26cfd125875a9a0fc32ad246/README.md#L49>
- [E-08] itch OAuth PKCE flow in reactor: `/tmp/gamenative-research/itch/src/main/reactors/login.ts:25`  
  Link: <https://github.com/itchio/itch/blob/953bf3ff2a5b86ca26cfd125875a9a0fc32ad246/src/main/reactors/login.ts#L25>
- [E-09] itch callback handling (`itch://oauth-callback`): `/tmp/gamenative-research/itch/src/main/reactors/url.ts:38`  
  Link: <https://github.com/itchio/itch/blob/953bf3ff2a5b86ca26cfd125875a9a0fc32ad246/src/main/reactors/url.ts#L38>
- [E-10] butlerd install planning API split: `/tmp/gamenative-research/butler/butlerd/types.go:1554`  
  Link: <https://github.com/itchio/butler/blob/7cabcc01b5863a559d56bb0a90291be245c08d36/butlerd/types.go#L1554>
- [E-11] butlerd no-compatible-uploads error code: `/tmp/gamenative-research/butler/butlerd/codes.go:13`  
  Link: <https://github.com/itchio/butler/blob/7cabcc01b5863a559d56bb0a90291be245c08d36/butlerd/codes.go#L13>
- [E-12] butler install queue compatibility selection flow: `/tmp/gamenative-research/butler/endpoints/install/install_queue.go:139`  
  Link: <https://github.com/itchio/butler/blob/7cabcc01b5863a559d56bb0a90291be245c08d36/endpoints/install/install_queue.go#L139>
- [E-13] butler OAuth login parameters (scope `wharf`, token flow): `/tmp/gamenative-research/butler/mansion/authenticate.go:219`  
  Link: <https://github.com/itchio/butler/blob/7cabcc01b5863a559d56bb0a90291be245c08d36/mansion/authenticate.go#L219>
- [E-14] Legendary capabilities and auth/install/update model: `/tmp/gamenative-research/legendary/README.md:20`  
  Link: <https://github.com/derrod/legendary/blob/42af7b5db78eb22210ae6cf2dd1b913c64ca3183/README.md#L20>
- [E-15] gogcli auth constraints (cookies, no official user-generated API keys): `/tmp/gamenative-research/gogcli/README.md:20`  
  Link: <https://github.com/Magnitus-/gogcli/blob/e960c6f18697654cbf98861ca37422e4b579269b/README.md#L20>
- [E-16] gogcli manifest/actions architecture framing: `/tmp/gamenative-research/gogcli/architecture-documentation/README.md:13`  
  Link: <https://github.com/Magnitus-/gogcli/blob/e960c6f18697654cbf98861ca37422e4b579269b/architecture-documentation/README.md#L13>
- [E-17] Heroic multi-store backend strategy and feature surface: `/tmp/gamenative-research/heroic/README.md:13` and `/tmp/gamenative-research/heroic/README.md:56`  
  Link: <https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/blob/d22b1a1507d478c572991011b0cfe0f05ae463bf/README.md#L13>
