package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Manages the GameScopeVK Vulkan ICD for frame generation.
 *
 * Unlike LSFG-VK (which is a Vulkan implicit layer that requires Lossless.dll),
 * GameScopeVK is a Vulkan ICD *wrapper*:
 *   - Registers as the Vulkan ICD seen by Wine/DXVK
 *   - Intercepts swapchain presentation, inserts generated frames
 *   - Forwards all other Vulkan calls to the real GPU driver (Turnip/wrapper)
 *   - Has 54 proprietary SPIR-V compute shaders embedded — no Lossless.dll needed
 *
 * Control protocol: a 10-byte little-endian mmap file, path in GAMESCOPE_CONTROL_PATH:
 *   offset 0 (u16): FPS limit (0 = no limit)
 *   offset 2 (u8):  frame interpolation enable (0/1)
 *   offset 3 (u8):  native rendering mode (0 = auto)
 *   offset 4 (f32): flow scale (0.2–1.0; lower = faster, more artifacts)
 *   offset 8 (u8):  model selector (0 = default, 1 = clear/high-quality)
 *   offset 9 (u8):  multiplier (2–4)
 */
object GamescopeVkManager {
    private const val TAG = "GamescopeVkManager"

    // Asset paths (bundled in APK)
    private const val ASSET_DIR = "gamescope_vk/android_arm64_v8a"
    private const val ASSET_LIB = "$ASSET_DIR/libGameScopeVK.so"
    private const val ASSET_XCB_DRI3 = "$ASSET_DIR/libxcb-dri3.so"
    private const val ASSET_XCB_PRESENT = "$ASSET_DIR/libxcb-present.so"

    // Paths inside the container rootDir
    private const val LIB_FILENAME = "libGameScopeVK.so"
    private const val ICD_JSON_FILENAME = "GameScopeVK_icd.json"
    private const val VERSION_FILENAME = ".gamescope_vk_runtime_version"

    private const val LIB_RELATIVE_PATH = "usr/lib/$LIB_FILENAME"
    private const val ICD_JSON_RELATIVE_PATH = "usr/share/vulkan/icd.d/$ICD_JSON_FILENAME"
    // Control file goes in the container's tmp dir (always writable, same lifetime as session)
    private const val CONTROL_FILE_RELATIVE_PATH = "usr/tmp/gamescope.control"

    // Container extra keys
    const val EXTRA_ENABLED = "gamescopeVkEnabled"
    const val EXTRA_MULTIPLIER = "gamescopeVkMultiplier"
    const val EXTRA_FLOW_SCALE = "gamescopeVkFlowScale"
    const val EXTRA_MODEL = "gamescopeVkModel"   // 0=default, 1=clear

    // Environment variables
    private const val ENV_ICD_FILENAMES = "VK_ICD_FILENAMES"
    private const val ENV_CONTROL_PATH = "GAMESCOPE_CONTROL_PATH"
    private const val ENV_DRIVER_PATH = "GAMESCOPE_DRIVER_PATH"

    // Control file byte layout
    private const val CTRL_FPS_OFFSET = 0    // u16 LE
    private const val CTRL_ENABLE_OFFSET = 2  // u8
    private const val CTRL_RENDER_OFFSET = 3  // u8 (native rendering mode, 0=auto)
    private const val CTRL_FLOW_OFFSET = 4    // f32 LE
    private const val CTRL_MODEL_OFFSET = 8   // u8
    private const val CTRL_MULT_OFFSET = 9    // u8
    private const val CTRL_FILE_SIZE = 10

    // Bump when the bundled .so changes
    private const val RUNTIME_VERSION = "gamescope-vk-v1.0.0"

    // Candidate real-driver .so names, checked in order
    // We check both the container's usr/lib AND the adrenotools content dir
    private val DRIVER_CANDIDATES = listOf(
        "libvulkan_wrapper.so",
        "libvulkan_freedreno.so",
    )

    // adrenotools content dir relative to app files dir
    // e.g. /data/user/0/app.gamenative/files/contents/adrenotools/<driverId>/
    private const val ADRENOTOOLS_CONTENT_REL = "contents/adrenotools"

    // ---- Public API --------------------------------------------------------

    /** Only supported inside Bionic containers (same as LSFG-VK). */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    @JvmStatic
    fun isEnabled(container: Container): Boolean =
        isSupported(container) &&
            container.getExtra(EXTRA_ENABLED, "false").equals("true", ignoreCase = true)

    fun multiplier(container: Container): Int =
        (container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)

    fun flowScale(container: Container): Float =
        (container.getExtra(EXTRA_FLOW_SCALE, "0.6").toFloatOrNull() ?: 0.6f).coerceIn(0.2f, 1.0f)

    fun model(container: Container): Int =
        (container.getExtra(EXTRA_MODEL, "0").toIntOrNull() ?: 0).coerceIn(0, 1)

