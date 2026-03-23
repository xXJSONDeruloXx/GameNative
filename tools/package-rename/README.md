# Package rename support tooling

This directory collects source-controlled notes and utilities for keeping
GameNative's package/app-ID handling maintainable.

## Source-controlled upstreams

The following upstreams are tracked as git submodules under `third_party/` so the
runtime pieces we depend on are discoverable in-tree:

- `third_party/bionic-vulkan-wrapper`
  - source lineage for `libvulkan_wrapper.so` / wrapper runtime behavior
- `third_party/box64`
  - source lineage for packaged `box64` assets
- `third_party/wine-custom`
  - source lineage for GLIBC-side custom Wine path behavior
- `app/src/main/cpp/extras/adrenotools`
  - source lineage for adrenotools hook/helper libs

## What is now handled dynamically in the app

- App/package-scoped intent/service actions are derived from `BuildConfig.APPLICATION_ID`
- GLIBC `box64` PT_INTERP is patched at extraction time via `ElfPatcher`
- Vulkan wrapper launch env now sets:
  - `WRAPPER_LAYER_PATH`
  - `WRAPPER_CACHE_PATH`
- Input hook launch env now sets:
  - `FAKE_EVDEV_DIR`
  - `EVSHIM_DATA_PATH`
  - `EVSHIM_WIN_PATH`
- `libwinlator.so`, `libfakeinput.so`, and `libevshim.so` are built from source during app builds

## Remaining known binary gap

The main unresolved opaque binary source gap is still:
- `app/src/main/assets/redirect.tzst`
  - `libredirect.so`
  - `libredirect-bionic.so`

Those are still treated as legacy compatibility shims until source is found or the
runtime no longer depends on them.

## Validation helper

Run this to scan packaged runtime archives for hardcoded legacy package paths:

```bash
python3 tools/package-rename/audit_runtime_package_paths.py
```

This audit is informational: some hits are now neutralized by extraction-time or
launch-time patching, while others still indicate real cleanup work.
