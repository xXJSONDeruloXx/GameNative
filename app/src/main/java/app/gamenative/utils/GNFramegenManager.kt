package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File

/**
 * Manages the GN Frame Generation Vulkan Layer for GameNative.
 *
 * This is an alternative implementation to GameScopeVK that uses the same SPIR-V shaders
 * but implements them as a proper Vulkan explicit layer with cleaner architecture.
 *
 * GN Framegen Layer vs GameScopeVK:
 *   GameScopeVK:
 *     - Vulkan ICD wrapper (replaces driver)
 *     - Uses control file + mmap protocol
 *     - Requires X11/XCB libraries
 *     - Complex DirectRendering integration
 *
 *   GN Framegen Layer:
 *     - Vulkan explicit layer (sits between app and driver)
 *     - Uses environment variables for configuration
 *     - No external dependencies
 *     - Simpler presentation via swapchain injection
 *     - Same SPIR-V shaders (optical flow, warp, blend)
 *
 * Environment Variables:
 *   GN_FG_ENABLE=1              - Enable frame generation
 *   GN_FG_MULTIPLIER=2          - Frame multiplier (2-4)
 *   GN_FG_FLOW_SCALE=0.6        - Optical flow sensitivity (0.2-1.0)
 *   GN_FG_MODEL=0               - Model variant (0=default, 1=clear)
 *   GN_FG_FPS_LIMIT=0           - FPS limit (0=unlimited)
 *   VK_LAYER_PATH=<path>        - Path to layer manifest JSON
 *   VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
 *
 * Installation:
 *   1. Build layer: ./build-android.sh (produces libgn-framegen.so)
 *   2. Install to APK: ./install-to-apk.sh GameNative.apk
 *   3. Set environment variables in GameNative container settings
 */
object GNFramegenManager {
    private const val TAG = "GNFramegen"

    // Container extra keys (same as GamescopeVkManager for UI compatibility)
    const val EXTRA_ENABLED = "gamescopeVkEnabled"  // Reuse same key
    const val EXTRA_MULTIPLIER = "gamescopeVkMultiplier"
    const val EXTRA_FLOW_SCALE = "gamescopeVkFlowScale"
    const val EXTRA_MODEL = "gamescopeVkModel"

    // Layer library and manifest paths (inside container)
    private const val LAYER_LIB_FILENAME = "libgn-framegen.so"
    private const val LAYER_JSON_FILENAME = "VkLayer_GN_gamescope_framegen.json"
    private const val LAYER_RELATIVE_PATH = "usr/lib/$LAYER_LIB_FILENAME"
    private const val MANIFEST_RELATIVE_PATH = "usr/share/vulkan/explicit_layer.d/$LAYER_JSON_FILENAME"

    // Layer name for VK_INSTANCE_LAYERS
    private const val LAYER_NAME = "VK_LAYER_GN_gamescope_framegen"

    // Environment variable names
    private const val ENV_ENABLE = "GN_FG_ENABLE"
    private const val ENV_MULTIPLIER = "GN_FG_MULTIPLIER"
    private const val ENV_FLOW_SCALE = "GN_FG_FLOW_SCALE"
    private const val ENV_MODEL = "GN_FG_MODEL"
    private const val ENV_FPS_LIMIT = "GN_FG_FPS_LIMIT"
    private const val ENV_LAYER_PATH = "VK_LAYER_PATH"
    private const val ENV_INSTANCE_LAYERS = "VK_INSTANCE_LAYERS"

    /**
     * Check if the GN Framegen layer is available in the container.
     * Layer must be installed via install-to-apk.sh or manually copied.
     */
    @JvmStatic
    fun isLayerInstalled(container: Container): Boolean {
        val rootDir = container.rootDir
        val libFile = File(rootDir, LAYER_RELATIVE_PATH)
        val manifestFile = File(rootDir, MANIFEST_RELATIVE_PATH)
        return libFile.isFile && manifestFile.isFile
    }

    /**
     * Only supported inside Bionic containers (same as GameScopeVK/LSFG-VK).
     */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /**
     * Check if frame generation is enabled in container settings.
     * This uses the same settings as GameScopeVK for UI compatibility.
     */
    @JvmStatic
    fun isEnabled(container: Container): Boolean =
        isSupported(container) &&
            container.getExtra(EXTRA_ENABLED, "false").equals("true", ignoreCase = true)