    /**
     * Install libGameScopeVK.so + ICD JSON into the container.
     * Uses version caching to skip if already up-to-date.
     *
     * @return true if install succeeded or was already current
     */
    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        val rootDir = container.rootDir
        val libFile = File(rootDir, LIB_RELATIVE_PATH)
        val icdJsonFile = File(rootDir, ICD_JSON_RELATIVE_PATH)
        val versionFile = File(icdJsonFile.parentFile, VERSION_FILENAME)

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val needsInstall = installedVersion != RUNTIME_VERSION || !libFile.isFile || !icdJsonFile.isFile

        if (!needsInstall) {
            Timber.tag(TAG).d("Runtime %s already installed", RUNTIME_VERSION)
            return true
        }

        return try {
            libFile.parentFile?.mkdirs()
            icdJsonFile.parentFile?.mkdirs()

            // Copy libGameScopeVK.so
            FileUtils.copy(context, ASSET_LIB, libFile)
            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101) // rwxr-xr-x

            // Copy XCB deps that might be absent in the bionic imagefs
            for ((asset, name) in listOf(ASSET_XCB_DRI3 to "libxcb-dri3.so", ASSET_XCB_PRESENT to "libxcb-present.so")) {
                val dest = File(rootDir, "usr/lib/$name")
                if (!dest.isFile) {
                    FileUtils.copy(context, asset, dest)
                    if (dest.exists()) FileUtils.chmod(dest, 0b111101101)
                }
            }

            // Write ICD JSON with the correct runtime path for this app package
            val icdJsonText = buildIcdJson(libFile.absolutePath)
            FileUtils.writeString(icdJsonFile, icdJsonText)
            if (icdJsonFile.exists()) FileUtils.chmod(icdJsonFile, 0b110100100) // rw-r--r--

            FileUtils.writeString(versionFile, RUNTIME_VERSION)
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

