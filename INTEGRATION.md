# GN Framegen Layer - GameNative Integration Guide

This document describes how the GN Framegen Layer integrates with GameNative as a bundled Vulkan layer deployed from APK assets.

## Integration Architecture

The GN Framegen Layer is integrated into GameNative following the same pattern as LSFG-VK:

```
GameNative APK
├── assets/
│   └── gn_framegen/
│       └── android_arm64_v8a/
│           ├── libgn-framegen.so      # Layer library (~6MB with shaders)
│           └── VkLayer_GN_gamescope_framegen.json  # Layer manifest
│
Runtime (Container Launch)
├── 1. GNFramegenManager.ensureRuntimeInstalled(context, container)
│      - Copies libgn-framegen.so → ~/.local/lib/
│      - Copies/patches manifest → ~/.local/share/vulkan/explicit_layer.d/
│      - Writes version file for update detection
│
├── 2. GNFramegenManager.applyLaunchEnv(container, envVars)
│      - GN_FG_ENABLE=1
│      - GN_FG_MULTIPLIER=2-4
│      - GN_FG_FLOW_SCALE=0.2-1.0
│      - GN_FG_MODEL=0/1
│      - VK_LAYER_PATH=<manifest_dir>
│      - VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
│      - Disables GameScopeVK/LSFG-VK to prevent conflicts
│
└── 3. Wine/DXVK → Vulkan Loader → Layer → Generated Frames → Present
```

## Components

### 1. Native Layer (`gn-native-layer/`)

- **libgn-framegen.so**: The Vulkan layer library
  - 49 embedded SPIR-V shaders (~6MB)
  - Intercepts vkCreateSwapchainKHR, vkQueuePresentKHR
  - Implements frame generation pipeline
  - No external dependencies

- **VkLayer_GN_gamescope_framegen.json**: Layer manifest
  - Enables via `GN_FG_ENABLE=1` environment variable
  - Points to libgn-framegen.so

### 2. Android Integration (`app/src/main/java/`)

#### GNFramegenManager.kt
Main integration manager with two key methods:

```kotlin
// Copy layer files from APK assets to container
GNFramegenManager.ensureRuntimeInstalled(context, container)

// Configure and enable the layer
GNFramegenManager.applyLaunchEnv(container, envVars)
```

#### ContainerData.kt
Persistent settings storage:

```kotlin
data class ContainerData(
    // ... other settings ...
    val gnFramegenEnabled: Boolean = false,
    val gnFramegenMultiplier: Int = 2,      // 2-4
    val gnFramegenFlowScale: Float = 0.6f,  // 0.2-1.0
    val gnFramegenModel: Int = 0,           // 0=Default, 1=Clear
)
```

#### GraphicsTab.kt
UI section in container settings:

```kotlin
@Composable
private fun GNFramegenSection(state: ContainerConfigState) {
    SettingsSwitch(
        title = { Text("Enable GN Framegen Layer") },
        subtitle = { Text("Vulkan explicit layer, no external dependencies") },
        // ... multiplier, flow scale, model settings
    )
}
```

#### BionicProgramLauncherComponent.java
Launch-time activation (priority order):

```java
if (GamescopeVkManager.isEnabled(container)) {
    // 1. GameScopeVK (ICD wrapper) - highest priority
    GamescopeVkManager.ensureRuntimeInstalled(...);
    GamescopeVkManager.applyLaunchEnv(...);
} else if (GNFramegenManager.isEnabled(container)) {
    // 2. GN Framegen (Vulkan explicit layer)
    GNFramegenManager.ensureRuntimeInstalled(...);
    GNFramegenManager.applyLaunchEnv(...);
} else if (LsfgVkManager.isSupported(container)) {
    // 3. LSFG-VK (requires Lossless.dll)
    LsfgVkManager.ensureRuntimeInstalled(...);
    LsfgVkManager.applyLaunchEnv(...);
}
```

### 3. Container Utils (`ContainerUtils.kt`)

