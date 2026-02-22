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
- `./gradlew :app:compileDebugKotlin --no-daemon` passes. [L-52]

### Integration Surface Health
- Database model + DAO + module wiring: present. [L-14] [L-15]
- Service lifecycle + background sync: present. [L-17] [L-19]
- Library ingest/filter state in VM: mostly present. [L-13] [L-21]
- UI source filter parity: present (itch toggle exposed in bottom sheet and wired to state). [L-33] [L-34]
- Launch/container source parity: improved (`extractGameSourceFromContainerId` handles `ITCH_`; install-path mapping wired for itch containers). [L-35]
- App card status parity: explicit itch install/status handling is present. [L-36]
- App screen download plumbing: coherent (`BaseAppScreen` now queries itch download state). [L-37] [L-10]
- Auth UX strategy: API-key-first in settings; OAuth path retained but hidden pending broader scope access. [L-49] [L-50]
- Browser return-flow hardening for API-key creation is implemented (pending final QA closure). [L-51] [L-52]

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

#### I-008: Auth strategy mismatch vs real OAuth scope limits
- Severity: P1
- Status: Closed (2026-02-22)
- Finding:
- Early branch state had three conflicting OAuth assumptions (implicit, OOB code flow, callback flow).
- After unifying OAuth implementation, scope validation showed current third-party OAuth credentials remain insufficient for download endpoints.
- Scope validation update (2026-02-22):
- Requesting `scope=wharf` from this client returns `invalid scope`.
- OAuth token introspection (`GET /credentials/info`) returns only `profile:me` and `profile:owned`.
- `GET /games/:id/uploads?download_key_id=...` fails with `403` and `api key does not permit \`game:view:uploads\``.
- Impact: OAuth can cover library sync but not full "library + downloads" v1 behavior.
- Evidence: [L-11] [L-18] [L-39] [L-40] [L-41] [L-45] [L-46] [L-47] [E-18] [E-19] [E-13] [L-49] [L-50]
- Resolution (2026-02-22):
- Keep OAuth codepath in tree for future partnership/scope expansion.
- Hide OAuth entry in settings for now and promote API-key route as primary UX.
- Improve API-key flow UX (connect copy, open-key-page shortcut, paste-from-clipboard, manage-key entry, clearer error mapping).

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

#### I-014: API-key browser bounce loses settings/modal context
- Severity: P1
- Status: Open
- Finding:
- User flow `Settings -> Connect itch.io -> Open API Keys Page -> return to app` can land on Home; API-key dialog may only flash briefly and dismiss.
- Impact:
- Breaks one-task auth flow and increases drop-off during sign-in.
- Evidence: [L-53]
- Mitigation in progress (2026-02-22):
- Added one-shot return flag + settings-route argument path to reopen API-key dialog on return from browser.
- `PluviaMain` now routes to `settings?openItchApiDialog=true` when the return flag is set.
- Settings screen/group now accept and consume `openItchApiDialogOnStart` for deterministic dialog re-open.
- Evidence: [L-51] [L-52]
- Remaining risk:
- Needs on-device re-validation after latest patch to confirm no brief-dismiss regression.

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
- [x] Auth strategy is unified in UI/docs (API-key-first; OAuth hidden).
- [x] Card media handling is store-specific for itch.
- [ ] External browser bounce reliably returns to API-key dialog.
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
| Auth flow coherence | Stable | Stable | Stable | In progress | Stable | API-key-first UX is now canonical; OAuth callback implementation is retained but hidden from settings. [L-49] [L-50] [L-39] [L-40] |
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
- Auth path is API-key-first in UI; OAuth implementation is retained but hidden due third-party scope ceiling for downloads.
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

### Phase 1: Auth Strategy + Scope Validation

**Entry criteria:** Phase 0 complete, build passing.

**Task list:**
- [x] Audit all auth touchpoints (run: `rg -n 'OAuth|oauth|apiKey|api_key|PKCE|code_verifier|response_type' app/src/main/java/app/gamenative/service/itch/ -g '*.kt'`).
- [x] Remove duplicate/unused itch auth activity and unify OAuth implementation.
- [x] Validate OAuth scope ceiling against required download endpoints (`/credentials/info`, `/games/:id/uploads`, `scope=wharf` test).
- [x] Pivot UX to API key as canonical visible path.
- [x] Hide OAuth entry from settings while retaining OAuth callback/activity code for future re-enable.
- [x] Update auth comments and settings copy to match actual product behavior.

**Known code locations to touch:**
| File | What to change |
|---|---|
| `ItchConstants.kt` | Keep OAuth constants for retained path; document API-key-first strategy |
| `ui/ItchOAuthActivity.kt` | Retained callback handler for future re-enable |
| `ui/screen/auth/ItchOAuthActivity.kt` | Removed duplicate implicit token extraction path |
| `SettingsGroupInterface.kt` | API-key-first UX, OAuth entry hidden, friendlier key dialog/error copy |
| `ItchAuthManager.kt`, `ItchService.kt` | Align comments to "API key primary / OAuth retained" |
| `AndroidManifest.xml:82` | Keep deep link callback registration for dormant OAuth path |

