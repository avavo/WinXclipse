package com.winlator.cmod.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class VKD3DVersionItemTest {
    @Test public void installedBuildsHaveDistinctVisibleLabelsAndStableIdentifiers() {
        VKD3DVersionItem first = new VKD3DVersionItem("2.14", 33);
        VKD3DVersionItem second = new VKD3DVersionItem("2.14", 34);
        assertNotEquals(first.toString(), second.toString());
        List<VKD3DVersionItem> rows = new ArrayList<>(Arrays.asList(new VKD3DVersionItem("none", "None"), first, second));
        assertEquals(1, VKD3DVersionItem.retainSelection(rows, "2.14-33"));
        assertEquals("2.14-33", rows.get(1).getIdentifier());
        assertEquals(3, rows.size());
    }

    @Test public void removedAndLegacySelectionsSurviveReopeningWithoutEnablingAnotherVkd3d() {
        List<VKD3DVersionItem> rows = new ArrayList<>(Arrays.asList(new VKD3DVersionItem("none", "None"), new VKD3DVersionItem("2.14", 34)));
        int saved = VKD3DVersionItem.retainSelection(rows, "2.13-21");
        assertEquals("2.13-21", rows.get(saved).getIdentifier());
        assertEquals(saved, VKD3DVersionItem.retainSelection(rows, "2.13-21"));
        assertEquals(0, VKD3DVersionItem.retainSelection(rows, "none"));
        assertEquals(3, rows.size());
    }
}
