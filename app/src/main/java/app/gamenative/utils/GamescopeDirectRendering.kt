package app.gamenative.utils

import android.view.Surface
import android.view.SurfaceControl
import timber.log.Timber
import java.io.File

/**
 * DirectRendering socket server for GameScopeVK frame presentation.
 *
 * GameScopeVK (libGameScopeVK.so) is a Vulkan ICD wrapper that intercepts
 * vkQueuePresentKHR, generates interpolated frames, then sends the resulting
 * AHardwareBuffer handles to this server over a Unix domain socket.
 *
 * The protocol (reverse-engineered from the binary):
 *   1. Server listens on <rootDir>/.dr.sock
 *   2. GameScopeVK connects as client
 *   3. Server sends a pipe fd (socketpair) for completion signaling
 *   4. Per frame: client writes uint32 image_index + AHardwareBuffer handle
 *   5. Server blits to the Android Surface and signals completion
 */
object GamescopeDirectRendering {

    private const val TAG = "GamescopeDR"
    private const val LIB_NAME = "gamescope_dr"

    init {
        try {
            System.loadLibrary(LIB_NAME)
            Timber.tag(TAG).d("Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).e(e, "Failed to load native library")
        }
    }

    // Native handle (DRServer* as jlong)
    private var nativeHandle: Long = 0L
    private var surfaceControl: SurfaceControl? = null

    // JNI native methods
    private external fun nativeStart(sockPath: String, surface: Surface?): Long
    private external fun nativeStop(handle: Long)
    private external fun nativeIsConnected(handle: Long): Boolean

    /**
     * Start the DirectRendering server.
     * Must be called BEFORE the game process launches.
     *
     * @param rootDir Container root directory (where .dr.sock will be created)
     * @param surface Optional Android Surface to present frames to.
     *                If null, frames are received but not displayed.
     * @return true if the server started successfully
     */
    fun start(rootDir: File, surface: Surface? = null): Boolean {
        if (nativeHandle != 0L) {
            Timber.tag(TAG).w("Server already running, stopping first")
            stop()
        }

        val sockPath = File(rootDir, ".dr.sock").absolutePath
        Timber.tag(TAG).i("Starting DR server on %s", sockPath)

        nativeHandle = nativeStart(sockPath, surface)
        if (nativeHandle == 0L) {
            Timber.tag(TAG).e("Failed to start DR server")
            return false
        }

        Timber.tag(TAG).i("DR server started (handle=%d)", nativeHandle)
        return true
    }

    /**
     * Create a SurfaceControl/Surface pair for the DR server to render into.
     * This creates a new layer that sits on top of the X server's surface.
     */
    fun createSurface(name: String = "GameScopeVK Frame Output"): Surface? {
        try {
            surfaceControl = SurfaceControl.Builder()
                .setName(name)
                .setOpaque(true)
                .build()
            return surfaceControl?.let { Surface(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create SurfaceControl")
            return null
        }
    }

    /**
     * Stop the DirectRendering server.
     */
    fun stop() {
        if (nativeHandle != 0L) {
            Timber.tag(TAG).i("Stopping DR server")
            nativeStop(nativeHandle)
            nativeHandle = 0L
        }
        surfaceControl?.let {
            it.release()
            surfaceControl = null
        }
    }

    /**
     * Check if a GameScopeVK client is currently connected.
     */
    fun isConnected(): Boolean {
        return nativeHandle != 0L && nativeIsConnected(nativeHandle)
    }

    /**
     * Get the DR socket path for a given container root directory.
     */
    fun socketPath(rootDir: File): String {
        return File(rootDir, ".dr.sock").absolutePath
    }
}