**Verification script:**
```bash
# After auth consolidation, run:
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5

# Verify OAuth no longer appears in settings UI code path
rg -n 'Login with OAuth|itchOAuthLauncher|Intent\\(context, ItchOAuthActivity' app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt
# Expected: 0 matches

# Verify current scope constant for retained OAuth path
grep -n 'ITCH_SCOPES' app/src/main/java/app/gamenative/service/itch/ItchConstants.kt
# Expected: "profile:me profile:owned"
```

**Exit criteria:**
- [x] One visible flow in UI (API key), documented in code/docs.
- [x] Build passes.
- [x] Manual QA confirms login/logout/restart/session restore behavior.
- [x] Scope validation confirms OAuth does not currently satisfy download endpoint permissions.
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
| I-008 | P1 | Closed | Unassigned | OAuth scope ceiling blocks downloads; API-key-first UX adopted |
| I-009 | P1 | Closed | Unassigned | Itch card media path unsuitable for pane variants |
| I-010 | P1 | Open | Unassigned | Contradictory assumptions about itch install/container model |
| I-011 | P2 | Open | Unassigned | Download pipeline is zip-only and lacks robust planning |
| I-012 | P2 | Open | Unassigned | Localization/icon parity incomplete |
| I-013 | P2 | Open | Unassigned | Missing itch tests |
| I-014 | P1 | Open | Unassigned | Returning from API-key browser page can lose/dismiss auth modal context |

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

### 2026-02-22: Phase 1 auth QA (adb/logcat)
- Symptom: Initial OAuth browser launch showed `invalid redirect URI`.
- Root cause: OAuth app redirect URI registration did not include `gamenative://itch/callback`.
- Steps tried:
- Added callback URI in itch OAuth app settings.
- Re-ran OAuth login flow on device with adb/logcat capture.
- Validated logout path and post-relaunch session restore.
- Outcome:
- OAuth callback/exchange succeeds and credentials are stored.
- Itch service sync completes and owned library entries appear.
- Logout clears credentials and itch library entries.
- Relaunch after re-login preserves session and library visibility.
- Evidence refs: [L-44]
- Next action: address separate download-scope failure (`game:view:uploads`) in later phase.

### 2026-02-22: OAuth scope ceiling validation + API-key UX pivot
- Symptom:
- OAuth sign-in worked for library sync, but download capability remained blocked and user flow was unclear.
- Hypothesis:
- Third-party OAuth scopes for this app do not include required upload/download permissions.
- Steps tried:
- Requested OAuth with `scope=wharf`.
- Inspected OAuth credential scopes via `GET /credentials/info`.
- Probed `GET /games/:id/uploads?download_key_id=...` with OAuth credential.
- Pivoted settings UX to API-key-first and hid OAuth entry.
- Outcome:
- `scope=wharf` returned `invalid scope`.
- OAuth credential scopes were `profile:me`, `profile:owned`.
- Upload-list endpoint returned `403` with missing `game:view:uploads`.
- API key flow is now the canonical visible path for library + downloads.
- Evidence refs: [L-45] [L-46] [L-47] [L-49] [L-50] [E-18] [E-13]
- Next action:
- Continue download hardening on API-key route; revisit OAuth only if partnership scopes become available.

### 2026-02-22: API-key browser return loses modal context
- Symptom:
- After opening itch API key page in browser from the sign-in dialog, returning to app landed on Home and dialog dismissed.
- Hypothesis:
- External browser bounce was not treated as resumable one-task auth flow; navigation state and dialog state diverged on resume.
- Steps tried:
- Captured user repro with screenshot/report and adb logcat around settings/auth transitions.
- Added persisted one-shot return flag (`PrefManager.itchReturnToApiKeyDialog`).
- Updated settings route to accept `openItchApiDialog` nav arg and reopened dialog from route arg.
- Updated resume logic in `PluviaMain` to route to `settings?openItchApiDialog=true` when return flag is set.
- Outcome:
- Fix is implemented and build/install succeed.
- Re-validation still required on device to confirm the brief-dismiss symptom is resolved.
- Evidence refs: [L-51] [L-52] [L-53]
- Next action:
- Run focused adb QA for the exact bounce path and close I-014 only after verified stable.

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

### Session 2026-02-22 (Codex, Phase 1 auth research)
- Scope: Resolve D-001 with fresh itch OAuth evidence and define implementation path.
- Commands run:
- Local auth touchpoint audit (`rg` + file reads across constants/auth activities/settings/manifest/service).
- Upstream docs/source pull + inspection (`itch OAuth docs`, `itch app reactors`, `go-itchio`, `butler` auth path).
- Live endpoint probes for `/user/oauth`, `/oauth/token`, and `/credentials/info`.
- Findings added:
- Confirmed current codebase has three conflicting auth assumptions (implicit, code+PKCE OOB, deep-link callback path).
- Confirmed scope mismatch for owned library access (`profile:owned` required for `/profile/owned-keys`).
- Confirmed `/oauth/token` endpoint is active and enforces PKCE-style auth-code parameters.
- Changes made:
- Decision register updated to choose one canonical Phase 1 auth path.
- Validation:
- N/A (research/documentation step only).
- Next handoff:
- Implement canonical auth-code + PKCE + callback flow in code, keep API-key login as fallback.

