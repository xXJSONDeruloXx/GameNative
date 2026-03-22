# Steam logout investigation

Date: 2026-03-22
Repo snapshot: `research/steam-logout-investigation` branched from local `master`
Scope: non-user-initiated Steam logouts in GameNative.

Out of scope for the main question: the separate bug where incidental logout also reset settings or appeared to wipe saves/custom games. I still note those side effects when they help identify timing/patterns, but this document is primarily about the unexpected Steam logout itself.

## Search method

Used Discord via Latchkey against the GameNative guild (`1378308569287622737`).

Primary places searched:
- `bug-reports` forum (`1384098122715758682`)
- `feature-requests` forum (`1438076056782504037`)
- `development` (`1386424596449988709`)
- `general` (`1412756778159964201`)
- `latest-builds` (`1440655876133228555`)
- `release-previews` (`1392123808730714222`)

Notes:
- `threads/search` on forum channels was used to enumerate bug threads.
- Guild-wide message search was used to find related discussion outside the forums.
- Some supporter-only links/messages were referenced by users/devs, but not all are readable from every account.

## Executive summary from Discord

What looks clearly established from Discord:
- Unexpected Steam logout is a recurring user-facing issue, not a one-off.
- Reports cluster around:
  - app relaunch / opening the app later,
  - waking the device or resuming from background/sleep,
  - network loss / reconnect scenarios,
  - during or after downloads,
  - sometimes after crashes or device restarts.
- The problem existed by `0.6.1`, was still being reported heavily in `0.8.0` and `0.8.1`, and was still being complained about on `2026-03-22`.
- Discord discussion strongly suggests recent work mainly mitigated **damage caused by logout** (settings reset, apparent library loss), not the root cause of logout itself.
- Dev discussion also indicates there had already been work on WebSocket keepalive / reconnect behavior because disconnects were happening often enough to warrant a dedicated fix.

What is **not** established from Discord alone:
- A single confirmed root cause.
- Whether every report is the same underlying bug versus multiple failure modes that all end up looking like "Steam logged me out".

## Most relevant bug report threads

### 1) Consistently getting signed out of Game Native on launch
- Thread: `1483485101605126174`
- Link: https://discord.com/channels/1378308569287622737/1483485101605126174/1483485101605126174
- Date: 2026-03-17
- Report: every time the user opens GameNative, they get logged out of Steam.
- Why it matters: very direct reproduction symptom; not tied to saves/settings discussion.

### 2) Device Sleep + Cloud Save Issue
- Thread: `1478637090626342943`
- Link: https://discord.com/channels/1378308569287622737/1478637090626342943/1478637090626342943
- Date: 2026-03-04
- Key content:
  - putting the device to sleep logs the user out of Steam,
  - issue reproduced multiple times,
  - another user says they also hit it a lot,
  - one user says they suspect sleep and/or going offline then back online.
- Why it matters: strongest cluster around sleep/background/network transitions.

### 3) Steam disconnects and save data is lost
- Thread: `1478584276974043331`
- Link: https://discord.com/channels/1378308569287622737/1478584276974043331/1478584276974043331
- Date: 2026-03-04
- Key content:
  - after saving and exiting via GameNative menu, library appeared empty,
  - restarting asked the user to sign into Steam again,
  - multiple users report the same thing,
  - one user says it happened without even closing GameNative, only closing the game and leaving the device asleep.
- Why it matters: again points at resume/sleep/background handling rather than only explicit logout.

### 4) Disconnected / reconnect / login : lost all data / settings
- Thread: `1476710794723852531`
- Link: https://discord.com/channels/1378308569287622737/1476710794723852531/1476710794723852531
- Date: 2026-02-26
- Key content:
  - several users piled on quickly,
  - OP suspected Wi‑Fi loss may have preceded logout,
  - another user explicitly said they got logged out while in game and then failed to upload saves after logging back in,
  - another reported it happening nearly every day after closing a game on Retroid Pocket 5.
- Why it matters: strong signal that connectivity churn may be involved.

