package com.winlator.cmod.core;

import java.util.Map;
import java.util.TreeMap;

/** Thread-safe session measurement, using only the caller's monotonic nanosecond clock. */
public final class GameplayMeasurement {
    public static final class Battery {
        public final long nanos;
        public final double watts, percent, availableMah;
        public final boolean charging, estimated;
        public Battery(long nanos, double watts, double percent, double availableMah, boolean charging, boolean estimated) {
            this.nanos = nanos; this.watts = watts; this.percent = percent; this.availableMah = availableMah;
            this.charging = charging; this.estimated = estimated;
        }
    }
    public static final class Frames {
        public final long count, intervals;
        public final double average, low1, minimum, maximum;
        Frames(Series series, long duration) {
            count = series.count; intervals = series.intervals;
            average = duration > 0 ? count * 1_000_000_000d / duration : Double.NaN;
            low1 = series.low1();
            minimum = series.max > 0 ? 1_000_000_000d / series.max : Double.NaN;
            maximum = series.min < Long.MAX_VALUE ? 1_000_000_000d / series.min : Double.NaN;
        }
    }
    public static final class Result {
        public final long durationNanos, generatedFrames;
        public final Frames source, output;
        public final double averageWatts, wattHours, powerSeconds, batteryPercent, batteryMah;
        public final boolean estimatedMah, chargingObserved;
        Result(GameplayMeasurement measurement, long duration) {
            durationNanos = duration; generatedFrames = measurement.generated;
            source = new Frames(measurement.source, duration); output = new Frames(measurement.output, duration);
            powerSeconds = measurement.coveredNs / 1_000_000_000d;
            averageWatts = powerSeconds > 0 ? measurement.wattSeconds / powerSeconds : Double.NaN;
            wattHours = powerSeconds > 0 ? measurement.wattSeconds / 3600 : Double.NaN;
            chargingObserved = measurement.chargingObserved;
            batteryPercent = measurement.percentMeasured && measurement.percentCovered && !chargingObserved ? measurement.usedPercent : Double.NaN;
            batteryMah = measurement.mahMeasured && measurement.mahCovered && !measurement.mixedMah && !chargingObserved ? measurement.usedMah : Double.NaN;
            estimatedMah = measurement.estimatedMah;
        }
    }
    private static final class Bucket { long count; double sum; }
    private static final class Series {
        long count, intervals, last, max, min = Long.MAX_VALUE;
        final TreeMap<Long, Bucket> histogram = new TreeMap<>();
        void frame(long now) {
            if (last > 0 && now <= last) return;
            count++;
            if (last > 0) {
                long delta = now - last;
                min = Math.min(min, delta); max = Math.max(max, delta); intervals++;
                // Bounded resolution: 0.1 ms up to 1 s, 1 ms up to 60 s, one
                // long-stall bucket beyond that. Each bucket keeps the actual sum.
                long bucket = delta <= 1_000_000_000L ? (delta + 50_000) / 100_000 * 100_000
                        : delta < 60_000_000_000L ? (delta + 500_000) / 1_000_000 * 1_000_000 : Long.MAX_VALUE;
                Bucket value = histogram.computeIfAbsent(bucket, ignored -> new Bucket());
                value.count++; value.sum += delta;
            }
            last = now;
        }
        double low1() {
            if (intervals == 0) return Double.NaN;
            long wanted = Math.max(1, (long)Math.ceil(intervals * .01)), left = wanted;
            double sum = 0;
            for (Bucket value : histogram.descendingMap().values()) {
                long take = Math.min(left, value.count);
                sum += value.sum / value.count * take;
                if ((left -= take) == 0) break;
            }
            return sum > 0 ? wanted * 1_000_000_000d / sum : Double.NaN;
        }
    }
    private Series source = new Series(), output = new Series();
    private volatile boolean running;
    private boolean active;
    private long activeSince, elapsed, generated, coveredNs;
    private double wattSeconds, usedPercent, usedMah;
    private double firstPct, lastPct, firstMah, lastMah;
    private boolean percentMeasured, mahMeasured, estimatedMah, chargingObserved;
    private boolean percentCovered, mahCovered, mixedMah, firstMahEstimated;
    private int percentSamples, mahSamples;
    private long firstPctTime, lastPctTime, firstMahTime, lastMahTime;
    private Battery previousBattery;
    private long latestBatteryTimestamp;

