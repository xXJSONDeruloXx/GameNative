package app.gamenative.utils

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.enums.Marker
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.xenvironment.ImageFs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device validation of container configuration idempotency fixes.
 * Each test captures explicit before/after file inventories across all
 * directories that the toggled settings are known to affect:
 *
 *   • Game app dir          — DRM markers (.steam_dll_replaced, .steam_coldclient_used)
 *   • Program Files (x86)/Steam/       — DLLs, steam.exe, ColdClientLoader.ini, config/, steamclient_backup/
 *   • Program Files (x86)/Steam/config/ — config.vdf, loginusers.vdf
 *   • users/xuser/AppData/Local/Steam/  — local.vdf (SteamTokenLogin phase 2)
 *   • ProgramData/                      — potential DRM artifacts
 *   • /etc/                             — config.box64rc (steamType)
 *
 * Inventories are logged at TAG "ContainerIdempotencyAudit" so they can be
 * pulled from logcat for manual review as well.
 */
@RunWith(AndroidJUnit4::class)
class ContainerConfigIdempotencyDeviceTest {

    private lateinit var context: Context
    private lateinit var imageFs: ImageFs

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadService.populateDownloadService(context)
        imageFs = ImageFs.find(context)
    }

    // ─── launchRealSteam toggle: markers cleared ──────────────────────

    @Test
    fun toggleLaunchRealSteam_clearsMarkersAndPrefixUnchanged() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device — install a game first", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)!!
        val appDirPath = SteamService.getAppDirPath(gameId)
        assertTrue("Game directory must exist: $appDirPath", File(appDirPath).isDirectory)

        val originalLaunchRealSteam = container.isLaunchRealSteam

        // Seed both markers
        val dllMarker = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        val coldMarker = File(appDirPath, Marker.STEAM_COLDCLIENT_USED.fileName)
        dllMarker.createNewFile()
        coldMarker.createNewFile()

        // ── BEFORE snapshot ─────────────────────────────────────────
        val before = captureRelevantInventory(container, appDirPath)
        logInventory("toggleLaunchRealSteam BEFORE", before)

        assertTrue("BEFORE: DLL marker must be present", dllMarker.exists())
        assertTrue("BEFORE: ColdClient marker must be present", coldMarker.exists())

        try {
            val toggled = ContainerUtils.toContainerData(container)
                .copy(launchRealSteam = !originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, toggled, saveToDisk = true)

            // ── AFTER snapshot ──────────────────────────────────────
            val after = captureRelevantInventory(container, appDirPath)
            logInventory("toggleLaunchRealSteam AFTER", after)

            // Primary assertions: markers gone
            assertFalse("AFTER: STEAM_DLL_REPLACED marker must be removed", dllMarker.exists())
            assertFalse("AFTER: STEAM_COLDCLIENT_USED marker must be removed", coldMarker.exists())

            // applyToContainer must not touch Steam-prefix dirs (those change at launch time only)
            assertEquals(
                "Steam dir file count must be unchanged by applyToContainer alone",
                before.steamDirCount,
                after.steamDirCount,
            )
            assertEquals(
                "Steam config dir file count must be unchanged by applyToContainer alone",
                before.steamConfigCount,
                after.steamConfigCount,
            )
            assertEquals(
                "Local/Steam dir file count must be unchanged by applyToContainer alone",
                before.localSteamCount,
                after.localSteamCount,
            )
            assertEquals(
                "ProgramData file count must be unchanged by applyToContainer alone",
                before.programDataCount,
                after.programDataCount,
            )
            // Marker count in game dir: both markers removed, so after = before - 2
            assertEquals(
                "Game dir marker count: both markers must have been removed",
                before.gameDirMarkerCount - 2,
                after.gameDirMarkerCount,
            )
        } finally {
            val restore = ContainerUtils.toContainerData(container)
                .copy(launchRealSteam = originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, restore, saveToDisk = true)
            dllMarker.delete()
            coldMarker.delete()
        }
    }

    // ─── launchRealSteam no-op: markers preserved ─────────────────────

    @Test
    fun keepLaunchRealSteamSame_markersPreservedAndPrefixUnchanged() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)!!
        val appDirPath = SteamService.getAppDirPath(gameId)
        if (!File(appDirPath).isDirectory) return // skip if not installed

        val dllMarker = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        dllMarker.createNewFile()

        val before = captureRelevantInventory(container, appDirPath)
        logInventory("keepLaunchRealSteamSame BEFORE", before)

        try {
            val same = ContainerUtils.toContainerData(container)
            ContainerUtils.applyToContainer(context, container, same, saveToDisk = true)

            val after = captureRelevantInventory(container, appDirPath)
            logInventory("keepLaunchRealSteamSame AFTER", after)

            assertTrue("AFTER: DLL marker must still be present", dllMarker.exists())
            assertEquals(
                "Game dir marker count must be unchanged when launchRealSteam not toggled",
                before.gameDirMarkerCount,
                after.gameDirMarkerCount,
            )
            assertEquals("Steam dir unchanged", before.steamDirCount, after.steamDirCount)
            assertEquals("Steam config dir unchanged", before.steamConfigCount, after.steamConfigCount)
            assertEquals("Local/Steam dir unchanged", before.localSteamCount, after.localSteamCount)
            assertEquals("ProgramData unchanged", before.programDataCount, after.programDataCount)
        } finally {
            dllMarker.delete()
        }
    }

    // ─── steamType toggle: markers cleared ────────────────────────────

    @Test
    fun toggleSteamType_clearsMarkersAndPrefixUnchanged() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)!!
        val appDirPath = SteamService.getAppDirPath(gameId)
        if (!File(appDirPath).isDirectory) return

        val originalSteamType = container.getSteamType()
        val newSteamType = if (originalSteamType == Container.STEAM_TYPE_NORMAL)
            Container.STEAM_TYPE_LIGHT else Container.STEAM_TYPE_NORMAL

        val dllMarker = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        val coldMarker = File(appDirPath, Marker.STEAM_COLDCLIENT_USED.fileName)
        dllMarker.createNewFile()
        coldMarker.createNewFile()

        val before = captureRelevantInventory(container, appDirPath)
        logInventory("toggleSteamType BEFORE (type=$originalSteamType)", before)

        try {
            val toggled = ContainerUtils.toContainerData(container).copy(steamType = newSteamType)
            ContainerUtils.applyToContainer(context, container, toggled, saveToDisk = true)

            val after = captureRelevantInventory(container, appDirPath)
            logInventory("toggleSteamType AFTER (type=$newSteamType)", after)

            assertFalse("AFTER: STEAM_DLL_REPLACED marker must be removed", dllMarker.exists())
            assertFalse("AFTER: STEAM_COLDCLIENT_USED marker must be removed", coldMarker.exists())

            // applyToContainer doesn't write box64rc (that's at launch via copyDefaultBox64RCFile)
            assertEquals("Steam dir unchanged", before.steamDirCount, after.steamDirCount)
            assertEquals("Steam config dir unchanged", before.steamConfigCount, after.steamConfigCount)
            assertEquals("ProgramData unchanged", before.programDataCount, after.programDataCount)
            // box64rc NOT expected to change here (written at launch, not at config-save time)
            assertEquals("box64rc unchanged at config-save time", before.box64rcSize, after.box64rcSize)

            assertEquals(
                "Marker count: both markers removed",
                before.gameDirMarkerCount - 2,
                after.gameDirMarkerCount,
            )
        } finally {
            val restore = ContainerUtils.toContainerData(container).copy(steamType = originalSteamType)
            ContainerUtils.applyToContainer(context, container, restore, saveToDisk = true)
            dllMarker.delete()
            coldMarker.delete()
        }
    }

    // ─── steamType no-op: markers preserved ───────────────────────────

    @Test
    fun keepSteamTypeSame_markersPreserved() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)!!
        val appDirPath = SteamService.getAppDirPath(gameId)
        if (!File(appDirPath).isDirectory) return

        val dllMarker = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        dllMarker.createNewFile()

        val before = captureRelevantInventory(container, appDirPath)
        logInventory("keepSteamTypeSame BEFORE", before)

        try {
            val same = ContainerUtils.toContainerData(container)
            ContainerUtils.applyToContainer(context, container, same, saveToDisk = true)

            val after = captureRelevantInventory(container, appDirPath)
            logInventory("keepSteamTypeSame AFTER", after)

            assertTrue("Marker preserved when steamType unchanged", dllMarker.exists())
            assertEquals("Marker count unchanged", before.gameDirMarkerCount, after.gameDirMarkerCount)
        } finally {
            dllMarker.delete()
        }
    }

    // ─── allowSteamUpdates toggle: markers cleared ────────────────────

    @Test
    fun toggleAllowSteamUpdates_clearsMarkersAndPrefixUnchanged() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val gameId = ContainerUtils.extractGameIdFromContainerId(container.id)!!
        val appDirPath = SteamService.getAppDirPath(gameId)
        if (!File(appDirPath).isDirectory) return

        val originalAllowUpdates = container.isAllowSteamUpdates

        val dllMarker = File(appDirPath, Marker.STEAM_DLL_REPLACED.fileName)
        val coldMarker = File(appDirPath, Marker.STEAM_COLDCLIENT_USED.fileName)
        dllMarker.createNewFile()
        coldMarker.createNewFile()

        val before = captureRelevantInventory(container, appDirPath)
        logInventory("toggleAllowSteamUpdates BEFORE", before)

        try {
            val toggled = ContainerUtils.toContainerData(container)
                .copy(allowSteamUpdates = !originalAllowUpdates)
            ContainerUtils.applyToContainer(context, container, toggled, saveToDisk = true)

            val after = captureRelevantInventory(container, appDirPath)
            logInventory("toggleAllowSteamUpdates AFTER", after)

            assertFalse("AFTER: STEAM_DLL_REPLACED marker must be removed", dllMarker.exists())
            assertFalse("AFTER: STEAM_COLDCLIENT_USED marker must be removed", coldMarker.exists())

            assertEquals("Steam dir unchanged", before.steamDirCount, after.steamDirCount)
            assertEquals("Steam config dir unchanged", before.steamConfigCount, after.steamConfigCount)
            assertEquals("ProgramData unchanged", before.programDataCount, after.programDataCount)
            assertEquals(
                "Marker count: both markers removed",
                before.gameDirMarkerCount - 2,
                after.gameDirMarkerCount,
            )
        } finally {
            val restore = ContainerUtils.toContainerData(container)
                .copy(allowSteamUpdates = originalAllowUpdates)
            ContainerUtils.applyToContainer(context, container, restore, saveToDisk = true)
            dllMarker.delete()
            coldMarker.delete()
        }
    }

    // ─── launchRealSteam toggle: needsUnpacking ────────────────────────

    @Test
    fun toggleLaunchRealSteam_setsNeedsUnpacking() {
        val container = findRealSteamContainer()
        assertNotNull("No Steam container found on device", container)
        container!!

        val originalLaunchRealSteam = container.isLaunchRealSteam
        val originalNeedsUnpacking = container.isNeedsUnpacking

        try {
            container.setNeedsUnpacking(false)

            val before = captureRelevantInventory(container,
                SteamService.getAppDirPath(ContainerUtils.extractGameIdFromContainerId(container.id)!!))
            logInventory("toggleLaunchRealSteam_needsUnpacking BEFORE", before)

            val toggled = ContainerUtils.toContainerData(container)
                .copy(launchRealSteam = !originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, toggled, saveToDisk = true)

            val after = captureRelevantInventory(container,
                SteamService.getAppDirPath(ContainerUtils.extractGameIdFromContainerId(container.id)!!))
            logInventory("toggleLaunchRealSteam_needsUnpacking AFTER", after)

            assertTrue("needsUnpacking must be true after toggling launchRealSteam", container.isNeedsUnpacking)
            // Prefix dirs are not touched by applyToContainer
            assertEquals("Steam dir unchanged", before.steamDirCount, after.steamDirCount)
        } finally {
            val restore = ContainerUtils.toContainerData(container)
                .copy(launchRealSteam = originalLaunchRealSteam)
            ContainerUtils.applyToContainer(context, container, restore, saveToDisk = true)
            container.setNeedsUnpacking(originalNeedsUnpacking)
            container.saveData()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Captures a snapshot of file counts and key file sizes across every directory
     * that the launchRealSteam / steamType / allowSteamUpdates toggles are known to affect.
     */
    private fun captureRelevantInventory(container: Container, appDirPath: String): DirectoryInventory {
        val winePrefix = File(imageFs.wineprefix)
        val steamDir = File(winePrefix, "drive_c/Program Files (x86)/Steam")
        val steamConfigDir = File(steamDir, "config")
        val steamClientBackup = File(steamDir, "steamclient_backup")
        val localSteamDir = File(winePrefix, "drive_c/users/${com.winlator.xenvironment.ImageFs.USER}/AppData/Local/Steam")
        val programDataDir = File(winePrefix, "drive_c/ProgramData")
        val etcDir = File(imageFs.rootDir, "etc")
        val box64rc = File(etcDir, "config.box64rc")
        val gameDir = File(appDirPath)

        return DirectoryInventory(
            // Steam install dir (top-level files, not recursive — sub-dirs tracked separately)
            steamDirFiles = steamDir.listTopLevelFiles(),
            steamDirCount = steamDir.countFilesRecursive(),
            steamExePresent = File(steamDir, "steam.exe").exists(),
            coldClientIniPresent = File(steamDir, "ColdClientLoader.ini").exists(),
            coldClientIniContent = File(steamDir, "ColdClientLoader.ini").readSafe(),
            // Steam config dir
            steamConfigFiles = steamConfigDir.listTopLevelFiles(),
            steamConfigCount = steamConfigDir.countFilesRecursive(),
            configVdfPresent = File(steamConfigDir, "config.vdf").exists(),
            configVdfSize = File(steamConfigDir, "config.vdf").lengthSafe(),
            loginUsersVdfPresent = File(steamConfigDir, "loginusers.vdf").exists(),
            // steamclient_backup dir
            steamBackupFiles = steamClientBackup.listTopLevelFiles(),
            steamBackupCount = steamClientBackup.countFilesRecursive(),
            // Local/Steam dir
            localSteamFiles = localSteamDir.listTopLevelFiles(),
            localSteamCount = localSteamDir.countFilesRecursive(),
            localVdfPresent = File(localSteamDir, "local.vdf").exists(),
            localVdfSize = File(localSteamDir, "local.vdf").lengthSafe(),
            // ProgramData
            programDataFiles = programDataDir.listTopLevelFiles(),
            programDataCount = programDataDir.countFilesRecursive(),
            // /etc/config.box64rc (steamType)
            box64rcPresent = box64rc.exists(),
            box64rcSize = box64rc.lengthSafe(),
            box64rcContent = box64rc.readSafe(),
            // Game app dir (markers)
            gameDirMarkerCount = countMarkerFiles(gameDir),
            gameDirFiles = listMarkerFiles(gameDir),
        )
    }

    private fun logInventory(label: String, inv: DirectoryInventory) {
        val tag = "ContainerIdempotencyAudit"
        Log.i(tag, "=== $label ===")
        Log.i(tag, "  steamDir (${inv.steamDirCount} files total): ${inv.steamDirFiles.take(20)}")
        Log.i(tag, "    steam.exe present: ${inv.steamExePresent}")
        Log.i(tag, "    ColdClientLoader.ini present: ${inv.coldClientIniPresent}")
        if (inv.coldClientIniPresent) Log.i(tag, "    ColdClientLoader.ini:\n${inv.coldClientIniContent}")
        Log.i(tag, "  steamConfigDir (${inv.steamConfigCount} files): ${inv.steamConfigFiles}")
        Log.i(tag, "    config.vdf present: ${inv.configVdfPresent} (${inv.configVdfSize} bytes)")
        Log.i(tag, "    loginusers.vdf present: ${inv.loginUsersVdfPresent}")
        Log.i(tag, "  steamClientBackup (${inv.steamBackupCount} files): ${inv.steamBackupFiles}")
        Log.i(tag, "  localSteamDir (${inv.localSteamCount} files): ${inv.localSteamFiles}")
        Log.i(tag, "    local.vdf present: ${inv.localVdfPresent} (${inv.localVdfSize} bytes)")
        Log.i(tag, "  ProgramData (${inv.programDataCount} files): ${inv.programDataFiles}")
        Log.i(tag, "  box64rc present: ${inv.box64rcPresent} (${inv.box64rcSize} bytes)")
        if (inv.box64rcPresent) Log.i(tag, "  box64rc content: ${inv.box64rcContent?.take(120)}")
        Log.i(tag, "  gameDirMarkers (${inv.gameDirMarkerCount}): ${inv.gameDirFiles}")
        Log.i(tag, "===")
    }

    private fun File.listTopLevelFiles(): List<String> =
        listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun File.countFilesRecursive(): Int =
        walk().filter { it.isFile }.count()

    private fun File.lengthSafe(): Long = if (exists()) length() else -1L

    private fun File.readSafe(): String? = if (exists()) runCatching { readText() }.getOrNull() else null

    private fun countMarkerFiles(dir: File): Int =
        dir.listFiles { f -> f.name.startsWith(".steam_") }?.size ?: 0

    private fun listMarkerFiles(dir: File): List<String> =
        dir.listFiles { f -> f.name.startsWith(".steam_") }?.map { it.name }?.sorted() ?: emptyList()

    private fun findRealSteamContainer(): Container? {
        val containerManager = com.winlator.container.ContainerManager(context)
        for (container in containerManager.containers) {
            if (container.id.startsWith("STEAM_")) return container
        }
        return null
    }

    data class DirectoryInventory(
        // Steam install dir
        val steamDirFiles: List<String>,
        val steamDirCount: Int,
        val steamExePresent: Boolean,
        val coldClientIniPresent: Boolean,
        val coldClientIniContent: String?,
        // Steam config dir
        val steamConfigFiles: List<String>,
        val steamConfigCount: Int,
        val configVdfPresent: Boolean,
        val configVdfSize: Long,
        val loginUsersVdfPresent: Boolean,
        // steamclient_backup
        val steamBackupFiles: List<String>,
        val steamBackupCount: Int,
        // Local/Steam
        val localSteamFiles: List<String>,
        val localSteamCount: Int,
        val localVdfPresent: Boolean,
        val localVdfSize: Long,
        // ProgramData
        val programDataFiles: List<String>,
        val programDataCount: Int,
        // box64rc
        val box64rcPresent: Boolean,
        val box64rcSize: Long,
        val box64rcContent: String?,
        // Game app dir markers
        val gameDirMarkerCount: Int,
        val gameDirFiles: List<String>,
    )
}
