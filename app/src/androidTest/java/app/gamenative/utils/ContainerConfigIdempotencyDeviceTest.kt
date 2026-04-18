package app.gamenative.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.enums.Marker
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device validation of container configuration idempotency fixes.
 * Runs as a connected Android instrumented test against real container data.
 */
@RunWith(AndroidJUnit4::class)
class ContainerConfigIdempotencyDeviceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadService.populateDownloadService(context)
    }

    /**
     * Validates that toggling launchRealSteam on a real container removes
     * the STEAM_DLL_REPLACED and STEAM_COLDCLIENT_USED markers from the
     * game's app directory.
     */
    @Test
    fun toggleLaunchRealSteam_clearsMarkersOnRealDevice() {
        // Find a real Steam container on this device
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device — install a game first", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)
        assertNotNull("Could not extract game ID from container ${container.id}", gameId)
        val appDirPath = SteamService.getAppDirPath(gameId!!)
        assertTrue("Game directory must exist: $appDirPath", File(appDirPath).isDirectory)

        // Capture the original launchRealSteam state
        val originalLaunchRealSteam = container.isLaunchRealSteam

        // Create marker files to simulate state after a game run
        val markerDllReplaced = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        val markerColdClient = File(appDirPath, Marker.STEAM_COLDCLIENT_USED.fileName)
        markerDllReplaced.createNewFile()
        markerColdClient.createNewFile()
        assertTrue("Precondition: DLL marker should exist", markerDllReplaced.exists())
        assertTrue("Precondition: ColdClient marker should exist", markerColdClient.exists())

        try {
            // Build ContainerData that toggles launchRealSteam
            val currentData = ContainerUtils.toContainerData(container)
            val toggledData = currentData.copy(launchRealSteam = !originalLaunchRealSteam)

            // Apply the config change
            ContainerUtils.applyToContainer(context, container, toggledData, saveToDisk = true)

            // Verify markers are removed
            assertFalse(
                "STEAM_DLL_REPLACED marker should be removed after toggling launchRealSteam",
                markerDllReplaced.exists(),
            )
            assertFalse(
                "STEAM_COLDCLIENT_USED marker should be removed after toggling launchRealSteam",
                markerColdClient.exists(),
            )
        } finally {
            // Restore original state
            val restoreData = ContainerUtils.toContainerData(container).copy(launchRealSteam = originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, restoreData, saveToDisk = true)
            // Clean up any markers we created if they weren't already cleaned
            markerDllReplaced.delete()
            markerColdClient.delete()
        }
    }

    /**
     * Validates that toggling steamType on a real container removes markers.
     */
    @Test
    fun toggleSteamType_clearsMarkersOnRealDevice() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)
        val appDirPath = SteamService.getAppDirPath(gameId!!)
        if (!File(appDirPath).isDirectory) {
            println("SKIP: Game directory not found for ${container.id}")
            return
        }

        val originalSteamType = container.getSteamType()

        val markerDllReplaced = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        val markerColdClient = File(appDirPath, Marker.STEAM_COLDCLIENT_USED.fileName)
        markerDllReplaced.createNewFile()
        markerColdClient.createNewFile()

        try {
            val currentData = ContainerUtils.toContainerData(container)
            val newSteamType = when (originalSteamType) {
                Container.STEAM_TYPE_NORMAL -> Container.STEAM_TYPE_LIGHT
                else -> Container.STEAM_TYPE_NORMAL
            }
            val toggledData = currentData.copy(steamType = newSteamType)

            ContainerUtils.applyToContainer(context, container, toggledData, saveToDisk = true)

            assertFalse(
                "STEAM_DLL_REPLACED marker should be removed after toggling steamType",
                markerDllReplaced.exists(),
            )
            assertFalse(
                "STEAM_COLDCLIENT_USED marker should be removed after toggling steamType",
                markerColdClient.exists(),
            )
        } finally {
            val restoreData = ContainerUtils.toContainerData(container).copy(steamType = originalSteamType)
            ContainerUtils.applyToContainer(context, container, restoreData, saveToDisk = true)
            markerDllReplaced.delete()
            markerColdClient.delete()
        }
    }

    /**
     * Validates that toggling launchRealSteam sets needsUnpacking = true.
     */
    @Test
    fun toggleLaunchRealSteam_setsNeedsUnpackingOnRealDevice() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val originalLaunchRealSteam = container.isLaunchRealSteam

        try {
            container.setNeedsUnpacking(false)

            val currentData = ContainerUtils.toContainerData(container)
            val toggledData = currentData.copy(launchRealSteam = !originalLaunchRealSteam)

            ContainerUtils.applyToContainer(context, container, toggledData, saveToDisk = true)

            assertTrue(
                "needsUnpacking should be true after toggling launchRealSteam",
                container.isNeedsUnpacking,
            )
        } finally {
            val restoreData = ContainerUtils.toContainerData(container).copy(launchRealSteam = originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, restoreData, saveToDisk = true)
        }
    }

    /**
     * Validates that keeping launchRealSteam the same does NOT remove markers.
     */
    @Test
    fun keepLaunchRealSteamSame_doesNotRemoveMarkers() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)
        val appDirPath = SteamService.getAppDirPath(gameId!!)
        if (!File(appDirPath).isDirectory) {
            println("SKIP: Game directory not found for ${container.id}")
            return
        }

        val markerDllReplaced = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        markerDllReplaced.createNewFile()

        try {
            val currentData = ContainerUtils.toContainerData(container)
            // Apply same value — markers should remain
            ContainerUtils.applyToContainer(context, container, currentData, saveToDisk = true)

            assertTrue(
                "STEAM_DLL_REPLACED marker should remain when launchRealSteam unchanged",
                markerDllReplaced.exists(),
            )
        } finally {
            markerDllReplaced.delete()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun findRealSteamContainer(): Container? {
        val containerManager = com.winlator.container.ContainerManager(context)
        for (container in containerManager.containers) {
            if (container.id.startsWith("STEAM_")) {
                return container
            }
        }
        return null
    }
}