### Session 2026-02-22 (Codex, Phase 1 auth implementation)
- Scope: Implement D-001 and close I-008 auth inconsistency.
- Commands run:
- `rg -n 'ItchOAuthActivity' app/src/main -g '*.kt' -g '*.xml'`
- `./gradlew :app:compileDebugKotlin --no-daemon`
- Findings added:
- Auth flow coherence is now single-path in code and settings wiring.
- Changes made:
- Updated `ItchConstants` to callback redirect + `profile:me profile:owned` scopes and PKCE URL builder.
- Replaced `ui/ItchOAuthActivity` with callback-based PKCE exchange flow (state validation + token exchange).
- Removed `ui/screen/auth/ItchOAuthActivity` duplicate implicit-flow activity.
- Updated settings OAuth launch flow to use activity result and trigger `ItchService` sync after successful auth.
- Updated stale auth comments in `ItchAuthManager` and `ItchService`.
- Validation:
- `:app:compileDebugKotlin` passes after auth consolidation. [L-43]
- Next handoff:
- Run adb/manual QA for OAuth login/logout/session behavior.

### Session 2026-02-22 (Codex, Phase 1 auth QA via adb/logcat)
- Scope: Validate end-to-end auth behavior after consolidation.
- Commands run:
- `./gradlew :app:installDebug --no-daemon`
- `adb devices -l`
- `adb -s d234a848 logcat -c`
- `adb -s d234a848 logcat -d -v time | rg -n 'Itch|OAuth|SettingsItch|owned-keys|logout'`
- Findings added:
- OAuth path is stable after redirect registration update; callback and code exchange complete.
- Logout path clears state; relaunch retains session when logged in.
- Separate out-of-scope download permission failure observed (`game:view:uploads`).
- Validation:
- Device QA passed for login/logout/restart/session-restore scope. [L-44]
- Next handoff:
- Keep download-scope issue for follow-up implementation.

### Session 2026-02-22 (Codex, Phase 1 scope validation + UX pivot)
- Scope: Validate OAuth scope limits for downloads and finalize API-key-first UX.
- Commands run:
- `./gradlew :app:compileDebugKotlin --no-daemon`
- `adb devices -l`
- `adb -s d234a848 logcat -d -v time | rg -n 'Itch|OAuth|SettingsItch|owned-keys|logout'`
- OAuth/API probes for `scope=wharf`, `/credentials/info`, and `/games/:id/uploads`.
- Findings added:
- `wharf` scope request on third-party OAuth client returned `invalid scope`.
- OAuth credential scopes remained limited to `profile:me`, `profile:owned`.
- Upload-list endpoint denied OAuth credential (`403`, missing `game:view:uploads`).
- Changes made:
- Updated living doc decisions/findings to API-key-first.
- Hid OAuth settings entry and improved API-key flow UX (connect/manage copy, paste flow, clearer auth errors).
- Updated itch auth comments/constants to avoid OAuth-first messaging.
- Validation:
- `:app:compileDebugKotlin` passes after UX/doc-alignment changes. [L-50]
- Next handoff:
- Keep OAuth code dormant; continue integration work on API-key path.

### Session 2026-02-22 (Codex, API-key return-flow hardening)
- Scope: Fix API-key dialog context loss when returning from external browser.
- Commands run:
- `./gradlew :app:compileDebugKotlin --no-daemon`
- `./gradlew :app:installDebug --no-daemon`
- `adb -s d234a848 logcat -d -v time | rg -n 'SettingsItch|Itch|onDestinationChanged|ActivityTaskManager'`
- Findings added:
- User observed return-flow bug: app resumed on Home and API-key dialog dismissed after brief flash.
- Changes made:
- Added `PrefManager.itchReturnToApiKeyDialog` one-shot state.
- Added `PluviaScreen.Settings` route arg (`openItchApiDialog`) and route helper.
- Routed browser-return flow to `settings?openItchApiDialog=true` in `PluviaMain`.
- Threaded `openItchApiDialogOnStart` through `SettingsScreen` into `SettingsGroupInterface` to reopen dialog deterministically.
- Validation:
- Build passes and debug install succeeds on device. [L-52]
- Next handoff:
- Execute targeted adb/manual QA of browser bounce and close I-014 if stable.

## 14) Decision Register

### Open Product/Architecture Decisions

#### D-001: Canonical itch auth flow
- Status: Decided (2026-02-22)
- Decision:
- Use API-key login as the canonical visible auth flow.
- Keep OAuth (`authorization_code` + PKCE + callback) in code but hidden from settings UI until broader scopes are available.
- Rationale:
- Existing implementation was initially inconsistent and needed consolidation.
- Scope validation after OAuth unification showed third-party limits for our use case:
- `scope=wharf` returned `invalid scope`.
- OAuth credential introspection showed only `profile:me`, `profile:owned`.
- Upload-list endpoint required missing `game:view:uploads`.
- API key gives one dependable flow for both library sync and downloads today.
- Alternatives considered:
- OAuth-only in UI: cleaner parity story, but fails current download permission requirements.
- Remove OAuth entirely: simplifies code now, but loses prepared path if scope/partnership situation changes.
- Expected impact:
- Users get one clear sign-in path that works for library + downloads.
- Fewer auth dead-ends caused by OAuth scope failures.
- Engineering retains low-cost optionality for future OAuth re-enable.
- Follow-up actions:
- Keep OAuth menu entry hidden unless scope validation criteria are revisited.
- Continue API-key UX hardening and download pipeline work.
- Re-open OAuth only if partnership/provisioned scopes can satisfy download endpoints.
- Why this matters: reliability now beats parity theater, and we keep a future bridge without exposing users to broken paths. [L-49] [L-50] [L-45] [L-46] [L-47] [E-18] [E-19] [E-13]

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