    public boolean isRunning() { return running; }
    public synchronized boolean isActive() { return running && active; }
    public synchronized void start(long now, boolean foreground) {
        source = new Series(); output = new Series();
        elapsed = generated = coveredNs = 0; wattSeconds = usedPercent = usedMah = 0;
        percentMeasured = mahMeasured = estimatedMah = chargingObserved = false;
        percentCovered = mahCovered = true; mixedMah = false;
        percentSamples = mahSamples = 0;
        latestBatteryTimestamp = 0; previousBattery = null;
        firstPct = lastPct = firstMah = lastMah = Double.NaN;
        activeSince = now; active = foreground; running = true;
    }
    public synchronized long elapsedNanos(long now) {
        return elapsed + (running && active ? Math.max(0, now - activeSince) : 0);
    }
    public synchronized void setActive(boolean foreground, long now) {
        if (!running || active == foreground) return;
        if (active) closeSegment(now);
        active = foreground; activeSince = now;
        source.last = output.last = 0;
        previousBattery = null;
        firstPct = lastPct = firstMah = lastMah = Double.NaN;
        percentSamples = mahSamples = 0;
    }
    public synchronized void sourceFrame(long now) {
        if (running && active && now >= activeSince) source.frame(now);
    }
    public synchronized void outputFrame(long now, boolean generatedFrame) {
        if (!running || !active || now < activeSince) return;
        output.frame(now);
        if (generatedFrame) generated++;
    }
    public synchronized void battery(Battery reading) {
        if (!running || !active || reading == null || reading.nanos <= latestBatteryTimestamp
                || reading.nanos < activeSince - 3_000_000_000L) return;
        latestBatteryTimestamp = reading.nanos;
        long when = Math.max(activeSince, reading.nanos);
        Battery current = new Battery(when, reading.watts, reading.percent, reading.availableMah,
                reading.charging, reading.estimated);
        chargingObserved |= reading.charging;
        if (previousBattery != null) integrate(previousBattery, current);
        previousBattery = current;
        if (validPercent(reading.percent)) {
            if (Double.isNaN(firstPct)) { firstPct = reading.percent; firstPctTime = when; }
            lastPct = reading.percent;
            lastPctTime = when; percentSamples++;
        }
        if (Double.isFinite(reading.availableMah) && reading.availableMah >= 0) {
            if (Double.isNaN(firstMah)) {
                firstMah = reading.availableMah; firstMahTime = when; firstMahEstimated = reading.estimated;
            } else if (reading.estimated != firstMahEstimated) mixedMah = true;
            lastMah = reading.availableMah;
            lastMahTime = when; mahSamples++;
            estimatedMah |= reading.estimated;
        }
    }
    private static boolean validPercent(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }
    private void integrate(Battery a, Battery b) {
        long delta = b.nanos - a.nanos;
        if (delta <= 0 || delta > 5_000_000_000L || a.charging || b.charging
                || !Double.isFinite(a.watts) || !Double.isFinite(b.watts) || a.watts < 0 || b.watts < 0) return;
        wattSeconds += (a.watts + b.watts) * .5 * delta / 1_000_000_000d;
        coveredNs += delta;
    }
    private void closeSegment(long now) {
        elapsed += Math.max(0, now - activeSince);
        // A bounded zero-order hold covers the last fraction of a sampling period,
        // never a background gap or an indefinitely stale sensor.
        if (previousBattery != null && now >= previousBattery.nanos && now - previousBattery.nanos <= 3_000_000_000L)
            integrate(previousBattery, new Battery(now, previousBattery.watts, previousBattery.percent,
                    previousBattery.availableMah, previousBattery.charging, previousBattery.estimated));
        if (percentSamples >= 2 && firstPctTime <= activeSince + 3_000_000_000L && now - lastPctTime <= 3_000_000_000L) {
            usedPercent += Math.max(0, firstPct - lastPct); percentMeasured = true;
        } else if (now > activeSince) percentCovered = false;
        if (mahSamples >= 2 && firstMahTime <= activeSince + 3_000_000_000L && now - lastMahTime <= 3_000_000_000L) {
            usedMah += Math.max(0, firstMah - lastMah); mahMeasured = true;
        } else if (now > activeSince) mahCovered = false;
    }
    public synchronized Result finish(long now) {
        if (running && active) closeSegment(now);
        running = false; active = false;
        return new Result(this, elapsed);
    }
}
