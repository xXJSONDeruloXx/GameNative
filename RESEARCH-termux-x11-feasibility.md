# Research: Termux-X11 feasibility for GameNative

Branch: `research/termux-x11-feasibility`
Date: 2026-03-13

## Goal
Investigate whether GameNative (currently centered around the in-app Winlator-derived X server path) could also support a Termux-X11-based path as an optional alternative.

## Initial findings

### Current GameNative architecture
- GameNative currently contains an in-app X server stack derived from Winlator code:
  - `app/src/main/java/com/winlator/xserver/...`
  - `app/src/main/java/com/winlator/xenvironment/components/XServerComponent.java`
  - `app/src/main/java/com/winlator/widget/XServerView.java`
  - `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- The guest launch path currently hardcodes:
  - `DISPLAY=:0`
  - socket path `/tmp/.X11-unix/X0`
- The app renders the X server inside its own `GLSurfaceView` (`XServerView`) rather than delegating display rendering to another Android app.

### Evidence in-tree
- `GuestProgramLauncherComponent` sets `DISPLAY=:0` and X11 GLX env vars.
- `UnixSocketConfig.XSERVER_PATH` is `/tmp/.X11-unix/X0`.
- `XServerComponent` starts an in-process X server bound to that socket.

### External open-source projects relevant to feasibility
- Mobox: `https://github.com/olegos2/mobox`
  - Uses Termux-X11.
  - Installer references `termux-x11-nightly`.
  - README documents Termux-X11 setup.
- Horizon Emu: `https://github.com/HorizonEmuTeam/Horizon-Emu`
  - README references Termux-X11.
- Termux-X11: `https://github.com/termux/termux-x11`
  - Open-source Android app and loader implementation.
  - Exposes start/stop and preference-related actions/broadcast patterns.

## Preliminary assessment
Adding Termux-X11 support looks **plausible as an optional backend**, but **not as a trivial switch**. The main reason is that GameNative’s current UX, input flow, lifecycle, and rendering path are tightly coupled to the built-in X server and `XServerView`.

## Likely implementation direction
A realistic approach would be:
1. Keep current in-app XServer mode as default.
2. Add a second backend mode: `Termux-X11`.
3. In that mode:
   - do **not** start `XServerComponent`
   - do **not** use `XServerView` for rendering
   - launch/connect to Termux-X11 externally
   - still launch guest Wine/box processes with compatible `DISPLAY` and socket setup
   - provide separate lifecycle/input handling for the external display app model

## Detailed findings

### GameNative code paths that would be affected

#### 1) Launch environment is currently built around an internal X server
Relevant files:
- `app/src/main/java/com/winlator/xenvironment/components/XServerComponent.java`
- `app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java`
- `app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`

Observed behavior:
- `XServerComponent` creates an `XConnectorEpoll` and serves X11 in-process.
- `UnixSocketConfig.XSERVER_PATH` is fixed to `/tmp/.X11-unix/X0`.
- `GuestProgramLauncherComponent.exec(...)` currently sets:
  - `DISPLAY=:0`
  - `ANDROID_SYSVSHM_SERVER=/tmp/.sysvshm/SM0`
  - `BOX86_X11GLX=1`
  - `BOX64_X11GLX=1`
- `setupXEnvironment(...)` always adds:
  - `SysVSharedMemoryComponent`
  - `XServerComponent`
  - optionally VirGL/Vortek/audio components tied to the same environment model

Implication:
- There is no backend abstraction yet; "X server" is assumed to be internal.

