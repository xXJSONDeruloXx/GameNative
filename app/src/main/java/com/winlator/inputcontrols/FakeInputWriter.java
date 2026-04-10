package com.winlator.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Writes raw Linux input_event structs to a backing file that libfakeinput.so
 * (LD_PRELOAD hook) serves to Wine as real evdev gamepad events.
 *
 * Each input_event is 24 bytes on 64-bit (timeval + type + code + value).
 * We batch up to 20 events (480 bytes) before flushing.
 *
 * Ported from Winlator-Ludashi decompiled FakeInputWriter.
 */
public class FakeInputWriter {
    private static final String TAG = "FakeInputWriter";

    // Linux input event constants
    public static final short EV_SYN = 0;
    public static final short EV_KEY = 1;
    public static final short EV_ABS = 3;
    public static final short EV_MSC = 4;

    public static final short SYN_REPORT = 0;
    public static final short MSC_SCAN = 4;

    public static final short ABS_X = 0;
    public static final short ABS_Y = 1;
    public static final short ABS_RX = 3;
    public static final short ABS_RY = 4;
    public static final short ABS_GAS = 9;
    public static final short ABS_BRAKE = 10;
    public static final short ABS_HAT0X = 16;
    public static final short ABS_HAT0Y = 17;

    public static final short BTN_A = 304;
    public static final short BTN_B = 305;
    public static final short BTN_X = 307;
    public static final short BTN_Y = 308;
    public static final short BTN_TL = 310;
    public static final short BTN_TR = 311;
    public static final short BTN_SELECT = 314;
    public static final short BTN_START = 315;
    public static final short BTN_THUMBL = 317;
    public static final short BTN_THUMBR = 318;

    private static final short[] BUTTON_MAP = {
        BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
        BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
    };

    private static final int EVENT_SIZE = 24;
    private static final int MAX_EVENTS_PER_UPDATE = 20;
    private static final int BUFFER_SIZE = EVENT_SIZE * MAX_EVENTS_PER_UPDATE;

    private final File eventFile;
    private RandomAccessFile raf;
    private FileChannel channel;
    private boolean isOpen = false;
    private volatile boolean destroyed = false;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private boolean hasChanges = false;

    // Previous state for dirty-checking
    private final boolean[] prevButtonStates = new boolean[BUTTON_MAP.length];
    private int prevThumbLX = 0;
    private int prevThumbLY = 0;
    private int prevThumbRX = 0;
    private int prevThumbRY = 0;
    private int prevTriggerL = 0;
    private int prevTriggerR = 0;
    private int prevHatX = 0;
    private int prevHatY = 0;

    public FakeInputWriter(String basePath, int slot) {
        this.eventFile = new File(basePath, "event" + slot);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public synchronized boolean open() {
        if (destroyed) return false;
        if (isOpen) return true;
        try {
            eventFile.getParentFile().mkdirs();
            if (!eventFile.exists()) {
                eventFile.createNewFile();
            }
            raf = new RandomAccessFile(eventFile, "rw");
            raf.seek(raf.length());
            channel = raf.getChannel();
            isOpen = true;
            Log.i(TAG, "Opened fake input: " + eventFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open: " + e.getMessage());
            return false;
        }
    }

    public synchronized void close() {
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
            channel = null;
        }
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            raf = null;
        }
        isOpen = false;
    }

    public synchronized void reset() {
        if (!isOpen && !open()) return;

        buffer.clear();
        hasChanges = false;

        // Release all pressed buttons
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if (prevButtonStates[i]) {
                prevButtonStates[i] = false;
                writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
                writeEvent(EV_KEY, BUTTON_MAP[i], 0);
            }
        }

