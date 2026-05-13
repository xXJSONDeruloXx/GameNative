package app.gamenative.framegen

import android.content.Context
import android.hardware.HardwareBuffer
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * Manages the GN standalone Vulkan frame generation engine.
 *
 * This is the Option-A standalone library: no Lossless.dll, no Vulkan layer shim,
 * no DR socket. The native library owns its own Vulkan device and runs the
 * GameScopeVK compute pass graph entirely from the embedded SPIR-V bundle.
 *
 * Integration flow:
 *   1. At launch, call [ensureLibraryReady] to sanity-check the embedded bundle.
 *   2. After the game surface is available, call [createContext] with the AHB ring.
 *   3. Each frame: call [present] with the latest prev/curr AHBs.
 *   4. At session end, call [destroyContext].
 */
object GNFramegenManager {
    private const val TAG = "GNFramegenManager"

    // Container extra keys (persisted alongside LSFG keys)
    const val EXTRA_ENABLED    = "gnFramegenEnabled"
    const val EXTRA_MULTIPLIER = "gnFramegenMultiplier"
    const val EXTRA_FLOW_SCALE = "gnFramegenFlowScale"
    const val EXTRA_MODEL      = "gnFramegenModel"

    // ── State ─────────────────────────────────────────────────────────────────

    private var contextHandle: Long = 0L
    @Volatile private var libraryReady: Boolean? = null

    // ── Container helpers ─────────────────────────────────────────────────────

    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    fun isEnabled(container: Container): Boolean =
        isSupported(container) &&
            container.getExtra(EXTRA_ENABLED, "false").toBoolean()

    fun multiplier(container: Container): Int =
        (container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)

    fun flowScale(container: Container): Float =
        (container.getExtra(EXTRA_FLOW_SCALE, "0.6").toFloatOrNull() ?: 0.6f).coerceIn(0.2f, 1.0f)

    fun model(container: Container): Int =
        (container.getExtra(EXTRA_MODEL, "0").toIntOrNull() ?: 0).coerceIn(0, 1)

    // ── Library bootstrap ─────────────────────────────────────────────────────

    /**
     * Checks that libgn_framegen.so loaded cleanly and all embedded shaders are valid.
     * Safe to call from any thread; caches result after first call.
     */
    fun ensureLibraryReady(): Boolean {
        libraryReady?.let { return it }
        return try {
            val ok = GNFramegenNative.nativeIsReady()
            val shaders = GNFramegenNative.nativeShaderCount()
            val valid   = GNFramegenNative.nativeValidShaderCount()
            Timber.tag(TAG).i("Bundle: %d/%d shaders valid, ready=%b", valid, shaders, ok)
            libraryReady = ok
            ok
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).e(e, "libgn_framegen.so failed to load")
            libraryReady = false
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "ensureLibraryReady exception")
            libraryReady = false
            false
        }
    }

    fun bundleDescription(): String = runCatching {
        GNFramegenNative.nativeDescribeBundle()
    }.getOrElse { "<unavailable>" }

    // ── Vulkan context lifecycle ──────────────────────────────────────────────

    /**
     * Creates the Vulkan frame generation context from AHardwareBuffers.
     *
     * [prevAhbBuf] and [currAhbBuf] are DirectByteBuffers wrapping AHardwareBuffer*.
     * [outputAhbBufs] has (multiplier-1) entries for generated output frames.
     *
     * Returns true on success.
     */
    fun createContext(
        prevAhbBuf: ByteBuffer,
        currAhbBuf: ByteBuffer,
        outputAhbBufs: Array<ByteBuffer>,
        width: Int,
        height: Int,
        multiplier: Int,
        flowScale: Float,
        model: Int,
    ): Boolean {
        destroyContext() // clean up any existing context
        return try {
            val h = GNFramegenNative.nativeCreateContext(
                prevAhbBuf, currAhbBuf, outputAhbBufs,
                width, height, multiplier, flowScale, model
            )
            if (h == 0L) {
                Timber.tag(TAG).e("nativeCreateContext returned null handle")
                false
            } else {
                contextHandle = h
                Timber.tag(TAG).i("Context created: %s",
                    GNFramegenNative.nativeDescribeContext(h))
                true
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "createContext exception")
            false
        }
    }

    /**
     * Dispatches one frame generation cycle.
     * Called once per "real" frame with the latest prev/curr AHBs.
     */
    fun present(prevAhbBuf: ByteBuffer, currAhbBuf: ByteBuffer): Boolean {
        val h = contextHandle
        if (h == 0L) return false
        return try {
            GNFramegenNative.nativePresent(h, prevAhbBuf, currAhbBuf)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "present exception")
            false
        }
    }

    /**
     * Hot-reload: update frame gen parameters without recreating the context.
     */
    fun updateConfig(container: Container) {
        val h = contextHandle
        if (h == 0L) return
        GNFramegenNative.nativeContextUpdateConfig(
            h, multiplier(container), flowScale(container), model(container)
        )
    }

    /** Destroys the Vulkan context and frees all GPU resources. */
    fun destroyContext() {
        val h = contextHandle
        if (h != 0L) {
            contextHandle = 0L
            try { GNFramegenNative.nativeDestroyContext(h) }
            catch (e: Exception) { Timber.tag(TAG).e(e, "destroyContext exception") }
        }
    }

    val hasActiveContext get() = contextHandle != 0L
}
