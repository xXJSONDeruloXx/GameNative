package app.gamenative.utils

import android.content.Context
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.Locale
import timber.log.Timber

object LsfgVkManager {
    private const val LOSSLESS_SCALING_APP_ID = 993090
    private const val LOSSLESS_DLL_NAME = "Lossless.dll"

    private const val ASSET_LIB_PATH = "lsfg_vk/liblsfg-vk.so"
    private const val ASSET_MANIFEST_PATH = "lsfg_vk/VkLayer_LS_frame_generation.json"
    private const val RUNTIME_VERSION = "arm-test-2025-11-28"

    private const val EXTRA_ENABLED = "lsfgEnabled"
    private const val EXTRA_DLL_PATH = "lsfgDllPath"
    private const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    private const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    private const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"

    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_LEGACY = "LSFG_LEGACY"
    private const val ENV_DLL_PATH = "LSFG_DLL_PATH"
    private const val ENV_MULTIPLIER = "LSFG_MULTIPLIER"
    private const val ENV_FLOW_SCALE = "LSFG_FLOW_SCALE"
    private const val ENV_PERFORMANCE_MODE = "LSFG_PERFORMANCE_MODE"
    private const val ENV_HDR_MODE = "LSFG_HDR_MODE"
    private const val ENV_EXPERIMENTAL_PRESENT_MODE = "LSFG_EXPERIMENTAL_PRESENT_MODE"

    fun isEnabled(container: Container): Boolean = container.getExtra(EXTRA_ENABLED, "0") == "1"

    fun configuredDllPath(container: Container): String = container.getExtra(EXTRA_DLL_PATH, "").trim()

    fun multiplier(container: Container): Int = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull()?.coerceIn(2, 4) ?: 2

    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "1.0").toFloatOrNull()?.coerceIn(0.25f, 1.0f) ?: 1.0f

    fun performanceMode(container: Container): Boolean = container.getExtra(EXTRA_PERFORMANCE_MODE, "0") == "1"

    fun resolveLosslessDllPath(configuredPath: String): String? {
        val overrideFile = configuredPath.trim().takeIf { it.isNotEmpty() }?.let { File(it) }
        if (overrideFile?.isFile == true) {
            return overrideFile.absolutePath
        }

        val steamResolved = File(SteamService.getAppDirPath(LOSSLESS_SCALING_APP_ID), LOSSLESS_DLL_NAME)
        if (steamResolved.isFile) {
            return steamResolved.absolutePath
        }

        val internalFallback = File(SteamService.internalAppInstallPath, "Lossless Scaling/$LOSSLESS_DLL_NAME")
        if (internalFallback.isFile) {
            return internalFallback.absolutePath
        }

        val externalFallback = File(SteamService.externalAppInstallPath, "Lossless Scaling/$LOSSLESS_DLL_NAME")
        if (externalFallback.isFile) {
            return externalFallback.absolutePath
        }

        return null
    }

    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars): Boolean {
        clearLaunchEnv(envVars)

        if (!container.containerVariant.equals(Container.GLIBC, ignoreCase = true)) {
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        if (!isEnabled(container)) {
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        val dllPath = resolveLosslessDllPath(configuredDllPath(container))
        if (dllPath.isNullOrEmpty()) {
            Timber.w("LSFG-VK enabled, but Lossless.dll could not be resolved")
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        if (!ensureRuntimeInstalled(context, container)) {
            Timber.e("LSFG-VK enabled, but runtime installation failed")
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        envVars.remove(ENV_DISABLE)
        envVars.put(ENV_LEGACY, "1")
        envVars.put(ENV_DLL_PATH, dllPath)
        envVars.put(ENV_MULTIPLIER, multiplier(container))
        envVars.put(ENV_FLOW_SCALE, formatFlowScale(flowScale(container)))
        envVars.put(ENV_PERFORMANCE_MODE, if (performanceMode(container)) "1" else "0")

        Timber.i(
            "LSFG-VK armed (dll=%s, multiplier=%d, flowScale=%s, performance=%s)",
            dllPath,
            multiplier(container),
            formatFlowScale(flowScale(container)),
            performanceMode(container),
        )
        return true
    }

    private fun clearLaunchEnv(envVars: EnvVars) {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_LEGACY)
        envVars.remove(ENV_DLL_PATH)
        envVars.remove(ENV_MULTIPLIER)
        envVars.remove(ENV_FLOW_SCALE)
        envVars.remove(ENV_PERFORMANCE_MODE)
        envVars.remove(ENV_HDR_MODE)
        envVars.remove(ENV_EXPERIMENTAL_PRESENT_MODE)
    }

    private fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        return try {
            val homeDir = container.rootDir
            val localLibDir = File(homeDir, ".local/lib")
            val layerDir = File(homeDir, ".local/share/vulkan/implicit_layer.d")
            val libFile = File(localLibDir, "liblsfg-vk.so")
            val manifestFile = File(layerDir, "VkLayer_LS_frame_generation.json")
            val versionFile = File(layerDir, ".lsfg_vk_runtime_version")
            val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            val needsInstall =
                installedVersion != RUNTIME_VERSION ||
                    !libFile.isFile ||
                    !manifestFile.isFile

            if (!needsInstall) {
                return true
            }

            localLibDir.mkdirs()
            layerDir.mkdirs()

            FileUtils.copy(context, ASSET_LIB_PATH, libFile)
            FileUtils.copy(context, ASSET_MANIFEST_PATH, manifestFile)
            FileUtils.writeString(versionFile, RUNTIME_VERSION)

            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
            if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

            val success = libFile.isFile && manifestFile.isFile
            if (success) {
                Timber.i("Installed LSFG-VK runtime into %s", homeDir.absolutePath)
            }
            success
        } catch (t: Throwable) {
            Timber.e(t, "Failed to install LSFG-VK runtime")
            false
        }
    }

    private fun formatFlowScale(value: Float): String = String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))
}
