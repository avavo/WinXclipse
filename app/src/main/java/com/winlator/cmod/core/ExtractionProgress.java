package com.winlator.cmod.core;

/** Progress of compressed input, independent of tar padding and sparse files. */
public final class ExtractionProgress {
    private ExtractionProgress() {}
    public static int percent(long read, long total, int start, int end) {
        if (read <= 0 || total <= 0) return start;
        double fraction = Math.min(1d, read / (double)total);
        // Completion is reported by the installer only after CRC and file writes.
        return start + Math.min(Math.max(0, end-start-1), Math.max(1, (int)(fraction*(end-start))));
    }
}
