package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File

/**
 * Manages the GN Frame Generation Vulkan Layer for GameNative.
 *
 * This is an alternative implementation to GameScopeVK that uses the same SPIR-V shaders
 * but implements them as a proper Vulkan explicit layer with cleaner architecture.
 *
 * Similar to LSFG-VK, this manager installs the layer from APK assets into the container
 * at launch time, then configures it via environment variables.
 *
 * GN Framegen Layer vs GameScopeVK vs LSFG-VK:
 *   GameScopeVK:
 *     - Vulkan ICD wrapper (replaces driver)
 *     - Uses control file + mmap protocol
 *     - Requires X11/XCB libraries
 *     - Complex DirectRendering integration
 *
 *   LSFG-VK:
 *     - Vulkan implicit layer
 *     - Requires Lossless.dll from Steam
 *     - Uses config file (conf.toml)
 *     - Hook-based approach
 *
 *   GN Framegen Layer:
 *     - Vulkan explicit layer (sits between app and driver)
 *     - Self-contained (no external dependencies)
 *     - Uses environment variables for configuration
 *     - Same SPIR-V shaders as GameScopeVK
 *     - Cleaner architecture, easier to debug
 *
 * Installation Flow:
 * 1. At launch time: install layer .so + manifest from APK assets into container
 *    at paths where Vulkan loader discovers explicit layers.
 * 2. Set environment variables to enable and configure the layer.
 * 3. At runtime: Vulkan loader loads the layer, which intercepts presentation
 *    and runs frame generation using embedded SPIR-V shaders.
 *
 * Environment Variables:
 *   GN_FG_ENABLE=1              - Enable frame generation
 *   GN_FG_MULTIPLIER=2          - Frame multiplier (2-4)
 *   GN_FG_FLOW_SCALE=0.6        - Optical flow sensitivity (0.2-1.0)
 *   GN_FG_MODEL=0               - Model variant (0=default, 1=clear)
 *   GN_FG_FPS_LIMIT=0           - FPS limit (0=unlimited)
 *   VK_LAYER_PATH=<path>        - Path to layer manifest JSON
 *   VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
 */
object GNFramegenManager {
    private const val TAG = "GNFramegen"

    // Container extra keys (same as GamescopeVkManager for UI compatibility)
    const val EXTRA_ENABLED = "gamescopeVkEnabled"  // Reuse same key
    const val EXTRA_MULTIPLIER = "gamescopeVkMultiplier"
    const val EXTRA_FLOW_SCALE = "gamescopeVkFlowScale"
    const val EXTRA_MODEL = "gamescopeVkModel"

    // Paths inside the container rootDir
    // Using same pattern as LSFG-VK for consistency
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/explicit_layer.d"
    private const val LIB_FILENAME = "libgn-framegen.so"
    private const val MANIFEST_FILENAME = "VkLayer_GN_gamescope_framegen.json"
    private const val VERSION_FILENAME = ".gn_framegen_runtime_version"

    // Relative path from explicit_layer.d back to lib/
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // Current runtime version (bump when bundled .so changes)
    // Format: gn-framegen-v<version>-android-<abi>
    private const val RUNTIME_VERSION = "gn-framegen-v1.0.0-android-arm64-v8a"

    // Asset paths (bundled in APK at app/src/main/assets/)
    private const val ASSET_DIR = "gn_framegen/android_arm64_v8a"
    private const val ASSET_LIB = "$ASSET_DIR/$LIB_FILENAME"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

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

    // ---- Public API --------------------------------------------------------

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
     * Install the GN Framegen layer from APK assets into the container.
     * Similar to LSFG-VK, this copies the layer .so and manifest into the
     * container's filesystem where the Vulkan loader can discover it.
     *
     * Uses version caching to skip if already up-to-date.
     *
     * @return true if installation succeeded or was already current
     */
    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        val rootDir = container.rootDir
        val localLibDir = File(rootDir, LIB_RELATIVE_DIR)
        val layerDir = File(rootDir, LAYER_RELATIVE_DIR)
        val libFile = File(localLibDir, LIB_FILENAME)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val needsInstall = installedVersion != RUNTIME_VERSION ||
            !libFile.isFile || !manifestFile.isFile

        if (!needsInstall) {
            Timber.tag(TAG).d("Runtime %s already installed in %s", RUNTIME_VERSION, rootDir)
            return true
        }