#### 2) UI and input are strongly coupled to `XServerView`
Relevant file:
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`

Observed behavior:
- The screen creates or reuses an in-process `XServer` object.
- It instantiates `XServerView(context, xServer)`.
- It wires many systems directly to that object:
  - `TouchpadView`
  - `IMEInputReceiver`
  - `WinHandler`
  - `TouchMouse`
  - `Keyboard`
  - `WindowManager.OnWindowModificationListener`
  - `InputControlsView.setXServer(...)`
- Window map/unmap events from the in-process X server are used for app logic, overlays, and exit behavior.

Implication:
- A Termux-X11 mode is not only a launcher change; it would need an alternate rendering/input/lifecycle path.

### What Mobox contributes
Relevant files from clone:
- `/tmp/pi-mobox/README.md`
- `/tmp/pi-mobox/install`

Observed behavior:
- Mobox explicitly requires installing:
  - Termux
  - Termux-X11
  - Input Bridge
- Its install script installs:
  - `x11-repo`
  - `xwayland`
  - `xorg-xrandr`
  - `termux-x11-nightly`
- README contains recommended Termux-X11 preferences such as exact resolution, fullscreen, landscape, scancode preference, etc.

What this means for GameNative:
- Mobox is strong evidence that a Termux-X11-based Android gaming stack is viable in practice.
- But Mobox does **not** directly show a native Android-app-managed integration like GameNative uses today; it is primarily a Termux-managed workflow.

### What Horizon Emu contributes
Relevant file from clone:
- `/tmp/pi-horizonemu/README.md`

Observed behavior:
- README states that X11 settings are exposed from Termux-X11 preferences.
- README references Termux-X11 and auto-launching InputBridge.
- The open-source repository clone did **not** reveal obvious in-tree Termux-X11 integration code from a quick search; most concrete evidence is documentation-level.

What this means for GameNative:
- Horizon Emu is useful as ecosystem evidence and UX precedent.
- It is less useful than Mobox + Termux-X11 as a direct code reference unless deeper code or other branches are found elsewhere.

### What MiceWine contributes
Relevant files from clone:
- `/tmp/pi-micewine/app/src/main/java/com/micewine/emu/core/EnvVars.java`
- `/tmp/pi-micewine/app/src/main/java/com/micewine/emu/CmdEntryPoint.java`
- `/tmp/pi-micewine/app/src/main/java/com/micewine/emu/activities/EmulationActivity.java`
- `/tmp/pi-micewine/app/src/main/java/com/micewine/emu/LorieView.java`
- `/tmp/pi-micewine/app/src/main/cpp/lorie/...`

Observed behavior:
- MiceWine appears to **embed/fork the Termux-X11/Lorie-style stack directly into its own app**, instead of depending on the external `com.termux.x11` package.
- Strong evidence:
  - it contains a full `app/src/main/cpp/lorie` tree
  - it has its own `LorieView`
  - it has its own `CmdEntryPoint`
  - package/broadcast wiring points to `com.micewine.emu`, not `com.termux.x11`
- `EnvVars.java` still uses a classic X11 model with:
  - `DISPLAY=:0`
  - `TMPDIR=<app-managed tmp>`
- `cmdentrypoint.c` patches X11 unix socket paths under the chosen tmp dir (`.X11-unix/X...`).
- `EmulationActivity` uses binder/socket connection flow very similar to Termux-X11 / Lorie:
  - receive start broadcast
  - obtain X connection fd
  - connect `LorieView`
  - send `windowChanged(surface)` back to native side

What this means for GameNative:
- This is a **very important feasibility reference**.
- It suggests there are actually **two plausible directions** for GameNative:
  1. **External Termux-X11 backend**
     - launch/control the separate Termux-X11 app.
  2. **Embedded Lorie/Termux-X11-style backend**
     - fork/embed the relevant open-source pieces into GameNative, keeping rendering in-app.
- For GameNative specifically, option 2 may fit the current UX better, because GameNative already expects in-app rendering and in-app input overlays.

### What Termux-X11 contributes
Relevant files from clone:
- `/tmp/pi-termux-x11/README.md`
- `/tmp/pi-termux-x11/shell-loader/src/main/java/com/termux/x11/Loader.java`
- `/tmp/pi-termux-x11/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
- `/tmp/pi-termux-x11/app/src/main/java/com/termux/x11/MainActivity.java`

Observed behavior:
- Termux-X11 is a real open-source Android X server, not just a wrapper.
- It supports command-line startup such as:
  - `termux-x11 :1`
  - or direct `app_process ... com.termux.x11.CmdEntryPoint :0`
- For proot/chroot-style environments, the README explicitly discusses:
  - `TMPDIR` mapping to the target container's `/tmp`
  - `XKB_CONFIG_ROOT`
  - shared tmp / namespace considerations
- The shell loader and `CmdEntryPoint` show that the Android app can be launched/connected by broadcast/intent-driven mechanisms.
- `MainActivity` listens for:
  - `com.termux.x11.CmdEntryPoint.ACTION_START`
  - `com.termux.x11.ACTION_STOP`
  - `com.termux.x11.ACTION_PREFERENCES_CHANGED`
  - `com.termux.x11.ACTION_CUSTOM`

What this means for GameNative:
- There is enough open-source code to research a proper integration.
- The most promising path is probably **launching/controlling Termux-X11 as an external app/backend**, not embedding its rendering into the current `XServerView` flow.

## Feasibility analysis

### Feasible parts
1. **Optional backend selection**
   - GameNative can likely add a container/app setting like `xServerBackend = internal | termux-x11 | embedded-lorie`.
2. **Guest env adaptation**
   - `DISPLAY` can be redirected to the chosen X backend display number.
   - `TMPDIR` / shared tmp setup appears conceptually compatible with how GameNative already manages an imagefs root and `/tmp`.
