package com.winlator.cmod.renderer;

import com.winlator.cmod.renderer.material.ShaderMaterial;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class GLResourceLifecycleTest {
    private static final class Material extends ShaderMaterial {
        int builds, binds, queries, deleted;
        Material() { setUniformNames("resolution", "optimizedOut"); }
        @Override protected int buildProgram() { return ++builds; }
        @Override protected void bindProgram(int program) { binds++; }
        @Override protected int locateUniform(int program, String name) {
            queries++; return "optimizedOut".equals(name) ? -1 : program * 100;
        }
        @Override protected void deleteProgram(int program) { deleted++; }
    }
    private static final class SeededTexture extends Texture {
        void seed(int id) { ensureContext(); textureId = id; needsUpdate = false; }
    }

    @Test public void recreatedContextRebuildsProgramAndResolvesNewUniformLocationsWithoutDeletingOldNames() {
        GLContextEpoch.beginContext(); Material material = new Material();
        material.use(); assertEquals(100, material.getUniformLocation("resolution"));
        GLContextEpoch.beginContext(); material.use();
        assertEquals(2, material.builds); assertEquals(200, material.getUniformLocation("resolution"));
        assertEquals(4, material.queries); assertEquals(0, material.deleted);
    }

    @Test public void absentUniformsAreCachedAndNotQueriedForEveryFrame() {
        GLContextEpoch.beginContext(); Material material = new Material();
        material.use(); material.use(); material.use();
        assertEquals(-1, material.getUniformLocation("optimizedOut"));
        assertEquals(1, material.builds); assertEquals(3, material.binds); assertEquals(2, material.queries);
    }

    @Test public void normalDestructionDeletesOnceAndInvalidatesUniformsForRecompilation() {
        GLContextEpoch.beginContext(); Material material = new Material();
        material.use(); material.destroy(); material.destroy();
        assertEquals(1, material.deleted); assertEquals(0, material.programId);
        material.use(); assertEquals(200, material.getUniformLocation("resolution")); assertEquals(4, material.queries);
    }

    @Test public void destructionAfterContextLossNeverDeletesARecycledNameInNewContext() {
        GLContextEpoch.beginContext(); Material material = new Material(); material.use();
        GLContextEpoch.beginContext(); material.destroy(); assertEquals(0, material.deleted);
        material.use(); assertEquals(2, material.builds);
    }

    @Test public void texturesReuploadEvenWithoutANewGameFrameAfterContextLoss() {
        GLContextEpoch.beginContext(); SeededTexture texture = new SeededTexture(); texture.seed(77);
        assertTrue(texture.isAllocated()); assertFalse(texture.isNeedsUpdate());
        GLContextEpoch.beginContext(); assertFalse(texture.isAllocated());
        assertEquals(0, texture.getTextureId()); assertTrue(texture.isNeedsUpdate());
        texture.seed(88); assertEquals(88, texture.getTextureId());
    }

    @Test public void vertexInvalidationKeepsTheCpuQuadForTheNewContext() {
        VertexAttribute vertices = new VertexAttribute("position", 2); vertices.put(new float[]{0, 0, 0, 1, 1, 0, 1, 1});
        assertEquals(4, vertices.count()); vertices.invalidateContext();
        assertEquals(4, vertices.count()); assertEquals(-1, vertices.getLocation());
    }

    @Test public void contextsOnAnotherRenderThreadDoNotInvalidateThisThreadsResources() throws Exception {
        GLContextEpoch.beginContext(); long current = GLContextEpoch.current();
        long other = CompletableFuture.supplyAsync(() -> { GLContextEpoch.beginContext(); return GLContextEpoch.current(); }).get();
        assertNotEquals(current, other); assertEquals(current, GLContextEpoch.current());
    }
}
