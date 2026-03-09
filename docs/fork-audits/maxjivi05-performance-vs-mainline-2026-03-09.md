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
