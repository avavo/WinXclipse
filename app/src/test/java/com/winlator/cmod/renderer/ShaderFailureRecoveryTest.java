package com.winlator.cmod.renderer;

import com.winlator.cmod.renderer.material.ShaderMaterial;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderFailureRecoveryTest {
    private static final class Material extends ShaderMaterial {
        int builds, binds;
        boolean fail = true, zero;
        final RuntimeException rejected = new IllegalStateException("unsupported shader");
        @Override protected int buildProgram() { builds++; if (fail) throw rejected; return zero ? 0 : 7; }
        @Override protected void bindProgram(int program) { binds++; }
        @Override protected void deleteProgram(int program) {}
    }

    @Test public void failedShaderDoesNotRecompileForEveryPresent() {
        Material material = new Material();
        for (int i = 0; i < 300; i++) assertSame(material.rejected, assertThrows(RuntimeException.class, material::use));
        assertEquals(1, material.builds);
        assertEquals(0, material.binds);
        material.fail = false;
        GLContextEpoch.beginContext();
        material.use(); material.use();
        assertEquals(2, material.builds);
        assertEquals(2, material.binds);
    }

    @Test public void explicitDestructionAllowsARetryAndZeroIsNeverBoundAsAProgram() {
        Material material = new Material();
        assertThrows(RuntimeException.class, material::use);
        material.destroy(); material.fail = false; material.zero = true;
        assertThrows(RuntimeException.class, material::use);
        assertThrows(RuntimeException.class, material::use);
        assertEquals(2, material.builds);
        assertEquals(0, material.binds);
        material.retryFailedBuild(); material.zero = false; material.use();
        assertEquals(3, material.builds);
        assertEquals(1, material.binds);
        material.retryFailedBuild(); material.use();
        assertEquals(3, material.builds);
    }
}