## 15) Agent Workflow Automation

### 15.1 Common Agent Operations (Copy-Paste Ready)

#### Full Build + Lint Loop
```bash
cd /home/kurt/GameNative

# Compile check (primary gate)
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20

# Lint check (secondary gate, slower)
./gradlew :app:lintDebug --no-daemon 2>&1 | tail -30

# Unit tests (itch-only)
./gradlew :app:testDebugUnitTest --tests '*Itch*' --no-daemon 2>&1 | tail -20
```

#### GameSource Exhaustiveness Scanner
Run after any enum change or new `when(gameSource)` block:
```bash
# Find all when blocks on GameSource that might be non-exhaustive
grep -rn 'when.*gameSource\|when.*GameSource' app/src/main/java --include='*.kt' | head -30

# Cross-check: every when on GameSource must include ITCH
for f in $(grep -rl 'when.*gameSource\|when.*GameSource' app/src/main/java --include='*.kt'); do
    if ! grep -q 'GameSource.ITCH\|\.ITCH' "$f"; then
        echo "MISSING ITCH: $f"
    fi
done
```

#### Itch File Inventory
```bash
# All itch-related source files
find app/src/main/java -path '*itch*' -name '*.kt' | sort

# All itch-related test files
find app/src/test -path '*itch*' -name '*.kt' 2>/dev/null | sort

# All itch-related resources
find app/src/main/res -iname '*itch*' -type f | sort
```

#### Credential/Security Audit Scan
```bash
# Check for API keys in URL query strings
grep -rn 'api_key=' app/src/main/java --include='*.kt'

# Check for plaintext credential writes
grep -rn 'writeText\|writeBytes' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'

# Check for new OkHttpClient instantiations (should use Net.http)
grep -rn 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'

# Check for runBlocking usage (should be avoided)
grep -rn 'runBlocking' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'
```

#### Diff Review Before Commit
```bash
# Summary of changes
git diff --stat

# Detailed diff showing only itch-related changes
git diff -- '**/itch/**' '**/*itch*' '**/*Itch*'

# Check for debug/TODO artifacts
git diff | grep -i 'TODO\|FIXME\|HACK\|XXX\|TEMP\|DEBUG' | head -20
```

### 15.2 Search Patterns for Discovery

| Task | Command |
|---|---|
| Find all itch imports | `grep -rn 'import.*itch' app/src/main/java --include='*.kt' \| sort -u` |
| Find itch usages in non-itch files | `grep -rn 'Itch\|itch' app/src/main/java --include='*.kt' \| grep -v '/itch/' \| head -30` |
| Find itch Room/DAO queries | `grep -rn '@Query\|@Insert\|@Update\|@Delete' app/src/main/java/app/gamenative/db/dao/ItchGameDao.kt` |
| Find itch preferences | `grep -rn 'itch' app/src/main/java/app/gamenative/PrefManager.kt` |
| Find Amazon PR patterns to mirror | `git diff origin/master..origin/feat/amazon-games-support --stat` |

### 15.3 Agent Decision Framework

When an agent encounters ambiguity during implementation:

1. **Check this document first** — search for the topic in the issue tracker, decision register, or roadmap.
2. **Check Amazon PR patterns** — `git show origin/feat/amazon-games-support:<path>` for reference.
3. **Check existing store implementations** — look at GOG/Epic equivalents in the same file.
4. **If still ambiguous** — document the decision in Section 14 (Decision Register) and choose the most conservative option.
5. **Never guess at API behavior** — check the itch.io API reference in Section 19.

## 16) Self-Assessment Gates

Before committing, every change set must pass ALL of the following gates.
Run the full gate check with this single script:

### 16.1 Gate Check Script
```bash
#!/bin/bash
# Save as: /home/kurt/GameNative/tools/gate-check.sh
# Usage: bash tools/gate-check.sh

set -e
cd /home/kurt/GameNative

echo "=== GATE 1: Compile ==="
if ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5 | grep -q 'BUILD SUCCESSFUL'; then
    echo "✓ PASS: Compile"
else
    echo "✗ FAIL: Compile"
    exit 1
fi

echo "=== GATE 2: GameSource exhaustiveness ==="
MISSING=$(for f in $(grep -rl 'when.*gameSource\|when.*GameSource' app/src/main/java --include='*.kt' 2>/dev/null); do
    if ! grep -q 'GameSource.ITCH\|\.ITCH' "$f"; then echo "$f"; fi
done)
if [ -z "$MISSING" ]; then
    echo "✓ PASS: All GameSource when-blocks include ITCH"
else
    echo "✗ FAIL: Missing ITCH in: $MISSING"
    exit 1
fi

echo "=== GATE 3: Security (API key not in URL) ==="
if grep -rn 'api_key=' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -v '^\s*//' | grep -q .; then
    echo "✗ FAIL: API key found in URL query string"
    grep -rn 'api_key=' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' | grep -v '^\s*//'
    exit 1
else
    echo "✓ PASS: No API key in URL"
fi

echo "=== GATE 4: No runBlocking in itch service ==="
if grep -rn 'runBlocking' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -q .; then
    echo "⚠ WARN: runBlocking found (acceptable pre-Phase 3, blocking post-Phase 3)"
    grep -rn 'runBlocking' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'
else
    echo "✓ PASS: No runBlocking"
fi

echo "=== GATE 5: No new OkHttpClient instantiation ==="
if grep -rn 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -q .; then
    echo "⚠ WARN: New OkHttpClient() found (acceptable pre-Phase 3, blocking post-Phase 3)"
    grep -rn 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'
else
    echo "✓ PASS: No new OkHttpClient()"
fi

echo "=== GATE 6: No debug/temp artifacts ==="
if git diff --cached 2>/dev/null | grep -iE 'TODO.*TEMP|HACK|println\(|System\.out' | grep -q .; then
    echo "⚠ WARN: Debug artifacts in staged changes"
else
    echo "✓ PASS: No debug artifacts"
fi

echo ""
echo "=== ALL GATES EVALUATED ==="
```

