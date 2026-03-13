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

## Next research steps
- Inspect Mobox integration details more closely.
- Inspect Termux-X11 launch and connection model more closely.
- Compare with GameNative launch/lifecycle/input assumptions to identify blockers.
