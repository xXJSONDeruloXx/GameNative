package com.winlator.xenvironment

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlibcRuntimePathPatcherTest {
    @Test
    fun patchBytesForPackage_rewritesKnownAbsolutePath() {
        val original = "/data/data/com.winlator/files/imagefs/usr/share/X11/locale"
        val data = byteArrayWithCapacity(original, 96)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(data, "app.gamenative")

        assertEquals(1, patched)
        assertTrue(readCString(data).startsWith("/data/data/app.gamenative/imgfs/usr/share/X11/locale"))
    }

    @Test
    fun patchBytesForPackage_rewritesKnownRelativePackagePath() {
        val original = "app.gamenative/files/imagefs"
        val data = byteArrayWithCapacity(original, 48)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(data, "app.gamenative")

        assertEquals(1, patched)
        assertEquals("app.gamenative/imgfs", readCString(data))
    }

    @Test
    fun patchBytesForPackage_skipsNonNullTerminatedMatches() {
        val bytes = "/data/data/com.winlator/files/imagefs/usr/lib/not-terminated".toByteArray(StandardCharsets.US_ASCII)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(bytes, "app.gamenative")

        assertEquals(0, patched)
    }

    private fun byteArrayWithCapacity(value: String, capacity: Int): ByteArray {
        val raw = value.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(capacity)
        System.arraycopy(raw, 0, out, 0, raw.size)
        return out
    }

    private fun readCString(data: ByteArray): String {
        val end = data.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else data.size }
        return String(data, 0, end, StandardCharsets.US_ASCII)
    }
}
