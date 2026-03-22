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

Code review notes will be added below in a follow-up commit so the Discord evidence and code-path analysis live in one file.