### 5) Steam occasionally logout when the app is awaken from background
- Thread: `1457594669646217229`
- Link: https://discord.com/channels/1378308569287622737/1457594669646217229/1457594669646217229
- Date: 2026-01-05
- Report:
  - sleep app,
  - re-open next day,
  - game list empty,
  - kill app, reopen,
  - login page shown.
- Dev reply at the time: "not really in our hands, steam logs you out after a period of inactivity".
- Why it matters: shows this symptom is older than the March 2026 bug spike.

### 6) Steam logout settings reset
- Thread: `1480236008699203676`
- Link: https://discord.com/channels/1378308569287622737/1480236008699203676/1480236008699203676
- Date: 2026-03-08
- Relevant to logout itself:
  - user reports Steam logged out 3 times since `v0.8.0`,
  - more users pile on across Mar 18-21,
  - one dev says "will be fixed",
  - later a dev points users to a build containing `fix: preserve settings on Steam logout (#903)`.
- Why it matters: confirms a recent fix existed, but the fix text is about preserving settings on logout, not preventing logout.

### 7) Fourth time; Crash during download, logged out of steam, all games showing not installed.
- Thread: `1480654278346412183`
- Link: https://discord.com/channels/1378308569287622737/1480654278346412183/1480654278346412183
- Date: 2026-03-09
- Relevant content:
  - crash during install/download,
  - reopening shows user logged out of Steam,
  - after re-login, installed games appear missing until storage setting is corrected.
- Why it matters: suggests crash/download path can also end in the same logout state.

## Other recurring reports outside those threads

### Development channel
- 2026-03-09: dev note about PR `#771`:
  - message: "I've just submitted a PR for slightly better WS keep-alive/reconnect behavior ... it's not dropping constantly for me anymore ... decreases the ping interval to 15s ... makes the backoff incremental."
  - Message ID: `1480494370254884947`
- 2026-03-14: dev note about PR `#903`:
  - message says users are being logged out from Steam and then losing prefs / downloaded games, and describes a minimal credential wipe approach so re-login restores things.
  - Message ID: `1482359552262410272`
- 2026-03-18: dev link to quick fix for prefs reset on unintentional logout:
  - Message ID: `1483624806439063633`
- 2026-03-22: complaint in dev channel itself:
  - "Constant Steam logouts are getting really annoying, anyone on a fix?"
  - Message ID: `1485288748517425152`

### General channel
- 2026-03-20:
  - user asks if they can prevent GameNative from logging out their Steam account overnight every day.
  - Message ID: `1484617071038955800`
- Follow-up, clearly second-hand / not primary evidence, but still interesting:
  - another user says "Currently what happens according to the devs is it does like a spam of connect to steam hitting a wall that logs you out"
  - Message ID: `1484617431203844228`
  - and "There gonna try to be more careful with the steam auth from what I read"
  - Message ID: `1484617562170855584`
- I would treat those two as hearsay, not confirmed root-cause evidence.

### Latest builds channel
Two relevant shipped changes were announced:
- 2026-03-18: `fix: add WebSocket keepalive ping and reconnect backoff (#771)`
- 2026-03-20: `fix: preserve settings on Steam logout (#903)`

This is good evidence that the team had already identified:
1. a disconnect/reconnect problem worth specifically hardening, and
2. a separate "logout causes collateral damage" problem.

## Pattern summary from Discord

### Strongest patterns
1. **Sleep / resume / backgrounding**
   - repeated across Jan and Mar reports.
2. **Network loss / reconnect / going offline then back online**
   - explicitly suspected by users.
3. **Download / crash / restart paths**
   - some reports happen mid-download or after crash/reopen.
4. **App launch / reopen later**
   - some users say simply opening the app later logs them out.

### Things that seem likely but are not proven from Discord alone
- The unexpected logout problem may have worsened in visibility around `0.8.0` / `0.8.1`.
- There may be an auth-churn/reconnect-loop component rather than a pure Steam idle-timeout explanation.

### Things I do not think Discord supports yet as a confident conclusion
- "Steam simply logs everyone out after inactivity" as the whole explanation.
  - Too many reports mention wake, reconnect, download interruption, crashes, or repeated daily logout.