    /**
     * Get multiplier setting (2-4, where 2 = 2x frame rate).
     */
    fun multiplier(container: Container): Int =
        (container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)

    /**
     * Get flow scale setting (0.2-1.0, lower = more sensitive).
     */
    fun flowScale(container: Container): Float =
        (container.getExtra(EXTRA_FLOW_SCALE, "0.6").toFloatOrNull() ?: 0.6f).coerceIn(0.2f, 1.0f)

    /**
     * Get model setting (0 = default, 1 = clear/high-quality).
     */
    fun model(container: Container): Int =
        (container.getExtra(EXTRA_MODEL, "0").toIntOrNull() ?: 0).coerceIn(0, 1)

    /**
     * Apply environment variables to enable the GN Framegen layer.
     *
     * Sets:
     *   GN_FG_ENABLE=1
     *   GN_FG_MULTIPLIER=<value>
     *   GN_FG_FLOW_SCALE=<value>
     *   GN_FG_MODEL=<value>
     *   VK_LAYER_PATH=<manifest directory>
     *   VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
     *
     * Note: Disable GameScopeVK (remove ICD JSON) to avoid conflicts.
     *
     * @return true if layer is armed and ready
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        if (!isEnabled(container)) {
            disableInContainer(container)
            return false
        }

        if (!isLayerInstalled(container)) {
            Timber.tag(TAG).e("GN Framegen layer not installed. Run: ./install-to-apk.sh")
            return false
        }

        val rootDir = container.rootDir
        val manifestFile = File(rootDir, MANIFEST_RELATIVE_PATH)
        val manifestDir = manifestFile.parentFile?.absolutePath

        if (manifestDir == null) {
            Timber.tag(TAG).e("Cannot determine manifest directory")
            return false
        }

        // Set configuration environment variables
        envVars.put(ENV_ENABLE, "1")
        envVars.put(ENV_MULTIPLIER, multiplier(container).toString())
        envVars.put(ENV_FLOW_SCALE, flowScale(container).toString())
        envVars.put(ENV_MODEL, model(container).toString())
        envVars.put(ENV_FPS_LIMIT, "0")  // No FPS limit by default

        // Set Vulkan loader paths
        envVars.put(ENV_LAYER_PATH, manifestDir)
        envVars.put(ENV_INSTANCE_LAYERS, LAYER_NAME)

        // Disable conflicting systems
        // Remove GameScopeVK ICD JSON path
        val gamescopeIcd = File(rootDir, "usr/share/vulkan/icd.d/GameScopeVK_icd.json")
        if (gamescopeIcd.exists()) {
            gamescopeIcd.delete()
            Timber.tag(TAG).d("Removed GameScopeVK ICD JSON to prevent conflict")
        }

        // Remove LSFG-VK env vars
        envVars.remove("LSFG_CONFIG")
        envVars.remove("LSFG_PROCESS")
        envVars.remove("VK_ICD_FILENAMES")  // GameScopeVK sets this

        Timber.tag(TAG).i(
            "GN Framegen armed: multiplier=%d, flowScale=%.2f, model=%d",
            multiplier(container), flowScale(container), model(container),
        )
        return true
    }

    /**
     * Disable frame generation by removing layer configuration.
     */
    @JvmStatic
    fun disableInContainer(container: Container) {
        // Nothing special needed - just don't set env vars
        Timber.tag(TAG).d("GN Framegen disabled (no env vars set)")
    }

    /**
     * Get layer installation status and version info for UI display.
     */
    @JvmStatic
    fun getLayerInfo(container: Container): Map<String, String> {
        val rootDir = container.rootDir
        val libFile = File(rootDir, LAYER_RELATIVE_PATH)
        val manifestFile = File(rootDir, MANIFEST_RELATIVE_PATH)

        return mapOf(
            "installed" to isLayerInstalled(container).toString(),
            "lib_path" to libFile.absolutePath,
            "lib_exists" to libFile.exists().toString(),
            "manifest_path" to manifestFile.absolutePath,
            "manifest_exists" to manifestFile.exists().toString(),
            "supported" to isSupported(container).toString(),
            "enabled" to isEnabled(container).toString(),
        )
    }
}
