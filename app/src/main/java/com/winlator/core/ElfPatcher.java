package com.winlator.core;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ELF patch helper for runtime interpreter replacement.
 *
 * <p>This intentionally only implements the pieces we need for package-aware
 * box64 extraction-time patching and keeps the logic self-contained in Java so
 * we do not depend on an external patchelf binary at runtime.</p>
 */
public final class ElfPatcher {
    private static final String TAG = "ElfPatcher";
    private static final int PT_INTERP = 3;

    private ElfPatcher() {}

    public static boolean patchInterpreter(@NonNull File elfFile, @NonNull String interpreter) {
        if (!elfFile.isFile()) {
            Log.w(TAG, "Not patching missing ELF: " + elfFile.getPath());
            return false;
        }

        byte[] interpreterBytes = interpreter.getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile raf = new RandomAccessFile(elfFile, "rw")) {
            byte[] ident = new byte[16];
            raf.readFully(ident);

            if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
                Log.w(TAG, "Skipping non-ELF file: " + elfFile.getPath());
                return false;
            }

            final int elfClass = ident[4] & 0xFF;
            final int dataEncoding = ident[5] & 0xFF;
            final ByteOrder order;
            if (dataEncoding == 1) {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (dataEncoding == 2) {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                Log.w(TAG, "Unsupported ELF encoding for " + elfFile.getPath() + ": " + dataEncoding);
                return false;
            }

            final long programHeaderOffset;
            final int programHeaderEntrySize;
            final int programHeaderCount;

            if (elfClass == 2) {
                byte[] header = new byte[64];
                raf.seek(0);
                raf.readFully(header);
                ByteBuffer buffer = ByteBuffer.wrap(header).order(order);
                programHeaderOffset = buffer.getLong(32);
                programHeaderEntrySize = buffer.getShort(54) & 0xFFFF;
                programHeaderCount = buffer.getShort(56) & 0xFFFF;
            } else if (elfClass == 1) {
                byte[] header = new byte[52];
                raf.seek(0);
                raf.readFully(header);
                ByteBuffer buffer = ByteBuffer.wrap(header).order(order);
                programHeaderOffset = buffer.getInt(28) & 0xFFFFFFFFL;
                programHeaderEntrySize = buffer.getShort(42) & 0xFFFF;
                programHeaderCount = buffer.getShort(44) & 0xFFFF;
            } else {
                Log.w(TAG, "Unsupported ELF class for " + elfFile.getPath() + ": " + elfClass);
                return false;
            }

            for (int i = 0; i < programHeaderCount; i++) {
                long headerOffset = programHeaderOffset + (long) i * programHeaderEntrySize;
                raf.seek(headerOffset);
                byte[] entry = new byte[programHeaderEntrySize];
                raf.readFully(entry);
                ByteBuffer entryBuffer = ByteBuffer.wrap(entry).order(order);

                final int programType = entryBuffer.getInt(0);
                if (programType != PT_INTERP) {
                    continue;
                }

                final long interpOffset;
                final long interpSize;
                if (elfClass == 2) {
                    interpOffset = entryBuffer.getLong(8);
                    interpSize = entryBuffer.getLong(32);
                } else {
                    interpOffset = entryBuffer.getInt(4) & 0xFFFFFFFFL;
                    interpSize = entryBuffer.getInt(16) & 0xFFFFFFFFL;
                }

                if (interpreterBytes.length + 1 > interpSize) {
                    Log.e(
                            TAG,
                            "Interpreter path too long for " + elfFile.getPath() +
                                    " (need " + (interpreterBytes.length + 1) + ", have " + interpSize + ")"
                    );
                    return false;
                }

                raf.seek(interpOffset);
                raf.write(interpreterBytes);
                raf.writeByte(0);
                for (long pad = interpreterBytes.length + 1; pad < interpSize; pad++) {
                    raf.writeByte(0);
                }

                Log.i(TAG, "Patched PT_INTERP for " + elfFile.getPath() + " -> " + interpreter);
                return true;
            }

            Log.w(TAG, "No PT_INTERP program header found in " + elfFile.getPath());
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Failed to patch interpreter for " + elfFile.getPath(), e);
            return false;
        }
    }
}