- One single reproduction path for all users.

## Interim take before code review

Based on Discord alone, my best current framing is:
- **Known:** GameNative has had a real recurring unexpected Steam logout/disconnect problem.
- **Known:** recent fixes addressed reconnect behavior and also reduced the damage caused when logout happens.
- **Not yet known from Discord alone:** the exact technical trigger for the non-user-initiated logout.

---

## Code review

Reviewed current repo code with focus on these files:
- `app/src/main/java/app/gamenative/service/SteamService.kt`
- `app/src/main/java/app/gamenative/MainActivity.kt`
- `app/src/main/java/app/gamenative/ui/PluviaMain.kt`
- `app/src/main/java/app/gamenative/PrefManager.kt`
- `app/src/main/java/app/gamenative/utils/SteamUtils.kt`
- `app/src/main/java/app/gamenative/utils/SteamTokenLogin.kt`

## Relevant current code paths

### 1) GameNative deliberately stops and restarts the Steam service during ordinary app lifecycle
- `MainActivity.onStop()` sets `SteamService.autoStopWhenIdle = true`.
- If there are no active operations, no login in progress, no import in progress, and no active game keepalive, it stops `SteamService`.
- `PluviaMain` later starts the foreground service again when it sees Steam is not connected.

What this means:
- ordinary background/foreground use can produce repeated `stop -> reconnect -> auto-login` cycles.
- This is **known behavior from code**, not a guess.
- Whether this behavior is *too aggressive* and contributes to logout is a separate question.

### 2) On service start, SteamService immediately reconnects to Steam
`SteamService.onStartCommand()` creates a `SteamClient` using WebSocket transport and an OkHttp client with:
- `pingInterval(15, TimeUnit.SECONDS)`
- 10s connect timeout
- 60s read timeout
- 30s write timeout

This lines up with the Discord/build note for PR `#771`.

### 3) On low-level connection, SteamService auto-logs in whenever stored Steam creds exist
`SteamService.onConnected()` does this:
- resets reconnect state,
- sets `isConnected = true`,
- if `PrefManager.username` and `PrefManager.refreshToken` both exist, it calls `login(...)` automatically.

So every reconnect attempt can become an auth attempt.

### 4) Reconnect logic exists, but current checked-out code still has auth-churn risk
Current `SteamService.onDisconnected()`:
- marks Steam disconnected,
- if not stopping and retry count not exhausted, schedules reconnect with backoff,
- emits `SteamEvent.RemotelyDisconnected`,
- later calls `connectToSteam()` again.

Current `connectToSteam()`:
- launches a coroutine,
- calls `steamClient.connect()`,
- waits 5 seconds,
- if still not connected, marks endpoint bad and force-disconnects.

Important nuance:
- the current checked-out implementation has a `reconnectJob`, but **does not have a separate `connectJob` dedupe guard**.
- I found older local commits/branches (`4470c548`, `01cffa6e`) that explored stronger reconnect/connect scheduling guards, but that stronger dedupe is **not** present in the currently checked-out code.

I am **not** claiming this is the root cause by itself, but it is a believable place for reconnect churn / overlapping attempts to come from.

### 5) There are two direct code paths that can turn an unexpected auth event into a full apparent logout

#### 5a) `onLoggedOff()` + `EResult.LogonSessionReplaced`
Current `SteamService.onLoggedOff()` does:
- if `isLoggingOut`: perform normal logout duties,
- else if `callback.result == EResult.LogonSessionReplaced`: call `performLogOffDuties()` and stop the service,
- else if `callback.result == EResult.LoggedInElsewhere`: emit force-close-app event and reconnect,
- else: reconnect.

This is a **known, direct, current code path** where an unexpected session replacement becomes a real GameNative logout.

That matters because `performLogOffDuties()` clears Steam-side persisted state via `clearUserData()`, which calls:
- `PrefManager.clearSteamSessionPreferences()`
- database cleanup for Steam library/license/download metadata.

#### 5b) `onLoggedOn()` clears user data on any non-OK login result
This is another important current behavior.

