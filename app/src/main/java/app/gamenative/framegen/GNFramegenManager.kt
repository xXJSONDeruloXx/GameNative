package app.gamenative.framegen

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * Manages the GN standalone Vulkan frame generation engine.
 *
 * This is the Option-A standalone library: no Lossless.dll, no Vulkan layer shim,
 * no DR socket. The native library owns its own Vulkan device and runs the
 * GameScopeVK compute pass graph entirely from the embedded SPIR-V bundle.
 *
 * Integration flow:
 *   1. At launch, copy libgn_framegen.so + the implicit-layer manifest into the
 *      Bionic container and set GN_FG_* / VK_LAYER_PATH env vars.
 *   2. The Vulkan layer intercepts vkCreateSwapchainKHR/vkQueuePresentKHR,
 *      allocates real AHardwareBuffers for prev/curr/output, and creates the
 *      native framegen context with those AHB pointers.
 *   3. The direct [createContext]/[present] JNI path is kept for tests and any
 *      future non-layer producer that already owns AHBs.
 */
object GNFramegenManager {
    private const val TAG = "GNFramegenManager"

    // Container extra keys (persisted alongside LSFG keys)
    const val EXTRA_ENABLED    = "gnFramegenEnabled"
    const val EXTRA_MULTIPLIER = "gnFramegenMultiplier"
    const val EXTRA_FLOW_SCALE = "gnFramegenFlowScale"
    const val EXTRA_MODEL      = "gnFramegenModel"

    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val LIB_FILENAME = "libgn_framegen.so"
    private const val MANIFEST_FILENAME = "VkLayer_GN_gamescope_framegen.json"
    private const val VERSION_FILENAME = ".gn_framegen_runtime_version"
    private const val RUNTIME_VERSION = "v0.2.0-gn-layer-ahb"
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    private const val ENV_ENABLE = "GN_FG_ENABLE"
    private const val ENV_MULTIPLIER = "GN_FG_MULTIPLIER"
    private const val ENV_FLOW_SCALE = "GN_FG_FLOW_SCALE"
    private const val ENV_MODEL = "GN_FG_MODEL"

    // ── State ─────────────────────────────────────────────────────────────────

    private var contextHandle: Long = 0L
    @Volatile private var libraryReady: Boolean? = null

    // ── Container helpers ─────────────────────────────────────────────────────

    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    fun isEnabled(container: Container): Boolean =
        isSupported(container) &&
            container.getExtra(EXTRA_ENABLED, "false").toBoolean()

    fun multiplier(container: Container): Int =
        (container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)

    fun flowScale(container: Container): Float =
        (container.getExtra(EXTRA_FLOW_SCALE, "0.6").toFloatOrNull() ?: 0.6f).coerceIn(0.2f, 1.0f)

    fun model(container: Container): Int =
        (container.getExtra(EXTRA_MODEL, "0").toIntOrNull() ?: 0).coerceIn(0, 1)

    // ── Library bootstrap ─────────────────────────────────────────────────────