            val ok = libFile.isFile && icdJsonFile.isFile
            if (ok) Timber.tag(TAG).i("Installed GameScopeVK %s → %s", RUNTIME_VERSION, rootDir)
            else Timber.tag(TAG).e("Install verification failed")
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install GameScopeVK runtime")
            false
        }
    }

    /**
     * Write the 10-byte control file and apply env vars for the launch.
     *
     * Sets:
     *   VK_ICD_FILENAMES → GameScopeVK_icd.json  (GameScopeVK becomes the Vulkan ICD)
     *   GAMESCOPE_DRIVER_PATH → real Vulkan driver .so (GameScopeVK wraps it)
     *   GAMESCOPE_CONTROL_PATH → 10-byte mmap control file
     *
     * Disables LSFG env vars so the two systems don't conflict.
     *
     * @return true if armed and env vars applied
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        if (!isEnabled(container)) {
            disableInContainer(container)
            return false
        }

        val rootDir = container.rootDir
        val icdJsonFile = File(rootDir, ICD_JSON_RELATIVE_PATH)
        val controlFile = File(rootDir, CONTROL_FILE_RELATIVE_PATH)

        if (!icdJsonFile.isFile) {
            Timber.tag(TAG).e("ICD JSON missing, cannot arm GameScopeVK")
            return false
        }

        // Write initial control file
        if (!writeControlFile(container, controlFile)) {
            Timber.tag(TAG).e("Failed to write control file")
            return false
        }

        // VK_ICD_FILENAMES: GameScopeVK replaces the existing ICD
        envVars.put(ENV_ICD_FILENAMES, icdJsonFile.absolutePath)

        // DR_SOCK_PATH: Unix socket where the DirectRendering server listens.
        // GameScopeVK connects to this socket to send AHardwareBuffer frames for display.
        // GameHub's libwinemu.so provides the server; we need an equivalent in GameNative.
        // For now, set the path so GameScopeVK knows where to connect.
        val drSockPath = File(rootDir, ".dr.sock").absolutePath
        envVars.put("DR_SOCK_PATH", drSockPath)

        // GAMESCOPE_DRIVER_PATH: real GPU driver that GameScopeVK will wrap
        findDriverSo(rootDir)?.let { driverPath ->
            envVars.put(ENV_DRIVER_PATH, driverPath)
            Timber.tag(TAG).d("Real driver: %s", driverPath)
        } ?: Timber.tag(TAG).w(
            "No real driver found — GameScopeVK will try ro.hardware.vulkan auto-detect"
        )

        envVars.put(ENV_CONTROL_PATH, controlFile.absolutePath)

        // Silence LSFG-VK so it doesn't also try to intercept
        envVars.remove("LSFG_CONFIG")
        envVars.remove("LSFG_PROCESS")

        Timber.tag(TAG).i(
            "GameScopeVK armed: multiplier=%d, flowScale=%.2f, model=%d",
            multiplier(container), flowScale(container), model(container),
        )
        return true
    }

    /**
     * Hot-reload: update the control file while the container is running.
     * GameScopeVK watches the file via inotify and picks up changes within one frame.
     */
    @JvmStatic
    fun updateAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        model: Int,
    ): Boolean {
        if (!isSupported(container)) return false
        val controlFile = File(container.rootDir, CONTROL_FILE_RELATIVE_PATH)
        if (!controlFile.exists()) {
            Timber.tag(TAG).w("Control file missing, cannot hot-reload")
            return false
        }
        return writeControlFileRaw(
            controlFile = controlFile,
            enabled = enabled,
            multiplier = multiplier.coerceIn(2, 4),
            flowScale = flowScale.coerceIn(0.2f, 1.0f),
            model = model.coerceIn(0, 1),
        )
    }

    // ---- Private helpers ---------------------------------------------------

    private fun disableInContainer(container: Container) {
        // Remove the ICD JSON so the Vulkan loader falls back to standard discovery
        val icdJsonFile = File(container.rootDir, ICD_JSON_RELATIVE_PATH)
        if (icdJsonFile.exists()) {
            icdJsonFile.delete()
            Timber.tag(TAG).d("Removed GameScopeVK ICD JSON to disable")
        }
    }

    private fun writeControlFile(container: Container, file: File): Boolean =
        writeControlFileRaw(
            controlFile = file,
            enabled = isEnabled(container),
            multiplier = multiplier(container),
            flowScale = flowScale(container),
            model = model(container),
        )

    private fun writeControlFileRaw(
        controlFile: File,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        model: Int,
    ): Boolean {
        return try {
            controlFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(controlFile, "rw")
            val channel = raf.channel
            if (raf.length() < CTRL_FILE_SIZE) raf.setLength(CTRL_FILE_SIZE.toLong())
            val map = channel.map(FileChannel.MapMode.READ_WRITE, 0L, CTRL_FILE_SIZE.toLong())
            map.order(ByteOrder.LITTLE_ENDIAN)
            map.position(CTRL_FPS_OFFSET)
            map.putShort(0)                              // FPS limit: no limit
            map.put(if (enabled) 1.toByte() else 0)     // enable
            map.put(0)                                   // native rendering mode: auto
            map.position(CTRL_FLOW_OFFSET)
            map.putFloat(flowScale)                      // flow scale
            map.position(CTRL_MODEL_OFFSET)
            map.put(model.toByte())                      // model selector
            map.put(multiplier.toByte())                 // multiplier
            map.force()
            channel.close()
            raf.close()
            Timber.tag(TAG).d(
                "Control file written: enabled=%b mult=%d flow=%.2f model=%d",
                enabled, multiplier, flowScale, model,
            )
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write control file")
            false
        }
    }

    /** Build the ICD JSON with the correct absolute .so path for this container. */
    private fun buildIcdJson(libAbsPath: String): String = """
        {
          "file_format_version": "1.0.0",
          "ICD": {
            "library_path": "$libAbsPath",
            "api_version": "1.3.216"
          }
        }
    """.trimIndent()

    /**
     * Find the real Vulkan driver .so.
     * GameScopeVK will dlopen() this and use it as the backing GPU driver.
     * Priority: adrenotools Turnip driver (matches what the X server uses for DRI3)
     * then fallback to shared imagefs wrapper/freedreno.
     */
    private fun findDriverSo(rootDir: File): String? {
        // First: look in the app files dir for the adrenotools driver
        // Path mirrors what ADRENOTOOLS_DRIVER_PATH env var points to
        // e.g. <filesDir>/contents/adrenotools/turnip26.0.0_R8/vulkan.ad07xx.so
        val appFilesDir = rootDir.parentFile?.parentFile  // files/
            ?: rootDir.parentFile
        if (appFilesDir != null) {
            val adrenoDir = File(appFilesDir, ADRENOTOOLS_CONTENT_REL)
            if (adrenoDir.isDirectory) {
                // find first driver dir that contains a vulkan*.so
                adrenoDir.listFiles()?.forEach { driverDir ->
                    if (driverDir.isDirectory) {
                        driverDir.listFiles { f -> f.name.startsWith("vulkan.") && f.name.endsWith(".so") }
                            ?.firstOrNull { it.isFile }
                            ?.let { return it.absolutePath }
                    }
                }
            }
        }
        // Fallback: container usr/lib
        for (name in DRIVER_CANDIDATES) {
            val f = File(rootDir, "usr/lib/$name")
            if (f.isFile) return f.absolutePath
        }
        // Fallback: shared imagefs usr/lib (where wrapper usually is after extra_libs extraction)
        val sharedLib = rootDir.parentFile?.let { File(it, "imagefs/usr/lib") }
        for (name in DRIVER_CANDIDATES) {
            val f = sharedLib?.let { File(it, name) } ?: continue
            if (f.isFile) return f.absolutePath
        }
        return null
    }
}
