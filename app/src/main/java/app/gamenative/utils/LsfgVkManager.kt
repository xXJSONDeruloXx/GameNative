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

    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val CONFIG_PROFILE_NAME = "GameNative"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_LSFGVK_frame_generation.json"
    private const val VERSION_FILENAME = ".lsfg_vk_runtime_version"
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // `lsfgEnabled` is the persistent container-level "armed" switch.
    private const val EXTRA_ARMED = "lsfgEnabled"
    private const val EXTRA_EFFECT_ENABLED = "lsfgEffectEnabled"
    private const val EXTRA_DLL_PATH = "lsfgDllPath"
    private const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    private const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    private const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"

    private const val ENV_DISABLE = "DISABLE_LSFGVK"
    private const val ENV_CONFIG = "LSFGVK_CONFIG"
    private const val ENV_PROFILE = "LSFGVK_PROFILE"

    // Clear old v1 env vars too so earlier experiments do not leak into the current v2 path.
    private const val LEGACY_ENV_DISABLE = "DISABLE_LSFG"
    private const val LEGACY_ENV_CONFIG = "LSFG_CONFIG"
    private const val LEGACY_ENV_PROCESS = "LSFG_PROCESS"
    private const val LEGACY_ENV_MODE = "LSFG_LEGACY"
    private const val LEGACY_ENV_DLL_PATH = "LSFG_DLL_PATH"
    private const val LEGACY_ENV_MULTIPLIER = "LSFG_MULTIPLIER"
    private const val LEGACY_ENV_FLOW_SCALE = "LSFG_FLOW_SCALE"
    private const val LEGACY_ENV_PERFORMANCE_MODE = "LSFG_PERFORMANCE_MODE"
    private const val LEGACY_ENV_HDR_MODE = "LSFG_HDR_MODE"
    private const val LEGACY_ENV_EXPERIMENTAL_PRESENT_MODE = "LSFG_EXPERIMENTAL_PRESENT_MODE"

    private data class RuntimeSpec(
        val assetDir: String,
        val version: String,
        val label: String,
    ) {
        val assetLibPath: String get() = "$assetDir/$LIB_FILENAME"
        val assetManifestPath: String get() = "$assetDir/$MANIFEST_FILENAME"
    }

    private val glibcSpec = RuntimeSpec(
        assetDir = "lsfg_vk/glibc_aarch64",
        version = "v2-glibc-aarch64-20260317-77b89b2",
        label = "glibc-aarch64",
    )

    private val bionicSpec = RuntimeSpec(
        assetDir = "lsfg_vk/android_arm64_v8a",
        version = "v1.0.0-android-arm64-v8a",
        label = "android-arm64-v8a",
    )

    fun isSupported(container: Container): Boolean = runtimeSpec(container) != null

    fun isEnabled(container: Container): Boolean = container.getExtra(EXTRA_ARMED, "0") == "1"

    fun configuredDllPath(container: Container): String = container.getExtra(EXTRA_DLL_PATH, "").trim()

    fun multiplier(container: Container): Int =
        container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull()?.coerceIn(2, 4) ?: 2

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

        val spec = runtimeSpec(container)
        if (spec == null) {
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        if (!ensureRuntimeInstalled(context, container, spec)) {
            Timber.e("LSFG-VK runtime installation failed for %s", spec.label)
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        if (!syncConfig(container)) {
            Timber.e("LSFG-VK config write failed")
            envVars.put(ENV_DISABLE, "1")
            return false
        }

        envVars.remove(ENV_DISABLE)
        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_PROFILE, CONFIG_PROFILE_NAME)

        val dllPath = resolveLosslessDllPath(configuredDllPath(container))
        val armed = isEnabled(container) && !dllPath.isNullOrBlank()
        when {
            armed -> Timber.i(
                "LSFG-VK armed via v2 conf.toml (%s, dll=%s, multiplier=%d, flowScale=%s, performance=%s)",
                spec.label,
                dllPath,
                multiplier(container),
                formatFlowScale(flowScale(container)),
                performanceMode(container),
            )
            isEnabled(container) -> Timber.w(
                "LSFG-VK enabled but Lossless.dll could not be resolved; wrote dormant v2 conf.toml",
            )
            else -> Timber.i("LSFG-VK runtime ready (%s); feature currently disabled", spec.label)
        }

        return armed
    }

    fun syncConfig(container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val dllPath = resolveLosslessDllPath(configuredDllPath(container))
            val shouldArm = isEnabled(container) && !dllPath.isNullOrBlank()
            val configFile = configFile(container)
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = shouldArm,
                multiplier = multiplier(container),
                flowScale = flowScale(container),
                performanceMode = performanceMode(container),
            )

            val success = FileUtils.writeString(configFile, configText)
            if (success && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
            }
            success
        } catch (t: Throwable) {
            Timber.e(t, "Failed to write LSFG-VK conf.toml")
            false
        }
    }

    private fun runtimeSpec(container: Container): RuntimeSpec? {
        return when {
            container.containerVariant.equals(Container.GLIBC, ignoreCase = true) -> glibcSpec
            container.containerVariant.equals(Container.BIONIC, ignoreCase = true) -> bionicSpec
            else -> null
        }
    }

    private fun clearLaunchEnv(envVars: EnvVars) {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROFILE)
        envVars.remove(LEGACY_ENV_DISABLE)
        envVars.remove(LEGACY_ENV_CONFIG)
        envVars.remove(LEGACY_ENV_PROCESS)
        envVars.remove(LEGACY_ENV_MODE)
        envVars.remove(LEGACY_ENV_DLL_PATH)
        envVars.remove(LEGACY_ENV_MULTIPLIER)
        envVars.remove(LEGACY_ENV_FLOW_SCALE)
        envVars.remove(LEGACY_ENV_PERFORMANCE_MODE)
        envVars.remove(LEGACY_ENV_HDR_MODE)
        envVars.remove(LEGACY_ENV_EXPERIMENTAL_PRESENT_MODE)
    }

    private fun ensureRuntimeInstalled(context: Context, container: Container, spec: RuntimeSpec): Boolean {
        return try {
            val homeDir = container.rootDir
            val localLibDir = File(homeDir, ".local/lib")
            val layerDir = File(homeDir, ".local/share/vulkan/implicit_layer.d")
            val libFile = File(localLibDir, LIB_FILENAME)
            val manifestFile = File(layerDir, MANIFEST_FILENAME)
            val versionFile = File(layerDir, VERSION_FILENAME)
            val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            val needsInstall =
                installedVersion != spec.version ||
                    !libFile.isFile ||
                    !manifestFile.isFile

            if (!needsInstall) {
                return true
            }

            localLibDir.mkdirs()
            layerDir.mkdirs()

            FileUtils.copy(context, spec.assetLibPath, libFile)
            FileUtils.writeString(manifestFile, patchedManifestText(context, spec))
            FileUtils.writeString(versionFile, spec.version)

            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
            if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

            val success = libFile.isFile && manifestFile.isFile
            if (success) {
                Timber.i("Installed LSFG-VK runtime (%s) into %s", spec.label, homeDir.absolutePath)
            }
            success
        } catch (t: Throwable) {
            Timber.e(t, "Failed to install LSFG-VK runtime")
            false
        }
    }

    private fun patchedManifestText(context: Context, spec: RuntimeSpec): String {
        val original = context.assets.open(spec.assetManifestPath).bufferedReader().use { it.readText() }
        return original.replace(
            oldValue = "\"library_path\": \"$LIB_FILENAME\"",
            newValue = "\"library_path\": \"$MANIFEST_LIBRARY_PATH\"",
        )
    }

    private fun configFile(container: Container): File = File(container.rootDir, CONFIG_RELATIVE_PATH)

    private fun buildConfigToml(
        dllPath: String?,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
    ): String {
        return buildString {
            appendLine("version = 2")
            appendLine()
            appendLine("[global]")
            if (!dllPath.isNullOrBlank()) {
                appendLine("dll = ${tomlString(dllPath)}")
            }
            appendLine("allow_fp16 = true")
            appendLine()

            if (enabled) {
                appendLine("[[profile]]")
                appendLine("name = ${tomlString(CONFIG_PROFILE_NAME)}")
                appendLine("multiplier = ${multiplier.coerceIn(2, 4)}")
                appendLine("flow_scale = ${formatFlowScale(flowScale)}")
                appendLine("performance_mode = ${if (performanceMode) "true" else "false"}")
                appendLine("pacing = \"none\"")
            }
        }
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun formatFlowScale(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))
}