        return try {
            localLibDir.mkdirs()
            layerDir.mkdirs()

            // Copy the layer .so from assets
            FileUtils.copy(context, ASSET_LIB, libFile)

            // Copy and patch the manifest with correct library_path
            val manifestText = context.assets.open(ASSET_MANIFEST)
                .bufferedReader().use { it.readText() }
                .replace(
                    "\"library_path\": \"$LIB_FILENAME\"",
                    "\"library_path\": \"$MANIFEST_LIBRARY_PATH\""
                )
            FileUtils.writeString(manifestFile, manifestText)

            // Write version file
            FileUtils.writeString(versionFile, RUNTIME_VERSION)

            // Set executable permissions
            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101) // rwxr-xr-x
            if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100) // rw-r--r--
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100) // rw-r--r--

            val ok = libFile.isFile && manifestFile.isFile
            if (ok) {
                Timber.tag(TAG).i("Installed GN Framegen %s into %s", RUNTIME_VERSION, rootDir)
            } else {
                Timber.tag(TAG).e("Runtime installation verification failed")
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install GN Framegen runtime")
            false
        }
    }

    /**
     * Apply environment variables to enable the GN Framegen layer.
     *
     * This should be called after ensureRuntimeInstalled() to ensure
     * the layer files are in place.
     *
     * Sets:
     *   GN_FG_ENABLE=1
     *   GN_FG_MULTIPLIER=<value>
     *   GN_FG_FLOW_SCALE=<value>
     *   GN_FG_MODEL=<value>
     *   VK_LAYER_PATH=<manifest directory>
     *   VK_INSTANCE_LAYERS=VK_LAYER_GN_gamescope_framegen
     *
     * Also disables conflicting systems (GameScopeVK, LSFG-VK).
     *
     * @return true if layer is armed and ready
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        if (!isEnabled(container)) {
            disableInContainer(container)
            return false
        }

        // Ensure runtime is installed first
        // Note: This requires context, so caller should ensure it's installed
        // before calling this method, or we need to pass context here too.
        // For now, assume caller handles installation.

        val rootDir = container.rootDir
        val manifestFile = File(rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        val manifestDir = manifestFile.parentFile?.absolutePath

        if (manifestDir == null || !manifestFile.isFile) {
            Timber.tag(TAG).e("Layer manifest not found. Run ensureRuntimeInstalled() first.")
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

        // Remove LSFG-VK env vars (but don't delete files, just disable via env)
        envVars.remove("LSFG_CONFIG")
        envVars.remove("LSFG_PROCESS")
        envVars.remove("DISABLE_LSFG")  // Don't disable, just don't enable

        Timber.tag(TAG).i(
            "GN Framegen armed: multiplier=%d, flowScale=%.2f, model=%d",
            multiplier(container), flowScale(container), model(container),
        )
        return true
    }

    /**
     * Disable frame generation and clean up layer configuration.
     */
    @JvmStatic
    fun disableInContainer(container: Container) {
        // Remove the layer manifest so Vulkan loader doesn't load it
        val rootDir = container.rootDir
        val manifestFile = File(rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        if (manifestFile.exists()) {
            manifestFile.delete()
            Timber.tag(TAG).d("Removed GN Framegen manifest to disable")
        }
    }

    /**
     * Get layer installation status and version info for UI display.
     */
    @JvmStatic
    fun getLayerInfo(context: Context, container: Container): Map<String, String> {
        val rootDir = container.rootDir
        val libFile = File(rootDir, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        val manifestFile = File(rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        val versionFile = File(rootDir, "$LAYER_RELATIVE_DIR/$VERSION_FILENAME")

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val bundledVersion = RUNTIME_VERSION

        // Check if assets exist in APK
        val assetsAvailable = try {
            context.assets.list(ASSET_DIR)?.containsAll(listOf(LIB_FILENAME, MANIFEST_FILENAME)) == true
        } catch (e: Exception) {
            false
        }

        return mapOf(
            "installed" to (libFile.isFile && manifestFile.isFile).toString(),
            "installed_version" to installedVersion,
            "bundled_version" to bundledVersion,
            "needs_update" to (installedVersion != bundledVersion).toString(),
            "assets_available" to assetsAvailable.toString(),
            "lib_path" to libFile.absolutePath,
            "lib_exists" to libFile.exists().toString(),
            "manifest_path" to manifestFile.absolutePath,
            "manifest_exists" to manifestFile.exists().toString(),
            "supported" to isSupported(container).toString(),
            "enabled" to isEnabled(container).toString(),
        )
    }

    /**
     * Check if the GN Framegen layer is installed and ready to use.
     * Convenience method that checks both installation and version.
     */
    @JvmStatic
    fun isRuntimeInstalled(container: Container): Boolean {
        val rootDir = container.rootDir
        val libFile = File(rootDir, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        val manifestFile = File(rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        val versionFile = File(rootDir, "$LAYER_RELATIVE_DIR/$VERSION_FILENAME")

        if (!libFile.isFile || !manifestFile.isFile) {
            return false
        }

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        return installedVersion == RUNTIME_VERSION
    }
}
