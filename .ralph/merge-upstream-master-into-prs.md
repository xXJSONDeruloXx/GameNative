
# Merge upstream/master into all open PR branches

## Current upstream/master
`55c07960 fix: Download support files for gen 2 (#1130)`

## PRs to process (13 total)

| # | PR | Branch | Status |
|---|-----|--------|--------|
| 1 | 1153 | fix/downloads-portrait-tabs | CLEAN |
| 2 | 1123 | feat/custom-achievement-sound | CLEAN |
| 3 | 1121 | feat/qam-launchers | CLEAN |
| 4 | 1112 | feat/scaling-modes-explore | CLEAN |
| 5 | 1108 | fix/persist-screen-effects | CLEAN |
| 6 | 1106 | feat/fake-hdr-effect | CLEAN |
| 7 | 1096 | feat/hide-library-compat-badges | CLEAN |
| 8 | **1081** | **feat/quick-menu-fps-clamp** | **CONFLICTING** |
| 9 | 1079 | downloads-sidebar-reorder-header-fix | CLEAN |
| 10 | 1076 | feat/gog-comet-integration | CLEAN |
| 11 | 1007 | feat/playstore-build-flavors-custom-games | CLEAN |
| 12 | 1002 | feat/per-game-backdrop | CLEAN |
| 13 | 966 | feat/steam-save-import-export | CLEAN |

## Steps per branch
1. `git checkout <branch>` (fetch from origin first if needed)
2. `git merge upstream/master`
3. If clean merge → push with `--force-with-lease` (since merge adds commits)
4. If conflicts → resolve them intelligently by reading the conflicting files, then commit and push
5. Report result

## Important
- For the CONFLICTING branch (#1081), carefully examine each conflict and resolve appropriately
- After each successful merge+push, mark the row as ✅
- If any branch has issues, mark as ❌ and describe the problem
- Save current branch before starting, restore at the end
