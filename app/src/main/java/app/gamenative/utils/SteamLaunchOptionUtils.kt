package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import java.nio.file.Paths

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