In `SteamService.onLoggedOn()`:
- `EResult.OK` => success path,
- `EResult.TryAnotherCM` => reconnect,
- `else` => `clearUserData()`, mark login failed, reconnect.

That means **any non-OK login result other than `TryAnotherCM` clears stored Steam session data**.

This is a strong code-level finding.
It means a transient or recoverable auth/login failure after a reconnect can be escalated by GameNative into a full apparent logout.

I cannot prove from code alone which exact non-OK result users are hitting in the wild, but the escalation behavior itself is real and current.

## What is known vs guessed from code review

### High-confidence findings
These are not guesses.

1. **Unexpected `LogonSessionReplaced` currently causes GameNative to clear Steam session state.**
   - This is an explicit code path in `onLoggedOff()`.

2. **Any non-OK logged-on result (other than `TryAnotherCM`) currently clears Steam session state.**
   - This is an explicit code path in `onLoggedOn()`.

3. **Backgrounding the app can frequently stop the Steam service, and later foregrounding can restart it and auto-login again.**
   - This is explicit lifecycle behavior across `MainActivity` and `PluviaMain`.

4. **PR `#903` / current `clearSteamSessionPreferences()` mainly fixes collateral damage to app-wide settings, not the underlying unexpected Steam logout.**
   - It preserves app-wide preferences better than the old blanket clear, but still removes Steam session/account state.

5. **PR `#771` / current 15-second ping interval clearly targeted a real disconnect problem.**
   - That is supported by both code and Discord build notes.

### Medium-confidence hypotheses
These are plausible, but still hypotheses.

1. **Auth churn is probably part of the real-world problem.**
   - Why I think so:
     - Discord reports cluster around sleep/resume, backgrounding, reconnect, Wi‑Fi loss, and opening the app later.
     - Current lifecycle/service logic creates repeated reconnect + auto-login opportunities.
     - Current code escalates certain auth failures/session replacements into full logout.
   - Why I am not calling it proven:
     - I do not yet have runtime logs showing the exact callback/result sequence from affected devices.

2. **The app may be too eager to convert a transient Steam-side auth wobble into a full logout.**
   - This is partly known because the clearing behavior exists.
   - The part that remains a hypothesis is which Steam result codes are most commonly causing it in the field.

3. **Lack of connect-attempt dedupe may worsen reconnect churn.**
   - Plausible.
   - Not proven without logs showing overlapping connection attempts.

### Low-confidence / scenario-specific hypotheses
1. **Real Steam client mode may explain a subset of `LogonSessionReplaced` cases.**
   - `SteamTokenLogin` writes login data for launching real Steam in-container.
   - If users run in-container real Steam plus GameNative's service client against the same account, session replacement would not be surprising.
   - I would only treat this as a subset explanation, not the broad main bug, because many Discord reports do not mention this mode.

## Best current root-cause read

### What I think is the most solid answer right now
The best code-backed answer I can give is:

- **The immediate mechanism for at least some of these unexpected logouts is not mysterious:** GameNative has explicit code paths that clear Steam session state when it receives certain Steam auth/session events.
- The two biggest ones are:
  1. `LoggedOffCallback(result = LogonSessionReplaced)`
  2. `LoggedOnCallback(result = anything non-OK except TryAnotherCM)`

### What I cannot honestly claim yet
I cannot honestly say which upstream Steam event/result is the dominant trigger across all user reports.

The leading possibilities are:
- session replacement caused by auth churn/reconnect churn,
- transient login failures after reconnect that GameNative handles too destructively,
- a smaller subset caused by real-Steam dual-client/session interactions.

Those are **informed hypotheses**, not confirmed root cause.

## Existing code that already looks like an obvious mitigation

I found an existing local branch/commit that appears directly relevant:
- Branch: `fix/steam-session-replaced-save-loss`
- Commit: `2bebae485a6ed9882ce9c18e66c689817dfd2a14`
- Subject: `fix: preserve steam state on unexpected session replacement`

What it changes:
- On unexpected `EResult.LogonSessionReplaced`, it does **not** call `performLogOffDuties()`.
- Instead it preserves cached Steam credentials/library state and emits a disconnected event.

