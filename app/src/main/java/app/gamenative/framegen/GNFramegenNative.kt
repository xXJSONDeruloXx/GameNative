package app.gamenative.framegen

import java.nio.ByteBuffer

object GNFramegenNative {
    init {
        System.loadLibrary("gn_framegen")
    }

    // ── Bundle bootstrap ──────────────────────────────────────────────────────
    external fun nativeIsReady(): Boolean
    external fun nativeShaderCount(): Int
    external fun nativeValidShaderCount(): Int
    external fun nativeDescribeBundle(): String

    // ── Session (config holder, no Vulkan) ────────────────────────────────────
    external fun nativeCreateSession(
        width: Int, height: Int, multiplier: Int, flowScale: Float, model: Int
    ): Long
    external fun nativeDestroySession(handle: Long)
    external fun nativeUpdateSessionConfig(
        handle: Long, width: Int, height: Int, multiplier: Int, flowScale: Float, model: Int
    ): Boolean
    external fun nativeDescribeSession(handle: Long): String

    // ── FramegenContext (real Vulkan + AHB pipeline) ───────────────────────────
    // prevAhb / currAhb / outputAhbs must be ByteBuffers wrapping AHardwareBuffer*
    // (use AHardwareBuffer_toHardwareBuffer / AHardwareBuffer_fromHardwareBuffer JNI helpers)
    external fun nativeCreateContext(
        prevAhb: ByteBuffer,
        currAhb: ByteBuffer,
        outputAhbs: Array<ByteBuffer>,
        width: Int, height: Int,
        multiplier: Int, flowScale: Float, model: Int
    ): Long
    external fun nativeDestroyContext(handle: Long)
    external fun nativePresent(handle: Long, prevAhb: ByteBuffer, currAhb: ByteBuffer): Boolean
    external fun nativeDescribeContext(handle: Long): String
    external fun nativeContextUpdateConfig(handle: Long, multiplier: Int, flowScale: Float, model: Int)
}