    /**
     * Checks that libgn_framegen.so loaded cleanly and all embedded shaders are valid.
     * Safe to call from any thread; caches result after first call.
     */
    fun ensureLibraryReady(): Boolean {
        libraryReady?.let { return it }
        return try {
            val ok = GNFramegenNative.nativeIsReady()
            val shaders = GNFramegenNative.nativeShaderCount()
            val valid   = GNFramegenNative.nativeValidShaderCount()
            Timber.tag(TAG).i("Bundle: %d/%d shaders valid, ready=%b", valid, shaders, ok)
            libraryReady = ok
            ok
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).e(e, "libgn_framegen.so failed to load")
            libraryReady = false
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "ensureLibraryReady exception")
            libraryReady = false
            false
        }
    }

    fun bundleDescription(): String = runCatching {
        GNFramegenNative.nativeDescribeBundle()
    }.getOrElse { "<unavailable>" }

    /** Install the GN Vulkan implicit layer into the Bionic container. */
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

        val sourceLib = File(context.applicationInfo.nativeLibraryDir, LIB_FILENAME)
        val needsInstall = installedVersion != RUNTIME_VERSION ||
            !libFile.isFile || !manifestFile.isFile || libFile.length() != sourceLib.length()

        if (!needsInstall) return true
        return try {
            localLibDir.mkdirs()
            layerDir.mkdirs()
            if (!sourceLib.isFile) {
                Timber.tag(TAG).e("Bundled %s not found in %s", LIB_FILENAME, context.applicationInfo.nativeLibraryDir)
                return false
            }
            if (!FileUtils.copy(sourceLib, libFile)) {
                Timber.tag(TAG).e("Failed to copy %s into container", LIB_FILENAME)
                return false
            }
            val manifestText = """
                {
                  "file_format_version": "1.0.0",
                  "layer": {
                    "name": "VK_LAYER_GN_gamescope_framegen",
                    "type": "GLOBAL",
                    "library_path": "$MANIFEST_LIBRARY_PATH",
                    "api_version": "1.1.0",
                    "implementation_version": "2",
                    "description": "GameNative standalone frame generation (GameScopeVK shaders)",
                    "functions": {
                      "vkGetInstanceProcAddr": "GNFramegen_GetInstanceProcAddr",
                      "vkGetDeviceProcAddr": "GNFramegen_GetDeviceProcAddr"
                    },
                    "enable_environment": { "GN_FG_ENABLE": "1" }
                  }
                }
            """.trimIndent()
            FileUtils.writeString(manifestFile, manifestText)
            FileUtils.writeString(versionFile, RUNTIME_VERSION)
            FileUtils.chmod(libFile, 0b111101101)
            FileUtils.chmod(manifestFile, 0b110100100)
            FileUtils.chmod(versionFile, 0b110100100)
            Timber.tag(TAG).i("Installed GN Framegen runtime %s into %s", RUNTIME_VERSION, rootDir)
            libFile.isFile && manifestFile.isFile
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install GN Framegen runtime")
            false
        }
    }

    /** Apply GN layer environment. Returns true when the implicit layer is armed. */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_ENABLE)
        envVars.remove(ENV_MULTIPLIER)
        envVars.remove(ENV_FLOW_SCALE)
        envVars.remove(ENV_MODEL)

        if (!isEnabled(container)) {
            disableLayerInContainer(container)
            return false
        }

        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        // Mutual exclusion with lsfg-vk: if GN is active, do not let the LSFG
        // manifest in the same directory load as a second framegen layer.
        File(layerDir, "VkLayer_LS_frame_generation.json").delete()

        envVars.put(ENV_ENABLE, "1")
        envVars.put(ENV_MULTIPLIER, multiplier(container).toString())
        envVars.put(ENV_FLOW_SCALE, String.format(java.util.Locale.US, "%.3f", flowScale(container)))
        envVars.put(ENV_MODEL, model(container).toString())

        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.split(':').filter { it.isNotBlank() }.contains(layerDir.absolutePath)) {
            envVars.put("VK_LAYER_PATH", existingLayerPath)
        } else if (existingLayerPath.isNotEmpty()) {
            envVars.put("VK_LAYER_PATH", "$existingLayerPath:${layerDir.absolutePath}")
        } else {
            envVars.put("VK_LAYER_PATH", layerDir.absolutePath)
        }
        Timber.tag(TAG).i(
            "GN Framegen armed: multiplier=%d, flowScale=%.2f, model=%d",
            multiplier(container), flowScale(container), model(container)
        )
        return true
    }

    private fun disableLayerInContainer(container: Container) {
        val manifest = File(File(container.rootDir, LAYER_RELATIVE_DIR), MANIFEST_FILENAME)
        if (manifest.exists()) manifest.delete()
    }

    // ── Vulkan context lifecycle ──────────────────────────────────────────────

    /**
     * Creates the Vulkan frame generation context from AHardwareBuffers.
     *
     * [prevAhbBuf] and [currAhbBuf] are DirectByteBuffers wrapping AHardwareBuffer*.
     * [outputAhbBufs] has (multiplier-1) entries for generated output frames.
     *
     * Returns true on success.
     */
    fun createContext(
        prevAhbBuf: ByteBuffer,
        currAhbBuf: ByteBuffer,
        outputAhbBufs: Array<ByteBuffer>,
        width: Int,
        height: Int,
        multiplier: Int,
        flowScale: Float,
        model: Int,
    ): Boolean {
        destroyContext() // clean up any existing context
        return try {
            val h = GNFramegenNative.nativeCreateContext(
                prevAhbBuf, currAhbBuf, outputAhbBufs,
                width, height, multiplier, flowScale, model
            )
            if (h == 0L) {
                Timber.tag(TAG).e("nativeCreateContext returned null handle")
                false
            } else {
                contextHandle = h
                Timber.tag(TAG).i("Context created: %s",
                    GNFramegenNative.nativeDescribeContext(h))
                true
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "createContext exception")
            false
        }
    }

    /**
     * Dispatches one frame generation cycle.
     * Called once per "real" frame with the latest prev/curr AHBs.
     */
    fun present(prevAhbBuf: ByteBuffer, currAhbBuf: ByteBuffer): Boolean {
        val h = contextHandle
        if (h == 0L) return false
        return try {
            GNFramegenNative.nativePresent(h, prevAhbBuf, currAhbBuf)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "present exception")
            false
        }
    }

    /**
     * Hot-reload: update frame gen parameters without recreating the context.
     */
    fun updateConfig(container: Container) {
        val h = contextHandle
        if (h == 0L) return
        GNFramegenNative.nativeContextUpdateConfig(
            h, multiplier(container), flowScale(container), model(container)
        )
    }

    /** Destroys the Vulkan context and frees all GPU resources. */
    fun destroyContext() {
        val h = contextHandle
        if (h != 0L) {
            contextHandle = 0L
            try { GNFramegenNative.nativeDestroyContext(h) }
            catch (e: Exception) { Timber.tag(TAG).e(e, "destroyContext exception") }
        }
    }

    val hasActiveContext get() = contextHandle != 0L
}
