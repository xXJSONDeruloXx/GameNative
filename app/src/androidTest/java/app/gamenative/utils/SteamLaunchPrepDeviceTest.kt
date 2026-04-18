package app.gamenative.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Generic real-device helper for executing Steam launch-prep code paths without going through
 * the app's cold-start launch intent flow.
 *
 * Supported actions:
 * - prepareColdClient       -> SteamUtils.replaceSteamclientDll(...)
 * - prepareLegacyDrm        -> SteamUtils.replaceSteamApi(...)
 * - extractRealSteamArchive -> extract steam.tzst into the container root
 * - seedUnpackArtifacts     -> create representative *.original.exe / *.unpacked.exe artifacts
 */
@RunWith(AndroidJUnit4::class)
class SteamLaunchPrepDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadService.populateDownloadService(context)
    }

    @Test
    fun runRequestedAction() {
        val args = InstrumentationRegistry.getArguments()
        val appId = args.getString("targetAppId")
            ?: throw IllegalArgumentException("Missing instrumentation arg: targetAppId")
        val action = args.getString("action")
            ?: throw IllegalArgumentException("Missing instrumentation arg: action")

        when (action) {
            "prepareColdClient" -> runBlocking {
                SteamUtils.replaceSteamclientDll(context, appId, false)
            }

            "prepareLegacyDrm" -> runBlocking {
                SteamUtils.replaceSteamApi(context, appId, false)
            }

            "extractRealSteamArchive" -> {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val imageFs = ImageFs.find(context)
                val steamArchive = File(imageFs.filesDir, "steam.tzst")
                assertTrue("steam.tzst must exist on device", steamArchive.exists())
                val success = SteamUtils.extractRealSteamArchive(context, container)
                assertTrue("steam.tzst extraction should succeed for ${container.id}", success)
            }

            "seedUnpackArtifacts" -> {
                val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
                val gameDir = File(SteamService.getAppDirPath(steamAppId))
                val exes = ContainerUtils.filterExesForUnpacking(
                    gameDir.walkTopDown()
                        .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
                        .map { it.relativeTo(gameDir).path }
                        .toList(),
                ).take(4)
                assertTrue("Need at least one executable to seed unpack artifacts", exes.isNotEmpty())
                var seeded = 0
                exes.forEach { relativePath ->
                    val exe = File(gameDir, relativePath)
                    if (!exe.exists() || !exe.isFile) return@forEach

                    val originalBytes = exe.readBytes()
                    val backup = File(exe.parentFile, exe.name + ".original.exe")
                    val unpacked = File(exe.parentFile, exe.name + ".unpacked.exe")
                    backup.writeBytes(originalBytes)
                    unpacked.writeBytes(originalBytes + "\nSEEDED_UNPACK\n".toByteArray())
                    exe.writeBytes(unpacked.readBytes())
                    seeded++
                }
                assertTrue("Should seed at least one unpack artifact", seeded > 0)
            }

            else -> throw IllegalArgumentException("Unsupported action: $action")
        }
    }
}
