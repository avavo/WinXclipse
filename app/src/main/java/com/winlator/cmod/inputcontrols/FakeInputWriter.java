package com.winlator.cmod.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Writes Linux input_event records consumed by Winlator Mali's libfakeinput.
 * The protocol is deliberately file/path based and therefore independent of
 * the Android application id.
 */
public final class FakeInputWriter {
    private static final String TAG = "FakeInputWriter";
    private static final int EVENT_SIZE = 24;
    private static final int BUFFER_SIZE = EVENT_SIZE * 32;

    private static final short EV_SYN = 0;
    private static final short EV_KEY = 1;
    private static final short EV_ABS = 3;
    private static final short EV_MSC = 4;
    private static final short SYN_REPORT = 0;
    private static final short MSC_SCAN = 4;

    private static final short ABS_X = 0;
    private static final short ABS_Y = 1;
    private static final short ABS_RX = 3;
    private static final short ABS_RY = 4;
    private static final short ABS_GAS = 9;
    private static final short ABS_BRAKE = 10;
    private static final short ABS_HAT0X = 16;
    private static final short ABS_HAT0Y = 17;

    private static final short[] BUTTON_MAP = {
            304, // BTN_A
            305, // BTN_B
            307, // BTN_X
            308, // BTN_Y
            310, // BTN_TL
            311, // BTN_TR
            314, // BTN_SELECT
            315, // BTN_START
            317, // BTN_THUMBL
            318  // BTN_THUMBR
    };

    private final File eventFile;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final boolean[] previousButtons = new boolean[BUTTON_MAP.length];

    private RandomAccessFile randomAccessFile;
    private FileChannel channel;
    private boolean open;
    private boolean destroyed;
    private boolean changed;
    private int previousLX;
    private int previousLY;
    private int previousRX;
    private int previousRY;
    private int previousLT;
    private int previousRT;
    private int previousHatX;
    private int previousHatY;

    public FakeInputWriter(File directory, int slot) {
        eventFile = new File(directory, "event" + slot);
    }

    public synchronized boolean open() {
        if (destroyed) return false;
        if (open) return true;
        try {
            File parent = eventFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            if (!eventFile.exists() && !eventFile.createNewFile()) return false;
            randomAccessFile = new RandomAccessFile(eventFile, "rw");
            randomAccessFile.seek(randomAccessFile.length());
            channel = randomAccessFile.getChannel();
            open = true;
            Log.i(TAG, "Opened " + eventFile.getAbsolutePath());
            return true;
        }
        catch (IOException e) {
            Log.e(TAG, "Unable to open " + eventFile, e);
            close();
            return false;
        }
    }

    public synchronized void writeGamepadState(GamepadState state) {
        if (state == null || (!open && !open())) return;
        buffer.clear();
        changed = false;

        for (int i = 0; i < BUTTON_MAP.length; i++) {
            boolean pressed = state.isPressed(i);
            if (pressed != previousButtons[i]) {
                previousButtons[i] = pressed;
                writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
                writeEvent(EV_KEY, BUTTON_MAP[i], pressed ? 1 : 0);
            }
        }

        previousLX = writeAxis(ABS_X, toSignedAxis(state.thumbLX), previousLX);
        previousLY = writeAxis(ABS_Y, toSignedAxis(state.thumbLY), previousLY);
        previousRX = writeAxis(ABS_RX, toSignedAxis(state.thumbRX), previousRX);
        previousRY = writeAxis(ABS_RY, toSignedAxis(state.thumbRY), previousRY);
        previousLT = writeAxis(ABS_BRAKE, toTrigger(state.triggerL), previousLT);
        previousRT = writeAxis(ABS_GAS, toTrigger(state.triggerR), previousRT);
        previousHatX = writeAxis(ABS_HAT0X, state.getDPadX(), previousHatX);
        previousHatY = writeAxis(ABS_HAT0Y, state.getDPadY(), previousHatY);

        if (!changed) return;
        writeEvent(EV_SYN, SYN_REPORT, 0);
        flushBuffer();
    }

    private void flushBuffer() {
        buffer.flip();
        try {
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        catch (IOException e) {
            Log.e(TAG, "Input write failed for " + eventFile, e);
            close();
        }
    }

    public synchronized void reset() {
        if (!open && !open()) return;
        // Send a complete neutral snapshot instead of relying on the local
        // delta cache. If Wine/libfakeinput missed one release event, the
        // writer can already believe it is neutral while the game is not.
        buffer.clear();
        changed = false;
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            previousButtons[i] = false;
            writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
            writeEvent(EV_KEY, BUTTON_MAP[i], 0);
        }
        previousLX = previousLY = previousRX = previousRY = 0;
        previousLT = previousRT = 0;
        previousHatX = previousHatY = 0;
        writeEvent(EV_ABS, ABS_X, 0);
        writeEvent(EV_ABS, ABS_Y, 0);
        writeEvent(EV_ABS, ABS_RX, 0);
        writeEvent(EV_ABS, ABS_RY, 0);
        writeEvent(EV_ABS, ABS_BRAKE, 0);
        writeEvent(EV_ABS, ABS_GAS, 0);
        writeEvent(EV_ABS, ABS_HAT0X, 0);
        writeEvent(EV_ABS, ABS_HAT0Y, 0);
        writeEvent(EV_SYN, SYN_REPORT, 0);
        flushBuffer();
    }

    public synchronized void destroy() {
        if (destroyed) return;
        reset();
        destroyed = true;
        close();
        if (eventFile.exists() && !eventFile.delete()) {
            Log.w(TAG, "Could not delete " + eventFile);
        }
    }

    public synchronized void close() {
        try {
            if (channel != null) channel.close();
        }
        catch (IOException ignored) {}
        try {
            if (randomAccessFile != null) randomAccessFile.close();
        }
        catch (IOException ignored) {}
        channel = null;
        randomAccessFile = null;
        open = false;
    }

    private int writeAxis(short code, int value, int previous) {
        if (value != previous) writeEvent(EV_ABS, code, value);
        return value;
    }

    private void writeEvent(short type, short code, int value) {
        long millis = System.currentTimeMillis();
        buffer.putLong(millis / 1000L);
        buffer.putLong((millis % 1000L) * 1000L);
        buffer.putShort(type);
        buffer.putShort(code);
        buffer.putInt(value);
        changed = true;
    }

    private static int toSignedAxis(float value) {
        return Math.round(Math.max(-1.0f, Math.min(1.0f, value)) * 32767.0f);
    }

    private static int toTrigger(float value) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }
}
