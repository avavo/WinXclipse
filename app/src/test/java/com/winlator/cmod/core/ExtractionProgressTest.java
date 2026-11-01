package com.winlator.cmod.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExtractionProgressTest {
    @Test public void compressedReadsAdvanceBeforeFirstHugeTarEntryAndReserveValidation() {
        assertEquals(1, ExtractionProgress.percent(256*1024, 92_951_687, 0, 78));
        assertEquals(39, ExtractionProgress.percent(50_000_000, 100_000_000, 0, 78));
        assertEquals(77, ExtractionProgress.percent(100_000_000, 100_000_000, 0, 78));
        assertEquals(92, ExtractionProgress.percent(100, 100, 78, 93));
        assertEquals(78, ExtractionProgress.percent(0, -1, 78, 93));
    }
}
