package com.winlator.cmod.core;
import org.junit.Test;
import static org.junit.Assert.*;
import com.winlator.cmod.fexcore.FEXCoreManager;
public class FexBundledSelectionTest {
 @Test public void canonicalizesOnlyTheBundledBuild() {
  assertEquals("2610", FEXCoreManager.canonicalBundledVersion("2610-2246"));
  assertEquals("2610", FEXCoreManager.canonicalBundledVersion("FEXCore-2610-2246"));
  assertEquals("2610-9999", FEXCoreManager.canonicalBundledVersion("2610-9999"));
  assertEquals("2608", FEXCoreManager.canonicalBundledVersion("2608"));
 }
}
