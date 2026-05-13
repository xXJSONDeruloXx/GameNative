# gn_framegen bootstrap

This module is the first standalone `libgn_framegen.so` bootstrap for GameNative.

Current scope:
- builds as part of the GameNative app native toolchain
- embeds the extracted GameScopeVK SPIR-V bundle directly in the library
- exposes a JNI bootstrap API for:
  - shader bundle validation
  - bundle/session introspection
  - config/session scaffolding

Not implemented yet:
- host Vulkan device/queue ownership
- AHardwareBuffer import/export
- compute dispatch / frame synthesis
- presentation/compositor wiring

This is intentionally the first library-first slice for the standalone Option A direction.