My assessment:
- **This looks like a strong mitigation.**
- It would not prove or fix the upstream trigger for session replacement.
- But it would stop GameNative from turning that event into a much more destructive full apparent logout.

So:
- as a **mitigation**, this looks high-confidence and obvious.
- as a **root-cause fix**, it is not enough by itself.

## Most obvious next fixes / follow-ups

### 1) Stop treating unexpected `LogonSessionReplaced` as a destructive full logout
Confidence: **high**

Recommendation:
- merge/adapt the behavior from `2bebae48`.

Reason:
- This directly matches one known bad current code path.
- Even if it does not remove the upstream Steam event, it should significantly reduce user-visible pain.

### 2) Stop clearing persisted Steam session data on every generic non-OK login result
Confidence: **high**

Recommendation:
- narrow the `onLoggedOn()` failure handling.
- Do not immediately call `clearUserData()` for every non-OK result.
- Only clear persisted auth state for results that really prove the stored credentials are unusable.

Reason:
- Current behavior looks too destructive.
- This is one of the clearest code smells I found in relation to the reported symptom.

### 3) Add instrumentation before making stronger causal claims
Confidence: **high**

Recommendation:
- log/telemetry around:
  - `LoggedOffCallback.result`
  - `LoggedOnCallback.result`
  - whether app was backgrounded/resumed recently
  - whether network changed recently
  - whether `container.isLaunchRealSteam` was active
  - count/timing of reconnect and login attempts

Reason:
- This is the fastest way to separate:
  - idle disconnects,
  - reconnect churn,
  - auth failure escalation,
  - real-Steam dual-session issues.

### 4) Revisit lifecycle/auth churn after data is collected
Confidence: **medium**

Possible directions:
- keep `SteamService` alive longer across short background periods,
- add login cooldown/debounce after resume/network regain,
- add explicit connect-attempt dedupe if logs show overlap.

Reason:
- plausible contributor,
- but I would still label this as a follow-up investigation path, not a proven fix.

## Bottom line

My honest read:
- **Known from Discord:** users really are being unexpectedly logged out of Steam, often around sleep/resume/reconnect/backgrounding.
- **Known from code:** GameNative currently has explicit paths that convert certain Steam session/auth events into a destructive logout by clearing persisted Steam session state.
- **Best obvious mitigation:** preserve state on unexpected `LogonSessionReplaced` and stop blanket-clearing Steam session data on generic non-OK login results.
- **Still a hypothesis, not a proven fact:** the deeper upstream trigger is probably auth/reconnect churn, but that needs logs to confirm.

## Branch implementation notes (2026-03-22)

Implemented on branch `research/steam-logout-investigation`:

1. **Preserve stored Steam state on unexpected `EResult.LogonSessionReplaced`**
   - User-initiated logout still clears state.
   - Unexpected session replacement now preserves stored Steam session/library state for recovery and diagnostics.

2. **Narrow when non-OK login results clear persisted Steam session state**
   - Current branch now only clears persisted Steam session state for a narrow set of high-confidence credential/auth failures such as invalid password/auth-code / explicit guard-style denial / cached credential invalidation.
   - Other non-OK login results now preserve state and reconnect, which should reduce destructive incidental logout behavior.

3. **Add targeted diagnostic logging to turn future reports into firmer evidence**
   - app lifecycle breadcrumbs (`activity_resumed`, `activity_paused`, `activity_stopped`, `activity_destroyed`)
   - network event breadcrumbs from `ConnectivityManager`
   - connect attempt trigger logging
   - login attempt origin logging
   - richer diagnostics on:
     - `onConnected`
     - `onDisconnected`
     - `onLoggedOn`
     - `onLoggedOff`
     - `clearUserData`
     - `performLogOffDuties`
     - `stop`
     - `clearValues`

4. **Validation**
   - `./gradlew :app:compileDebugKotlin` passes on this branch.

Planned next step:
- load this build on-device,
- let real-world undesirable logout cases happen,
- pull logs,
- update this document with callback/result sequences so more of the current hypotheses can be converted into fact-backed conclusions.
