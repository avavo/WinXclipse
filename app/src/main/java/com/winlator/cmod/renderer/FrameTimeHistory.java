package com.winlator.cmod.renderer;

/** Bounded chronological frametime samples; pauses start a new clock boundary. */
public final class FrameTimeHistory {
    private final double[] samples;
    private int count, next;
    private long last;
    private boolean hasLast;
    public FrameTimeHistory(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity");
        samples = new double[capacity];
    }
    public synchronized void frame(long nanos) {
        if (hasLast) {
            if (nanos <= last) return;
            samples[next] = (nanos - last) / 1_000_000d;
            next = (next + 1) % samples.length;
            count = Math.min(samples.length, count + 1);
        }
        last = nanos; hasLast = true;
    }
    public synchronized double[] snapshot() {
        double[] result = new double[count];
        int first = (next - count + samples.length) % samples.length;
        for (int i = 0; i < count; i++) result[i] = samples[(first + i) % samples.length];
        return result;
    }
    public synchronized double latest(long now) {
        return count > 0 && hasLast && now - last <= 2_000_000_000L
                ? samples[(next - 1 + samples.length) % samples.length] : Double.NaN;
    }
    public synchronized void boundary() { hasLast = false; }
    public synchronized void clear() { count = next = 0; hasLast = false; }
}
