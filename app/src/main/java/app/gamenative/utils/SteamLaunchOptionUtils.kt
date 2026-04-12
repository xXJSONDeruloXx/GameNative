package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import java.nio.file.Paths

const val STEAM_SELECTED_LAUNCH_SIGNATURE_KEY = "steamSelectedLaunchSignature"
const val STEAM_TRANSIENT_LAUNCH_SELECTION_KEY = "steamTransientLaunchSelection"

fun buildSteamLaunchSignature(launchInfo: LaunchInfo): String {
    return listOf(
        launchInfo.executable,
        launchInfo.workingDir,
        launchInfo.description,
        launchInfo.type,
    ).joinToString("\u001F")
}

fun findSteamLaunchInfoBySignature(
    launchInfos: List<LaunchInfo>,
    signature: String,
): LaunchInfo? {
    if (signature.isBlank()) return null
    return launchInfos.firstOrNull { buildSteamLaunchSignature(it) == signature }
}

fun resolveSteamLaunchInfo(
    launchInfos: List<LaunchInfo>,
    selectedSignature: String,
): LaunchInfo? {
    return findSteamLaunchInfoBySignature(launchInfos, selectedSignature)
        ?: launchInfos.firstOrNull()
}

fun getSteamLaunchOptionLabel(
    launchInfo: LaunchInfo,
    index: Int,
): String {
    val description = launchInfo.description.trim()
    if (description.isNotEmpty()) return description

    val executableName = launchInfo.executable
        .replace('\\', '/')
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Paths.get(it).fileName?.toString() }.getOrNull() }
        .orEmpty()

    return executableName.ifBlank { "Option ${index + 1}" }
}
