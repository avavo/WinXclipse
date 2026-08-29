package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;
import android.util.SparseLongArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private final SparseLongArray nextFrameTimesNs = new SparseLongArray();
    private volatile int frameRateLimit;
    private volatile int refreshRateHz = 60;
    private volatile String vsyncMode = "off";

    public void setFrameRateLimit(int limit) {
        int normalized = Math.max(0, limit);
        if (frameRateLimit != normalized) {
            frameRateLimit = normalized;
            synchronized (nextFrameTimesNs) { nextFrameTimesNs.clear(); }
        }
    }

    public void setRefreshRate(float refreshRate) {
        int normalized = Math.max(1, Math.round(refreshRate));
        if (refreshRateHz != normalized) {
            refreshRateHz = normalized;
            synchronized (nextFrameTimesNs) { nextFrameTimesNs.clear(); }
        }
    }

    public void setVsyncMode(String mode) {
        String normalized = "50".equals(mode) ? "50" : "100".equals(mode) ? "100" : "off";
        if (!normalized.equals(vsyncMode)) {
            vsyncMode = normalized;
            synchronized (nextFrameTimesNs) { nextFrameTimesNs.clear(); }
        }
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        long ust = System.nanoTime() / 1000;
        long msc = ust / Math.max(1, 1000000 / refreshRateHz);

        synchronized (content.renderLock) {
            content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
        }
        // Socket/event delivery may block briefly. Keep it outside renderLock so
        // the GL thread can upload the completed frame without waiting on Wine.
        sendIdleNotify(window, pixmap, serial, idleFence);
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        // Keep the window content on the upload-backed Texture path. The old
        // optimization kept one AHardwareBuffer CPU-mapped while GLES sampled
        // it, so the next DRI3 Present could overwrite scanlines still in use
        // by the Xclipse GPU. Fast camera pans then showed a horizontal tear
        // even when the guest swapchain selected FIFO/mailbox.

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    /** Paces guest Present requests without sleeping Android's GL thread. */
    private void enforceFrameRate(XClient client) {
        int vsyncLimit = "100".equals(vsyncMode) ? refreshRateHz
                : "50".equals(vsyncMode) ? Math.max(1, refreshRateHz / 2) : 0;
        int targetFps = frameRateLimit > 0 && vsyncLimit > 0
                ? Math.min(frameRateLimit, vsyncLimit)
                : frameRateLimit > 0 ? frameRateLimit : vsyncLimit;
        if (targetFps <= 0) {
            synchronized (nextFrameTimesNs) { nextFrameTimesNs.delete(System.identityHashCode(client)); }
            return;
        }

        long frameNs = 1_000_000_000L / targetFps;
        long now = System.nanoTime();
        int pacingKey = System.identityHashCode(client);
        long nextFrameTimeNs;
        synchronized (nextFrameTimesNs) {
            nextFrameTimeNs = nextFrameTimesNs.get(pacingKey, 0L);
        }
        if (nextFrameTimeNs == 0 || now > nextFrameTimeNs + frameNs)
            nextFrameTimeNs = now;

        long remaining = nextFrameTimeNs - now;
        if (remaining > 1_500_000L) {
            long sleepNs = remaining - 700_000L;
            try {
                Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        remaining = nextFrameTimeNs - System.nanoTime();
        if (remaining > 0 && !Thread.currentThread().isInterrupted())
            LockSupport.parkNanos(remaining);
        synchronized (nextFrameTimesNs) {
            nextFrameTimesNs.put(pacingKey, nextFrameTimeNs + frameNs);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                enforceFrameRate(client);
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
