package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class GameplayMeasurementTest {
    private static final long START = 1_000_000_000L, SECOND = 1_000_000_000L;
    private GameplayMeasurement recording() { GameplayMeasurement m = new GameplayMeasurement(); m.start(START, true); return m; }
    private GameplayMeasurement.Battery battery(long delta, double watts, double pct, double mah) {
        return new GameplayMeasurement.Battery(START + delta, watts, pct, mah, false, false);
    }
    @Test public void countsSourceAndGeneratedOutputSeparately() {
        var m = recording();
        for (int i = 1; i <= 100; i++) {
            long when = START + i * 10_000_000L;
            m.sourceFrame(when); m.outputFrame(when, false); m.outputFrame(when + 5_000_000L, true);
        }
        var result = m.finish(START + SECOND);
        assertEquals(100, result.source.count);
        assertEquals(200, result.output.count);
        assertEquals(100, result.generatedFrames);
        assertEquals(100, result.source.average, .0001);
        assertEquals(200, result.output.average, .0001);
        assertEquals(100, result.source.low1, .0001);
    }
    @Test public void lowIsMeanOfSlowestIntervalsRatherThanMeanOfInstantaneousFps() {
        var m = recording(); long time = START;
        m.sourceFrame(time);
        for (int i = 0; i < 99; i++) m.sourceFrame(time += 10_000_000L);
        m.sourceFrame(time += 100_000_000L);
        var result = m.finish(time);
        assertEquals(10, result.source.low1, .0001);
        assertEquals(10, result.source.minimum, .0001);
        assertEquals(100, result.source.maximum, .0001);
    }
    @Test public void includesLongStallsAndKeepsWholeMeasuredDuration() {
        var m = recording(); m.sourceFrame(START); m.sourceFrame(START + 120 * SECOND);
        var result = m.finish(START + 120 * SECOND);
        assertEquals(1 / 120d, result.source.low1, .000001);
        assertEquals(2 / 120d, result.source.average, .000001);
    }
    @Test public void backgroundDoesNotCreateAFalseStallOrConsumption() {
        var m = recording();
        m.sourceFrame(START); m.sourceFrame(START + SECOND);
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(SECOND, 4, 89, 4490));
        m.setActive(false, START + SECOND);
        m.sourceFrame(START + 10 * SECOND);
        m.battery(battery(10 * SECOND, 10, 80, 4000));
        m.setActive(true, START + 20 * SECOND);
        m.battery(battery(20 * SECOND, 4, 80, 4000)); m.battery(battery(21 * SECOND, 4, 79, 3990));
        m.sourceFrame(START + 20 * SECOND); m.sourceFrame(START + 21 * SECOND);
        var result = m.finish(START + 21 * SECOND);
        assertEquals(2 * SECOND, result.durationNanos);
        assertEquals(4, result.source.count);
        assertEquals(1, result.source.minimum, 0);
        assertEquals(2, result.batteryPercent, 0);
        assertEquals(20, result.batteryMah, 0);
        assertEquals(8 / 3600d, result.wattHours, .000001);
    }
    @Test public void integratesTrapezoidAndReportsPowerCoverage() {
        var m = recording();
        m.battery(battery(0, 2, 50, 2500)); m.battery(battery(2 * SECOND, 6, 49, 2490));
        var result = m.finish(START + 2 * SECOND);
        assertEquals(4, result.averageWatts, 0);
        assertEquals(8 / 3600d, result.wattHours, .000001);
        assertEquals(2, result.powerSeconds, 0);
        assertEquals(1, result.batteryPercent, 0);
        assertEquals(10, result.batteryMah, 0);
    }
    @Test public void staleSensorDoesNotIntegrateAcrossLongGapOrClaimCompleteBatteryConsumption() {
        var m = recording();
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(SECOND, 4, 89, 4490));
        var result = m.finish(START + 100 * SECOND);
        assertEquals(1, result.powerSeconds, 0);
        assertTrue(Double.isNaN(result.batteryPercent));
        assertTrue(Double.isNaN(result.batteryMah));
    }
    @Test public void gapsAndUnavailableValuesDoNotBecomeZeroPower() {
        var m = recording();
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(SECOND, Double.NaN, 89, Double.NaN));
        m.battery(battery(10 * SECOND, 8, 88, 4490));
        var result = m.finish(START + 10 * SECOND);
        assertTrue(Double.isNaN(result.averageWatts));
        assertTrue(Double.isNaN(result.wattHours));
    }
    @Test public void chargingInvalidatesDischargeConsumption() {
        var m = recording(); m.battery(battery(0, 4, 90, 4500));
        m.battery(new GameplayMeasurement.Battery(START + SECOND, 4, 91, 4510, true, false));
        var result = m.finish(START + SECOND);
        assertTrue(result.chargingObserved);
        assertTrue(Double.isNaN(result.batteryMah));
        assertTrue(Double.isNaN(result.batteryPercent));
        assertTrue(Double.isNaN(result.averageWatts));
    }
    @Test public void batteryDoesNotSumFuelGaugePercentageBounces() {
        var m = recording();
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(SECOND, 4, 89, 4490));
        m.battery(battery(2 * SECOND, 4, 90, 4500)); m.battery(battery(3 * SECOND, 4, 89, 4490));
        var result = m.finish(START + 3 * SECOND);
        assertEquals(1, result.batteryPercent, 0);
        assertEquals(10, result.batteryMah, 0);
    }
    @Test public void oneSampleIsInsufficientAndMixedChargeSourcesAreNotSubtracted() {
        var m = recording(); m.battery(battery(0, 4, 90, 4500));
        assertTrue(Double.isNaN(m.finish(START + SECOND).batteryMah));
        m = recording(); m.battery(battery(0, 4, 90, 4500));
        m.battery(new GameplayMeasurement.Battery(START + SECOND, 4, 89, 4400, false, true));
        assertTrue(Double.isNaN(m.finish(START + SECOND).batteryMah));
    }
    @Test public void ignoresDuplicateOrOutOfOrderSamplesAndFrames() {
        var m = recording();
        m.sourceFrame(START + SECOND); m.sourceFrame(START + SECOND); m.sourceFrame(START);
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(0, 100, 80, 4000));
        m.battery(battery(SECOND, 4, 89, 4490));
        var result = m.finish(START + SECOND);
        assertEquals(1, result.source.count);
        assertEquals(1, result.batteryPercent, 0);
        assertTrue(Double.isNaN(result.source.low1));
    }
    @Test public void finishIsIdempotentAndStartingAgainResetsState() {
        var m = recording(); m.sourceFrame(START + SECOND);
        var a = m.finish(START + SECOND); var b = m.finish(START + 5 * SECOND);
        assertEquals(a.durationNanos, b.durationNanos);
        m.sourceFrame(START + 6 * SECOND);
        m.start(START + 10 * SECOND, false); m.sourceFrame(START + 11 * SECOND);
        m.setActive(true, START + 20 * SECOND); m.sourceFrame(START + 21 * SECOND);
        var result = m.finish(START + 22 * SECOND);
        assertEquals(1, result.source.count);
        assertEquals(2 * SECOND, result.durationNanos);
    }
    @Test public void aNewSegmentNeedsItsOwnTwoBatteryEndpoints() {
        var m = recording();
        m.battery(battery(0, 4, 90, 4500)); m.battery(battery(SECOND, 4, 89, 4490));
        m.setActive(false, START + SECOND);
        m.setActive(true, START + 10 * SECOND);
        m.battery(battery(10 * SECOND, 4, 80, 4000));
        var result = m.finish(START + 11 * SECOND);
        assertTrue(Double.isNaN(result.batteryMah));
        assertTrue(Double.isNaN(result.batteryPercent));
    }
}