Serialization/deserialization between Container and ContainerData:

```kotlin
// Reading from container extras
gnFramegenEnabled = container.getExtra(GNFramegenManager.EXTRA_ENABLED, "false").toBoolean(),
gnFramegenMultiplier = container.getExtra(GNFramegenManager.EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2,
// ... etc

// Writing to container extras
container.putExtra(GNFramegenManager.EXTRA_ENABLED, containerData.gnFramegenEnabled.toString())
// ... etc
```

## Deployment Flow

### Development Workflow

1. **Build the layer**:
   ```bash
   cd gn-native-layer/native_layer
   ./build-android.sh
   ```

2. **Copy to assets**:
   ```bash
   cd ..
   ./copy-to-assets.sh
   ```

3. **Build GameNative APK**:
   ```bash
   cd /path/to/GameNative
   ./gradlew assembleDebug
   ```

4. **Install and test**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Runtime Deployment

1. **APK Installation**: Layer files bundled in assets
2. **First Launch**: GNFramegenManager copies files to container
3. **Version Check**: Only copies if version differs (avoid unnecessary I/O)
4. **Configuration**: Environment variables set before Wine launch
5. **Execution**: Layer intercepts Vulkan calls, generates frames

## Configuration

### Environment Variables

| Variable | Values | Description |
|----------|--------|-------------|
| `GN_FG_ENABLE` | `1` or unset | Enable/disable layer |
| `GN_FG_MULTIPLIER` | `2` - `4` | Frames generated per real frame |
| `GN_FG_FLOW_SCALE` | `0.2` - `1.0` | Optical flow sensitivity |
| `GN_FG_MODEL` | `0` or `1` | 0=Default, 1=Clear quality |
| `VK_LAYER_PATH` | Path to manifest dir | Where loader finds layer |
| `VK_INSTANCE_LAYERS` | Layer name | Which layers to load |

### Mutual Exclusion

The three frame generation systems are **mutually exclusive**:

| System | Priority | Requires | Notes |
|--------|----------|----------|-------|
| GameScopeVK | 1 | None | ICD wrapper, most complex |
| GN Framegen | 2 | None | Self-contained, recommended |
| LSFG-VK | 3 | Lossless.dll | Requires Steam purchase |

When one is enabled, the UI automatically disables the others.

## Troubleshooting

### Layer Not Loading

1. Check version file matches bundled version:
   ```bash
   adb shell cat /data/data/app.gamenative/.../.gn_framegen_runtime_version
   ```

2. Verify files were copied:
   ```bash
   adb shell ls -la /data/data/app.gamenative/.../.local/lib/
   adb shell ls -la /data/data/app.gamenative/.../.local/share/vulkan/explicit_layer.d/
   ```

3. Check environment variables:
   ```bash
   adb logcat -s "BionicProgramLauncherComponent" | grep GN_FG
   ```

### Build Issues

If copy-to-assets.sh fails:
- Ensure layer was built: `native_layer/build-android-arm64-v8a/libgn-framegen.so`
- Check script is run from gn-native-layer directory
- Verify GameNative directory structure exists

## Migration from GameScopeVK

Users switching from GameScopeVK to GN Framegen:

1. Settings are **preserved independently** (gnFramegen* vs gamescopeVk*)
2. GN Framegen falls back to GameScopeVK settings if not set:
   ```kotlin
   // GNFramegenManager.kt
   fun multiplier(container: Container): Int {
       val raw = container.getExtra(EXTRA_MULTIPLIER, "")
       if (raw.isNotEmpty()) return raw.toIntOrNull() ?: 2
       // Fallback to GameScopeVK setting
       return container.getExtra("gamescopeVkMultiplier", "2").toIntOrNull() ?: 2
   }
   ```
3. UI toggle automatically disables GameScopeVK when GN Framegen is enabled

## Future Enhancements

Potential improvements:
- A/B testing between frame generation systems
- Per-game preset configurations
- Runtime switching without container restart
- Performance metrics integration with GameNative telemetry