        // Center all axes
        if (prevThumbLX != 0) { prevThumbLX = 0; writeEvent(EV_ABS, ABS_X, 0); }
        if (prevThumbLY != 0) { prevThumbLY = 0; writeEvent(EV_ABS, ABS_Y, 0); }
        if (prevThumbRX != 0) { prevThumbRX = 0; writeEvent(EV_ABS, ABS_RX, 0); }
        if (prevThumbRY != 0) { prevThumbRY = 0; writeEvent(EV_ABS, ABS_RY, 0); }
        if (prevTriggerL != 0) { prevTriggerL = 0; writeEvent(EV_ABS, ABS_BRAKE, 0); }
        if (prevTriggerR != 0) { prevTriggerR = 0; writeEvent(EV_ABS, ABS_GAS, 0); }
        if (prevHatX != 0) { prevHatX = 0; writeEvent(EV_ABS, ABS_HAT0X, 0); }
        if (prevHatY != 0) { prevHatY = 0; writeEvent(EV_ABS, ABS_HAT0Y, 0); }

        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            try {
                channel.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Reset write error: " + e.getMessage());
            }
        }
        Log.i(TAG, "Reset fake input: " + eventFile.getAbsolutePath());
    }

    public synchronized void softRelease() {
        reset();
        close();
        Log.i(TAG, "Soft released fake input: " + eventFile.getAbsolutePath());
    }

    public synchronized void destroy() {
        destroyed = true;
        reset();
        close();
        if (eventFile != null && eventFile.exists()) {
            boolean deleted = eventFile.delete();
            Log.i(TAG, "Deleted fake input: " + eventFile.getAbsolutePath() + " (" + deleted + ")");
        }
    }

    public void writeGamepadState(GamepadState state) {
        if (!isOpen && !open()) return;

        buffer.clear();
        hasChanges = false;

        // Buttons
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            boolean pressed = state.isPressed((byte) i);
            if (prevButtonStates[i] != pressed) {
                prevButtonStates[i] = pressed;
                writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
                writeEvent(EV_KEY, BUTTON_MAP[i], pressed ? 1 : 0);
            }
        }

        // Thumbsticks (float -1..1 → int -32767..32767)
        int lx = (int) (state.thumbLX * 32767.0f);
        int ly = (int) (state.thumbLY * 32767.0f);
        int rx = (int) (state.thumbRX * 32767.0f);
        int ry = (int) (state.thumbRY * 32767.0f);

        if (lx != prevThumbLX) { prevThumbLX = lx; writeEvent(EV_ABS, ABS_X, lx); }
        if (ly != prevThumbLY) { prevThumbLY = ly; writeEvent(EV_ABS, ABS_Y, ly); }
        if (rx != prevThumbRX) { prevThumbRX = rx; writeEvent(EV_ABS, ABS_RX, rx); }
        if (ry != prevThumbRY) { prevThumbRY = ry; writeEvent(EV_ABS, ABS_RY, ry); }

        // Triggers (float 0..1 → int 0..255)
        int tl = (int) (state.triggerL * 255.0f);
        int tr = (int) (state.triggerR * 255.0f);

        if (tl != prevTriggerL) { prevTriggerL = tl; writeEvent(EV_ABS, ABS_BRAKE, tl); }
        if (tr != prevTriggerR) { prevTriggerR = tr; writeEvent(EV_ABS, ABS_GAS, tr); }

        // D-pad as hat switch (-1, 0, 1)
        int hatX = state.dpad[3] ? -1 : (state.dpad[1] ? 1 : 0);
        int hatY = state.dpad[0] ? -1 : (state.dpad[2] ? 1 : 0);

        if (hatX != prevHatX) { prevHatX = hatX; writeEvent(EV_ABS, ABS_HAT0X, hatX); }
        if (hatY != prevHatY) { prevHatY = hatY; writeEvent(EV_ABS, ABS_HAT0Y, hatY); }

        // Flush if anything changed
        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            try {
                channel.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        }
    }

    public boolean isOpen() {
        return isOpen;
    }

    private void writeEvent(short type, short code, int value) {
        long timeMs = System.currentTimeMillis();
        buffer.putLong(timeMs / 1000);       // tv_sec
        buffer.putLong((timeMs % 1000) * 1000); // tv_usec
        buffer.putShort(type);
        buffer.putShort(code);
        buffer.putInt(value);
        hasChanges = true;
    }
}
