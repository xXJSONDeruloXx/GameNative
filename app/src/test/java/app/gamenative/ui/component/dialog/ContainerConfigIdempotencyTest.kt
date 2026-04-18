package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.SteamApp
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.EnumSet

/**
 * Tests for idempotent container configuration behavior.
 *
 * When a setting that affects the Steam DRM/DLL setup is toggled,
 * the STEAM_DLL_REPLACED and STEAM_COLDCLIENT_USED markers must be removed
 * so the next game launch re-evaluates the correct setup.
 *
 * Similarly, toggling launchRealSteam off should set needsUnpacking = true
 * so the ColdClient/Steamless DRM pipeline runs again.
 */
@RunWith(RobolectricTestRunner::class)
class ContainerConfigIdempotencyTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var container: Container
    private lateinit var appDirPath: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("container_idempotency_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        appDirPath = File(SteamService.internalAppInstallPath, "123456").apply { mkdirs() }.absolutePath

        val db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val testApp = SteamApp(
            id = 123456,
            name = "Test Game",
            config = ConfigInfo(installDir = "123456"),
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
        )
        runBlocking { db.steamAppDao().insert(testApp) }

        val mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())
        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        val instanceField = SteamService::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, mockSteamService)

        container = Container("STEAM_123456")
        container.setRootDir(tempDir)

        File(tempDir, ".wine").apply { mkdirs() }
        File(tempDir, ".wine/user.reg").apply {
            if (!exists()) writeText("REGEDIT4\n")
        }
    }

    // ─── Marker cleanup: launchRealSteam ──────────────────────────────

    @Test
    fun togglingLaunchRealSteamOff_removesSteamDllReplacedMarker() {
        // Arrange: container has launchRealSteam=true, markers present
        container.isLaunchRealSteam = true
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
        assertTrue("Precondition: marker should exist", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))

        val data = ContainerData(launchRealSteam = false)

        // Act
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        // Assert
        assertFalse("STEAM_DLL_REPLACED marker should be removed", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
    }

    @Test
    fun togglingLaunchRealSteamOff_removesSteamColdClientUsedMarker() {
        container.isLaunchRealSteam = true
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        val data = ContainerData(launchRealSteam = false)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertFalse("STEAM_COLDCLIENT_USED marker should be removed", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun togglingLaunchRealSteamOn_removesSteamDllReplacedMarker() {
        // Toggle from off → on should also clear markers (different DRM path)
        container.isLaunchRealSteam = false
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        val data = ContainerData(launchRealSteam = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertFalse("STEAM_DLL_REPLACED marker should be removed", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
        assertFalse("STEAM_COLDCLIENT_USED marker should be removed", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun keepingLaunchRealSteamSame_doesNotRemoveMarkers() {
        container.isLaunchRealSteam = true
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)

        val data = ContainerData(launchRealSteam = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertTrue("Marker should remain when value unchanged", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
    }

    // ─── Marker cleanup: steamType ────────────────────────────────────

    @Test
    fun togglingSteamType_removesSteamDllReplacedMarker() {
        container.setSteamType(Container.STEAM_TYPE_NORMAL)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        val data = ContainerData(steamType = Container.STEAM_TYPE_LIGHT)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertFalse("STEAM_DLL_REPLACED marker should be removed on steamType change", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
        assertFalse("STEAM_COLDCLIENT_USED marker should be removed on steamType change", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun keepingSteamTypeSame_doesNotRemoveMarkers() {
        container.setSteamType(Container.STEAM_TYPE_NORMAL)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)

        val data = ContainerData(steamType = Container.STEAM_TYPE_NORMAL)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertTrue("Marker should remain when steamType unchanged", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
    }

    // ─── Marker cleanup: allowSteamUpdates ────────────────────────────

    @Test
    fun togglingAllowSteamUpdates_removesSteamDllReplacedMarker() {
        container.isAllowSteamUpdates = false
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        val data = ContainerData(allowSteamUpdates = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertFalse("STEAM_DLL_REPLACED marker should be removed on allowSteamUpdates change", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
        assertFalse("STEAM_COLDCLIENT_USED marker should be removed on allowSteamUpdates change", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun keepingAllowSteamUpdatesSame_doesNotRemoveMarkers() {
        container.isAllowSteamUpdates = true
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)

        val data = ContainerData(allowSteamUpdates = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertTrue("Marker should remain when allowSteamUpdates unchanged", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
    }

    // ─── needsUnpacking trigger: launchRealSteam toggle ───────────────

    @Test
    fun togglingLaunchRealSteamOff_setsNeedsUnpackingTrue() {
        container.isLaunchRealSteam = true
        container.setNeedsUnpacking(false)

        val data = ContainerData(launchRealSteam = false)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertTrue("needsUnpacking should be true when launchRealSteam toggled off", container.isNeedsUnpacking)
    }

    @Test
    fun togglingLaunchRealSteamOn_setsNeedsUnpackingTrue() {
        container.isLaunchRealSteam = false
        container.setNeedsUnpacking(false)

        val data = ContainerData(launchRealSteam = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertTrue("needsUnpacking should be true when launchRealSteam toggled on", container.isNeedsUnpacking)
    }

    @Test
    fun keepingLaunchRealSteamSame_doesNotSetNeedsUnpacking() {
        container.isLaunchRealSteam = true
        container.setNeedsUnpacking(false)

        val data = ContainerData(launchRealSteam = true)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = false)

        assertFalse("needsUnpacking should not change when launchRealSteam unchanged", container.isNeedsUnpacking)
    }

    // ─── Idempotency: toggling back and forth is stable ───────────────

    @Test
    fun togglingLaunchRealSteam_twice_removesMarkersBothTimes() {
        container.isLaunchRealSteam = false
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)

        // Toggle on
        ContainerUtils.applyToContainer(context, container, ContainerData(launchRealSteam = true), saveToDisk = false)
        assertFalse("First toggle: marker removed", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))

        // Re-add marker (simulating a game run that sets it)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        assertTrue("Marker re-added between toggles", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))

        // Toggle off again
        ContainerUtils.applyToContainer(context, container, ContainerData(launchRealSteam = false), saveToDisk = false)
        assertFalse("Second toggle: marker removed again", MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
    }

    @Test
    fun togglingLaunchRealSteamWithSaveToDisk_resetsSteamModeArtifacts() {
        container.isLaunchRealSteam = true

        val steamDir = File(tempDir, ".wine/drive_c/Program Files (x86)/Steam")
        val localSteamDir = File(tempDir, ".wine/drive_c/users/xuser/AppData/Local/Steam")
        val roamingSteamDir = File(tempDir, ".wine/drive_c/users/xuser/AppData/Roaming/Steam")
        val programDataDir = File(tempDir, ".wine/drive_c/ProgramData")

        File(steamDir, "config/config.vdf").apply { parentFile.mkdirs(); writeText("config") }
        File(steamDir, "userdata/392297941/config/localconfig.vdf").apply { parentFile.mkdirs(); writeText("local") }
        File(localSteamDir, "htmlcache/LocalPrefs.json").apply { parentFile.mkdirs(); writeText("prefs") }
        File(roamingSteamDir, "logs/bootstrap_log.txt").apply { parentFile.mkdirs(); writeText("log") }
        File(programDataDir, "Microsoft/Windows/Start Menu/Programs/Notepad.lnk").apply { parentFile.mkdirs(); writeText("keep") }

        val data = ContainerData(launchRealSteam = false)
        ContainerUtils.applyToContainer(context, container, data, saveToDisk = true)

        assertFalse("Steam install dir should be removed on mode switch", steamDir.exists())
        assertFalse("Local Steam cache should be removed on mode switch", localSteamDir.exists())
        assertFalse("Roaming Steam dir should be removed on mode switch", roamingSteamDir.exists())
        assertTrue("ProgramData should be preserved", programDataDir.exists())
        assertTrue(File(programDataDir, "Microsoft/Windows/Start Menu/Programs/Notepad.lnk").exists())
    }

    @Test
    fun togglingUseLegacyDrm_removesMarkersAndSetsNeedsUnpacking() {
        container.setUseLegacyDRM(false)
        container.setNeedsUnpacking(false)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        ContainerUtils.applyToContainer(context, container, ContainerData(useLegacyDRM = true), saveToDisk = false)

        assertTrue("needsUnpacking should be true when useLegacyDRM changes", container.isNeedsUnpacking)
        assertFalse(MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
        assertFalse(MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun togglingUnpackFilesOffWithSaveToDisk_resetsUnpackingArtifacts() {
        container.setUnpackFiles(true)
        container.setNeedsUnpacking(false)

        val imageFs = com.winlator.xenvironment.ImageFs.find(tempDir)
        val gameDir = File(imageFs.wineprefix, "dosdevices/a:/Game")
        gameDir.mkdirs()
        val mainExe = File(gameDir, "Main.exe").apply { writeText("current-unpacked") }
        File(gameDir, "Main.exe.original.exe").apply { writeText("original-main") }
        File(gameDir, "Main.exe.unpacked.exe").apply { writeText("unpacked-main") }
        val extraDllDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/extra_dlls")
        File(extraDllDir, "StubDRM64.dll").apply { parentFile.mkdirs(); writeText("stub") }

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

        ContainerUtils.applyToContainer(context, container, ContainerData(unpackFiles = false), saveToDisk = true)

        assertTrue("needsUnpacking should be true when unpackFiles changes", container.isNeedsUnpacking)
        assertEquals("original-main", mainExe.readText())
        assertFalse(File(gameDir, "Main.exe.original.exe").exists())
        assertFalse(File(gameDir, "Main.exe.unpacked.exe").exists())
        assertFalse(extraDllDir.exists())
        assertFalse(MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED))
        assertFalse(MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED))
    }
}