3. **External app control**
   - Termux-X11 exposes start/stop and preference-related hooks that can be researched further for app-driven orchestration.
4. **Embedded backend precedent**
   - MiceWine shows that a Termux-X11/Lorie-style backend can be embedded directly into an emulator app.
5. **Precedent exists**
   - Mobox, Horizon Emu, and MiceWine all validate that this concept is used in the Android x86-on-ARM ecosystem.

### Hard parts / blockers
1. **Current rendering model mismatch**
   - Internal mode renders through `XServerView` and `GLRenderer`.
   - Termux-X11 mode would render in another Android app/activity.
2. **Input model mismatch**
   - Current on-screen controls inject into the in-memory `XServer` object.
   - In Termux-X11 mode, input would need either:
     - a Termux-X11-compatible external injection path, or
     - a different control app/integration strategy.
3. **Window/event tracking mismatch**
   - Current logic depends on `WindowManager` callbacks from the internal X server.
   - External Termux-X11 would likely remove or greatly reduce visibility into those events unless another bridge exists.
4. **Lifecycle complexity**
   - GameNative currently pauses/resumes its own environment and tightly coordinates overlay/UI state.
   - External app lifecycle, foreground/background transitions, and reconnect behavior will be more complex.
5. **Packaging/dependency complexity**
   - If GameNative depends on Termux-X11, it must decide whether to:
     - require separate user installation,
     - detect package presence and prompt install,
     - or vendor/fork/redistribute components if licensing and packaging allow.

## Practical implementation options

### Option A: Minimal external Termux-X11 prototype
Goal: prove launch compatibility only.
- Add a hidden dev toggle for backend selection.
- In Termux-X11 mode:
  - skip `XServerComponent`
  - skip `XServerView`
  - configure env for external X server
  - launch/open Termux-X11
  - launch Wine/game process
- Success criteria:
  - external Termux-X11 window appears
  - Wine app can render
  - game or test app starts

Pros:
- Fastest way to validate external-backend viability.

Cons:
- No polished input/UI integration.

### Option B: Optional external-backend mode
Goal: usable alternative backend.
- Add backend abstraction around X display management.
- Separate:
  - internal Winlator-style X server backend
  - external Termux-X11 backend
- Build a reduced UI for external mode focused on launch/status/resume/stop.

Pros:
- Cleaner architecture.

Cons:
- Still difficult for controls/window tracking.

### Option C: Embedded Lorie-style backend
Goal: keep rendering inside GameNative while replacing/augmenting the current X stack.
- Study MiceWine + Termux-X11 Lorie code.
- Prototype an in-app `LorieView`-style backend as an alternative to the current Winlator X server path.
- Reuse GameNative’s existing strengths:
  - in-app activity/view rendering
  - overlay controls
  - pause/resume UX

Pros:
- Better fit with GameNative’s current in-app rendering model.
- May preserve more of the current UX than an external-app backend.

Cons:
- Bigger engineering change than Option A.
- Requires evaluating licensing, code import cost, and compatibility with existing Winlator-derived systems.

### Option D: Deep parity integration
Goal: full parity regardless of backend.
- Recreate window tracking, input injection, overlays, quick menu behavior, and lifecycle handling for the alternate backend.

Pros:
- Best UX if successful.

Cons:
- Highest cost and risk; likely not worth doing first.

## Current conclusion
- **Yes, there is enough open-source code to research and prototype adding a Termux-X11-style path as an additional backend.**
- **No, it does not look like a small patch.**
- There are now **two credible implementation routes**:
  1. external Termux-X11 app integration
  2. embedded Lorie/Termux-X11-style backend (MiceWine-style)
- For GameNative, the **embedded Lorie-style route may ultimately fit better** with the current in-app rendering/input model.
- For fastest validation, the **external backend prototype** is still the quickest proof-of-concept.
- Mobox provides proof of ecosystem viability.
- Termux-X11 provides the core open-source implementation details.
- MiceWine is the strongest reference found so far for how a game/emulator app can internalize the Termux-X11/Lorie approach.
- Horizon Emu provides product-direction evidence, but from this quick repo scan it is not the strongest code reference.

## Recommended next steps
1. Define a backend enum in GameNative state/config.
2. Isolate current internal-X-server assumptions in `XServerScreen.kt` and environment setup.
3. Prototype **one of these first**:
   - external Termux-X11 launch path for fastest validation, or
   - embedded Lorie-style spike for best architectural fit.
4. Validate:
   - package/code-path viability
   - shared `/tmp` / `DISPLAY` behavior
   - rendering
   - input viability
5. Only after that, investigate full controls/window/lifecycle parity.
