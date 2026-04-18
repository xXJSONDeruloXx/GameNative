package app.gamenative.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.DownloadService
import com.winlator.container.ContainerData
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generic real-device helper for mutating a container via
 * ContainerUtils.applyToContainer(..., saveToDisk=true).
 *
 * Example:
 * adb shell am instrument -w \
 *   -e class app.gamenative.utils.ContainerConfigMutationDeviceTest \
 *   -e targetAppId STEAM_1562430 \
 *   -e action enableLaunchRealSteam \
 *   app.gamenative.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ContainerConfigMutationDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadService.populateDownloadService(context)
    }

    @Test
    fun mutateRequestedAction() {
        val args = InstrumentationRegistry.getArguments()
        val appId = args.getString("targetAppId")
            ?: throw IllegalArgumentException("Missing instrumentation arg: targetAppId")
        val action = args.getString("action")
            ?: throw IllegalArgumentException("Missing instrumentation arg: action")

        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val current = ContainerUtils.toContainerData(container)
        val updated = when (action) {
            "enableLaunchRealSteam" -> current.copy(launchRealSteam = true)
            "disableLaunchRealSteam" -> current.copy(launchRealSteam = false)
            "enableLegacyDrm" -> current.copy(useLegacyDRM = true)
            "disableLegacyDrm" -> current.copy(useLegacyDRM = false)
            "enableUnpackFiles" -> current.copy(unpackFiles = true)
            "disableUnpackFiles" -> current.copy(unpackFiles = false)
            else -> throw IllegalArgumentException("Unsupported action: $action")
        }

        ContainerUtils.applyToContainer(context, container, updated, saveToDisk = true)

        val reloaded = ContainerUtils.toContainerData(ContainerUtils.getContainer(context, appId))
        when (action) {
            "enableLaunchRealSteam", "disableLaunchRealSteam" -> assertEquals(updated.launchRealSteam, reloaded.launchRealSteam)
            "enableLegacyDrm", "disableLegacyDrm" -> assertEquals(updated.useLegacyDRM, reloaded.useLegacyDRM)
            "enableUnpackFiles", "disableUnpackFiles" -> assertEquals(updated.unpackFiles, reloaded.unpackFiles)
        }
    }
}