### 16.2 Gate Severity Levels

| Gate | Phase 0-1 | Phase 2 | Phase 3+ | Notes |
|---|---|---|---|---|
| G1: Compile | BLOCKING | BLOCKING | BLOCKING | Must always pass |
| G2: GameSource exhaustive | BLOCKING | BLOCKING | BLOCKING | Prevents compile regression |
| G3: API key not in URL | WARN | WARN | BLOCKING | Security fix in Phase 3 |
| G4: No runBlocking | WARN | WARN | BLOCKING | Architecture fix in Phase 3 |
| G5: No new OkHttpClient | WARN | WARN | BLOCKING | Resource fix in Phase 3 |
| G6: No debug artifacts | WARN | WARN | BLOCKING | Clean code for merge |

### 16.3 Post-Commit Verification
After each commit, verify the commit is sound:
```bash
# Verify HEAD compiles
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5

# Verify commit message format
git log --oneline -1

# Verify no unintended files
git diff --stat HEAD~1
```

## 17) Commit & Push Automation

### 17.1 Commit Message Convention
Follow [Conventional Commits](https://www.conventionalcommits.org/) scoped to itch:

```
<type>(itch): <description>

[optional body with issue refs]
```

**Types:**
| Type | When |
|---|---|
| `feat(itch):` | New functionality (filter chip, download support, etc.) |
| `fix(itch):` | Bug fix (Zip Slip, exhaustive when, etc.) |
| `refactor(itch):` | Code restructuring without behavior change |
| `security(itch):` | Security fix (API key transport, path traversal) |
| `test(itch):` | Adding/fixing tests |
| `docs(itch):` | Documentation-only changes |
| `chore(itch):` | Build/config changes |

**Examples:**
```
feat(itch): add source filter chip to library bottom sheet

Closes I-003. Adds FlowFilterChip for itch.io toggle with state
propagation through LibraryListPane to LibraryViewModel.

fix(itch): patch Zip Slip vulnerability in download extraction

Closes I-011 (partial). Validates canonical path of extracted entries
against destination directory before writing.

security(itch): move API key from URL query to Authorization header

Closes I-011 (partial). Download URLs no longer contain plaintext
API key. Uses Bearer token header instead.
```

### 17.2 Commit Workflow (Per-Session)
```bash
cd /home/kurt/GameNative

# 1. Run gate check
bash tools/gate-check.sh

# 2. Review changes
git diff --stat
git diff  # full review

# 3. Stage selectively (prefer atomic commits per phase task)
git add -p  # interactive staging

# 4. Commit with conventional message
git commit -m "feat(itch): <description>"

# 5. Push
git push origin feat/itchio

# 6. Update this document's HEAD SHA
# (edit line 3 of ITCHIO_LIVING.md with new SHA)
```

### 17.3 Atomic Commit Strategy
Each commit should correspond to **one logical unit** from the phase checklist:
- **Good:** "fix(itch): add ITCH branch to GameFeedbackUtils when block" — single exhaustive-when fix
- **Bad:** "feat(itch): fix everything" — multiple unrelated changes
- **Exception:** Phase 0 bulk fixes may be batched if they are all compile-fix type

### 17.4 Branch Hygiene
```bash
# Rebase on master before opening PR
git fetch origin
git rebase origin/master

# If rebase conflicts, resolve and continue
git rebase --continue

# Force-push after rebase (only on feature branch)
git push origin feat/itchio --force-with-lease

# Verify after rebase
./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

## 18) Code-Level Fix Catalog

Concrete before/after code blocks for known issues. Each fix has an ID (F-XXX) cross-referenced to Issues (I-XXX).

### F-001: Zip Slip Vulnerability Fix
**Issue:** I-011 | **Phase:** 3 | **File:** `ItchDownloadManager.kt:271`

**Before (vulnerable):**
```kotlin
while (entry != null) {
    val file = File(destDir, entry.name)
    
    if (entry.isDirectory) {
        file.mkdirs()
```

**After (safe):**
```kotlin
while (entry != null) {
    val file = File(destDir, entry.name)
    
    // Zip Slip guard: ensure extracted path stays within destDir
    val canonicalDest = destDir.canonicalPath
    val canonicalFile = file.canonicalPath
    if (!canonicalFile.startsWith(canonicalDest + File.separator) && canonicalFile != canonicalDest) {
        throw SecurityException("Zip entry would escape target dir: ${entry.name}")
    }
    
    if (entry.isDirectory) {
        file.mkdirs()
```

### F-002: Reuse Shared OkHttpClient
**Issue:** I-011 | **Phase:** 3 | **File:** `ItchDownloadManager.kt:218`

**Before (leaks):**
```kotlin
val client = okhttp3.OkHttpClient()
val response = client.newCall(request).execute()
```

**After (shared):**
```kotlin
val client = app.gamenative.utils.Net.http
val response = client.newCall(request).execute()
```

### F-003: API Key in URL → Authorization Header
**Issue:** I-011 | **Phase:** 3 | **File:** `ItchDownloadManager.kt:191-192`

**Before (key in URL):**
```kotlin
val downloadUrl = "${ItchConstants.ITCH_API_BASE_URL}/uploads/$uploadId/download" +
    "?download_key_id=$downloadKeyId&api_key=$apiKey"
```

**After (key in header):**
```kotlin
// Store URL without API key
val downloadUrl = "${ItchConstants.ITCH_API_BASE_URL}/uploads/$uploadId/download" +
    "?download_key_id=$downloadKeyId"
// Then in downloadFile(), add to the Request.Builder:
//   .header("Authorization", "Bearer $apiKey")
```
Note: This requires threading `apiKey` through to `downloadFile()` as a parameter.

### F-004: Fix Hardcoded `gameId = 0`
**Issue:** N/A (new) | **Phase:** 3 | **File:** `ItchService.kt:242-246`

**Before:**
```kotlin
val downloadInfo = app.gamenative.data.DownloadInfo(
    jobCount = 1,
    gameId = 0,
    downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
)
```

**After:**
```kotlin
val downloadInfo = app.gamenative.data.DownloadInfo(
    jobCount = 1,
    gameId = gameId.toIntOrNull() ?: 0,
    downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
)
```

### F-005: Replace `runBlocking` with Suspend
**Issue:** N/A (new) | **Phase:** 3 | **File:** `ItchService.kt:174-178`

**Before:**
```kotlin
fun getItchGameOf(gameId: String): ItchGame? {
    return runBlocking(Dispatchers.IO) {
        getInstance()?.itchManager?.getGameFromDbById(gameId.toIntOrNull() ?: 0)
    }
}
```

**After:**
```kotlin
suspend fun getItchGameOf(gameId: String): ItchGame? {
    return withContext(Dispatchers.IO) {
        getInstance()?.itchManager?.getGameFromDbById(gameId.toIntOrNull() ?: 0)
    }
}
```
Note: All call sites must be updated to call from a coroutine context. Audit with:
```bash
grep -rn 'getItchGameOf' app/src/main/java --include='*.kt'
```

### F-006: Fix OAuth Scope
**Issue:** I-008 | **Phase:** 1 | **File:** `ItchConstants.kt:48`

**Before:**
```kotlin
const val ITCH_SCOPES = "profile:me"
```

**After (if using OAuth):**
```kotlin
const val ITCH_SCOPES = "profile:me profile:owned"
```
Note: If the decision is to use API key only (no OAuth), this constant can be removed entirely.

### F-007: Add Uninstall Support
**Issue:** N/A (new) | **Phase:** 2 | **File:** `ItchAppScreen.kt` (new override)

**Implementation sketch:**
```kotlin
override fun onUninstallClick(context: Context, libraryItem: LibraryItem) {
    val game = itchGame.value ?: return
    if (!game.isInstalled || game.installPath.isEmpty()) return
    
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val installDir = File(game.installPath)
            if (installDir.exists()) {
                installDir.deleteRecursively()
            }
            
            // Update database
            val updatedGame = game.copy(
                isInstalled = false,
                installPath = "",
                installSize = 0L
            )
            ItchService.getInstance()?.itchManager?.updateGame(updatedGame)
            itchGame.value = updatedGame
            
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "${game.title} uninstalled", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.tag("ItchAppScreen").e(e, "Failed to uninstall ${game.title}")
        }
    }
}
```

## 19) itch.io API Quick Reference

### 19.1 Authentication
| Method | Endpoint | Notes |
|---|---|---|
| API Key | https://itch.io/user/settings/api-keys | Manual user-generated key; full access; never expires; current primary GameNative sign-in path |
| OAuth (implicit) | `https://itch.io/user/oauth?client_id=X&scope=Y&response_type=token&redirect_uri=Z` | Returns token in URL fragment (supported by itch docs; hidden in current GameNative UI) |
| OAuth (code+PKCE) | Same URL with `response_type=code&code_challenge=X&code_challenge_method=S256` | Exchange at `/api/1/oauth/token` (implementation retained but hidden in UI) |
| Profile validation | `GET /api/1/profile` w/ `Authorization: Bearer <key>` | Returns `{user: {id, username, ...}}` |

### 19.2 Library Endpoints
| Endpoint | Method | Auth | Returns |
|---|---|---|---|
| `/api/1/profile/owned-keys?page=N` | GET | Bearer | Paginated list of `{owned_keys: [{id, game: {...}}]}` |
| `/api/1/games/:id` | GET | Optional | `{game: {id, title, cover_url, p_windows, ...}}` |
| `/api/1/:game_id/uploads` | GET | Bearer + `download_key_id` | `{uploads: [{id, filename, size, ...}]}` |
| `/api/1/uploads/:id/download` | GET | Bearer + `download_key_id` | Redirect to signed download URL |

### 19.3 Key Response Shapes

**Owned Keys Response:**
```json
{
  "owned_keys": [
    {
      "id": 12345,
      "game": {
        "id": 67890,
        "title": "Example Game",
        "url": "https://developer.itch.io/example",
        "cover_url": "https://img.itch.zone/...",
        "short_text": "Description",
        "p_windows": true,
        "p_linux": false,
        "p_osx": false,
        "p_android": false,
        "classification": "game",
        "created_at": "2024-01-15T10:30:00Z",
        "min_price": 0,
        "user": { "username": "developer" }
      }
    }
  ],
  "per_page": 50
}
```

**Uploads Response:**
```json
{
  "uploads": [
    {
      "id": 11111,
      "filename": "game-windows.zip",
      "size": 104857600,
      "p_windows": true
    }
  ]
}
```

### 19.4 Rate Limits & Error Handling
- itch.io API does not document explicit rate limits, but aggressive polling will get throttled.
- Current sync throttle: `SYNC_THROTTLE_MILLIS = 5 * 60 * 1000` (5 minutes) in `ItchService.kt:18`.
- Error responses: `{"errors": ["message"]}` — always check `json.has("errors")`.
- HTTP 401: invalid/revoked API key — trigger re-auth flow.
- HTTP 403: scope insufficient (for OAuth tokens this can include missing `game:view:uploads` on upload endpoints).

### 19.5 OAuth Scopes
| Scope | Grants |
|---|---|
| `profile:me` | Read user profile (id, username, display_name, cover_url) |
| `profile:owned` | Read owned game keys (required for library sync) |
| `profile:games` | Read games user has created (developer-only, not needed) |
| `profile:collections` | Read user collections (not needed for v1) |
| `game:view:ownership` | Check ownership for app-creator-owned games only |
| `game:view:rewards` | Read claimed rewards for games user develops |

**Current decision (2026-02-22):** keep OAuth hidden in UI and use API key as the visible path.
Reason: third-party OAuth scope list does not include `wharf`/`game:view:uploads`, and probe attempts showed those missing permissions block download endpoints. [E-18] [L-45] [L-47]

## 20) Architecture Quick Reference

### 20.1 Store Layer Pattern
Every store follows this layered architecture. When implementing an itch feature, find the equivalent in GOG or Epic:

```
XxxConstants.kt     ← Config, URLs, paths, OAuth params
    ↓
XxxAuthManager.kt   ← Credential lifecycle (login, store, refresh, logout)
    ↓
XxxApiClient.kt     ← HTTP calls to store API (library fetch, game details)
    ↓
XxxManager.kt       ← Business logic bridge (sync API → DB, update state)
    ↓
XxxDownloadManager.kt ← Download, extract, install, verify
    ↓
XxxService.kt       ← Android foreground service (lifecycle, notifications, coordination)
    ↓
XxxAppScreen.kt     ← UI (extends BaseAppScreen, Compose)
    ↓
XxxGame.kt          ← Room entity
XxxGameDao.kt       ← Room DAO
```

### 20.2 Key Integration Points (Non-Itch Files to Update)
When adding itch support, these non-itch files almost always need a new branch:

| File | Pattern | What to add |
|---|---|---|
| `PluviaMain.kt` | `when(gameSource)` blocks | ITCH branch for launch, install-check, prelaunch |
| `GameFeedbackUtils.kt` | `when(gameSource)` block | ITCH branch for gameName lookup |
| `ContainerUtils.kt` | `when(gameSource)` blocks ×2 | ITCH branch for drive mapping + install path |
| `LibraryBottomSheet.kt` | FlowFilterChip list | ITCH toggle chip |
| `LibraryListPane.kt` | `showItch` prop threading | Prop declaration + call-site pass |
| `LibraryAppItem.kt` | `when(gameSource)` blocks ×3 | ITCH branch for install status, icon, status text |
| `BaseAppScreen.kt` | `when(gameSource)` block | ITCH branch for downloadInfo lookup |
| `LibraryViewModel.kt` | DAO flow collection + count | Itch game flow, filter toggle, count |
| `PrefManager.kt` | Preference declarations | `showItchInLibrary`, `itchGamesCount`, `itchInstalledGamesCount` |
| `AndroidManifest.xml` | Service + Activity declarations | ItchService, ItchOAuthActivity, deep link |

### 20.3 Database Schema
Current schema version: **13** (matches Amazon PR).

**ItchGame entity** (table: `itch_games`):
| Column | Type | Notes |
|---|---|---|
| id | Int (PK) | itch.io game ID |
| title | String | Game title |
| url | String | Game page URL |
| coverUrl | String | Cover image URL |
| shortText | String | Short description |
| developer | String | Developer username |
| pWindows | Boolean | Windows platform flag |
| pLinux | Boolean | Linux platform flag |
| pOsx | Boolean | macOS platform flag |
| pAndroid | Boolean | Android platform flag |
| minPrice | Int | Minimum price in cents (0 = free/pay-what-you-want) |
| classification | String | "game", "tool", etc. |
| createdAt | String | ISO 8601 date |
| type | AppType | Enum: game, tool |
| downloadKeyId | Int | Download key ID for authenticated downloads |
| isInstalled | Boolean | Local install tracking |
| installPath | String | Local install directory |
| installSize | Long | Installed size in bytes |

## 21) Citation Index

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
- [L-39] `app/src/main/java/app/gamenative/service/itch/ItchConstants.kt:27`, `app/src/main/java/app/gamenative/service/itch/ItchConstants.kt:42`, `app/src/main/java/app/gamenative/service/itch/ItchConstants.kt:54`
- [L-40] `app/src/main/java/app/gamenative/ui/ItchOAuthActivity.kt:49`, `app/src/main/java/app/gamenative/ui/ItchOAuthActivity.kt:107`, `app/src/main/java/app/gamenative/ui/ItchOAuthActivity.kt:273`
- [L-41] `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:411`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:625`
- [L-42] `app/src/main/java/app/gamenative/service/itch/ItchAuthManager.kt:16`, `app/src/main/java/app/gamenative/service/itch/ItchService.kt:23`
- [L-43] Local command output: `./gradlew :app:compileDebugKotlin --no-daemon` (2026-02-22, build success after Phase 1 auth consolidation).
- [L-44] Local adb QA evidence (2026-02-22): logcat captures showing OAuth callback + exchange success, credentials saved, owned-library sync (`Fetched 3 owned games`), successful logout/library clear, and persistent session after relaunch. Command family: `adb -s d234a848 logcat -d -v time | rg -n 'Itch|OAuth|SettingsItch|owned-keys|logout'`.
- [L-45] User QA evidence (2026-02-22): system-browser OAuth attempt with `scope=wharf` returned `invalid scope` (screenshot + in-session report).
- [L-46] Session probe evidence (2026-02-22): `GET https://api.itch.io/credentials/info` for OAuth credential returned scopes `["profile:me","profile:owned"]`.
- [L-47] Session probe evidence (2026-02-22): `GET /games/:id/uploads?download_key_id=...` returned `403` with `api key does not permit \`game:view:uploads\``.
- [L-49] `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:424`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:599`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:611`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:1126`, `app/src/main/java/app/gamenative/service/itch/ItchConstants.kt:12`, `app/src/main/java/app/gamenative/service/itch/ItchAuthManager.kt:17`, `app/src/main/java/app/gamenative/service/itch/ItchService.kt:23`
- [L-50] Local command output: `./gradlew :app:compileDebugKotlin --no-daemon` (2026-02-22, build success after API-key UX + doc alignment changes).
- [L-51] `app/src/main/java/app/gamenative/PrefManager.kt:718`, `app/src/main/java/app/gamenative/ui/screen/PluviaScreen.kt:10`, `app/src/main/java/app/gamenative/ui/PluviaMain.kt:441`, `app/src/main/java/app/gamenative/ui/PluviaMain.kt:1102`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsScreen.kt:33`, `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt:428`
- [L-52] Local command output: `./gradlew :app:compileDebugKotlin --no-daemon` and `./gradlew :app:installDebug --no-daemon` (2026-02-22, build success and install success after API-key browser-return hardening patch).
- [L-53] User QA evidence (2026-02-22): after returning from browser API-key page, app resumed on Home and API-key modal briefly appeared then dismissed.

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
- [E-18] itch OAuth docs: implicit-flow intro + `response_type=token` requirement: `/tmp/itch-auth-research/oauth.pretty.html:148` and `/tmp/itch-auth-research/oauth.pretty.html:201`  
  Link: <https://itch.io/docs/api/oauth>
- [E-19] itch OAuth docs scope mapping (`profile:owned` grants `/profile/owned-keys`): `/tmp/itch-auth-research/oauth.pretty.html:225`  
  Link: <https://itch.io/docs/api/oauth>
- [E-20] itch OAuth docs token permission introspection endpoint (`/credentials/info`): `/tmp/itch-auth-research/oauth.pretty.html:341`  
  Link: <https://itch.io/docs/api/oauth>
- [E-21] Live probes (2026-02-22): `/user/oauth` accepts both `response_type=token` and `response_type=code`, `/oauth/token` enforces auth-code + PKCE fields: `/tmp/itch-auth-research/live-oauth-response-types.txt:1` and `/tmp/itch-auth-research/live-oauth-token-pkce.txt:1`
- [E-22] itch app PKCE login flow (`response_type=code`, `code_challenge`, `state`): `/tmp/itch-auth-research/itch-login.ts:104`  
  Link: <https://github.com/itchio/itch/blob/master/src/main/reactors/login.ts>
- [E-23] itch app callback handling (`itch://oauth-callback?code=...&state=...`): `/tmp/itch-auth-research/itch-url.ts:38`  
  Link: <https://github.com/itchio/itch/blob/master/src/main/reactors/url.ts>
- [E-24] go-itchio OAuth code exchange endpoint and parameters (`/oauth/token`, `grant_type=authorization_code`, `code_verifier`): `/tmp/itch-auth-research/go-itchio-repo/endpoints_login.go:106`  
  Link: <https://github.com/itchio/go-itchio/blob/master/endpoints_login.go>
