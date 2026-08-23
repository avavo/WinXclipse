package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/**
 * AMD FidelityFX SUPER RESOLUTION [FSR1] ::: EASU Edge-Adaptive Spatial
 * Upscaling, ported from ffx_fsr1.h v1.20210629 (MIT).
 *
 * GLES2 has no gather4, so each of the four gathers is expanded into its
 * four texels (12 unique taps) fetched at exact texel centers. All math
 * runs in a V-down coordinate space to match the reference; fetches flip
 * to GL's V-up at the last moment.
 */
public class FSREasuEffect extends Effect {
    private final float[] con0 = new float[4];
    private final float[] dstRect = new float[4];
    private float outHeight;

    /**
     * FsrEasuCon + FsrEasuConOffset, computed CPU-side.
     *
     * @param inW/inH      scene (input) buffer size in pixels
     * @param srcOffX/Y    input viewport offset inside the scene buffer (V-down)
     * @param srcViewW/H   input viewport size (the rendered region)
     * @param dstOffX/Y    output viewport offset on the display (V-down)
     * @param dstViewW/H   output viewport size on the display
     * @param surfaceH     display height (for gl_FragCoord Y flip)
     */
    public void setMapping(int inW, int inH, float srcOffX, float srcOffY,
                           float srcViewW, float srcViewH,
                           float dstOffX, float dstOffY,
                           float dstViewW, float dstViewH, int surfaceH) {
        float rcpOutX = 1.0f / dstViewW;
        float rcpOutY = 1.0f / dstViewH;
        con0[0] = srcViewW * rcpOutX;
        con0[1] = srcViewH * rcpOutY;
        con0[2] = 0.5f * srcViewW * rcpOutX - 0.5f + srcOffX;
        con0[3] = 0.5f * srcViewH * rcpOutY - 0.5f + srcOffY;
        // inW/inH feed uTexel directly from EffectComposer; con1.zw / con2 /
        // con3 are folded into the direct texel fetches below.
        dstRect[0] = dstOffX;
        dstRect[1] = dstOffY;
        dstRect[2] = dstViewW;
        dstRect[3] = dstViewH;
        outHeight = surfaceH;
    }

    public float[] getCon0() { return con0; }
    public float[] getDstRect() { return dstRect; }
    public float getOutHeight() { return outHeight; }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSREasuMaterial();
    }

    private class FSREasuMaterial extends ScreenMaterial {
        FSREasuMaterial() {
            super();
            setUniformNames(new String[]{"resolution", "screenTexture", "uCon0",
                    "uDstRect", "uOutH", "uTexel"});
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                "precision highp float;",
                "// AMD FidelityFX SUPER RESOLUTION [FSR1] ::: EASU - MIT License",
                "uniform sampler2D screenTexture;",
                "uniform vec4 uCon0;",
                "uniform vec4 uDstRect;",
                "uniform float uOutH;",
                "uniform vec2 uTexel;",
                "",
                "vec3 fetchTap(vec2 corner, vec2 off) {",
                "    vec2 t = corner + off;",
                "    return texture2D(screenTexture, vec2((t.x + 0.5) * uTexel.x, 1.0 - (t.y + 0.5) * uTexel.y)).rgb;",
                "}",
                "",
                "void fsrEasuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {",
                "    vec2 v;",
                "    v.x = (off.x * dir.x) + (off.y * dir.y);",
                "    v.y = (off.x * -dir.y) + (off.y * dir.x);",
                "    v *= len;",
                "    float d2 = v.x * v.x + v.y * v.y;",
                "    d2 = min(d2, clp);",
                "    float wB = 2.0 / 5.0 * d2 + -1.0;",
                "    float wA = lob * d2 + -1.0;",
                "    wB *= wB;",
                "    wA *= wA;",
                "    wB = 25.0 / 16.0 * wB + -(25.0 / 16.0 - 1.0);",
                "    float w = wB * wA;",
                "    aC += c * w;",
                "    aW += w;",
                "}",
                "",
                "void fsrEasuSet(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV, float lA, float lB, float lC, float lD, float lE) {",
                "    float w = 0.0;",
                "    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);",
                "    if (biT) w = pp.x * (1.0 - pp.y);",
                "    if (biU) w = (1.0 - pp.x) * pp.y;",
                "    if (biV) w = pp.x * pp.y;",
                "    float dc = lD - lC;",
                "    float cb = lC - lB;",
                "    float lenX = max(abs(dc), abs(cb));",
                "    lenX = 1.0 / lenX;",
                "    float dirX = lD - lB;",
                "    dir.x += dirX * w;",
                "    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);",
                "    lenX *= lenX;",
                "    len += lenX * w;",
                "    float ec = lE - lC;",
                "    float ca = lC - lA;",
                "    float lenY = max(abs(ec), abs(ca));",
                "    lenY = 1.0 / lenY;",
                "    float dirY = lE - lA;",
                "    dir.y += dirY * w;",
                "    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);",
                "    lenY *= lenY;",
                "    len += lenY * w;",
                "}",
                "",
                "void main() {",
                "    // Integer output pixel position in a V-down space (reference convention).",
                "    vec2 ip = vec2(floor(gl_FragCoord.x - 0.5), uOutH - 0.5 - gl_FragCoord.y);",
                "    vec2 local = ip - uDstRect.xy;",
                "    if (local.x < 0.0 || local.y < 0.0 || local.x >= uDstRect.z || local.y >= uDstRect.w) {",
                "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
                "        return;",
                "    }",
                "    // Get position of 'f' (texel-center space, V-down).",
                "    vec2 pp = local * uCon0.xy + uCon0.zw;",
                "    vec2 fp = floor(pp);",
                "    pp -= fp;",
                "",
                "    // 12-tap kernel, gathers expanded to exact texel fetches.",
                "    //    b c",
                "    //  e f g h",
                "    //  i j k l",
                "    //    n o",
                "    vec2 g0 = fp + vec2(1.0, -1.0);",
                "    vec3 b = fetchTap(g0, vec2(-1.0, 0.0));",
                "    vec3 c = fetchTap(g0, vec2(0.0, 0.0));",
                "    vec2 g1 = fp + vec2(0.0, 1.0);",
                "    vec3 i = fetchTap(g1, vec2(-1.0, 0.0));",
                "    vec3 j = fetchTap(g1, vec2(0.0, 0.0));",
                "    vec3 f = fetchTap(g1, vec2(0.0, -1.0));",
                "    vec3 e = fetchTap(g1, vec2(-1.0, -1.0));",
                "    vec2 g2 = fp + vec2(2.0, 1.0);",
                "    vec3 k = fetchTap(g2, vec2(-1.0, 0.0));",
                "    vec3 l = fetchTap(g2, vec2(0.0, 0.0));",
                "    vec3 h = fetchTap(g2, vec2(0.0, -1.0));",
                "    vec3 g = fetchTap(g2, vec2(-1.0, -1.0));",
                "    vec2 g3 = fp + vec2(1.0, 3.0);",
                "    vec3 o = fetchTap(g3, vec2(0.0, -1.0));",
                "    vec3 n = fetchTap(g3, vec2(-1.0, -1.0));",
                "",
                "    // Simplest multi-channel approximate luma possible (luma times 2).",
                "    float bL = b.b * 0.5 + (b.r * 0.5 + b.g);",
                "    float cL = c.b * 0.5 + (c.r * 0.5 + c.g);",
                "    float iL = i.b * 0.5 + (i.r * 0.5 + i.g);",
                "    float jL = j.b * 0.5 + (j.r * 0.5 + j.g);",
                "    float fL = f.b * 0.5 + (f.r * 0.5 + f.g);",
                "    float eL = e.b * 0.5 + (e.r * 0.5 + e.g);",
                "    float kL = k.b * 0.5 + (k.r * 0.5 + k.g);",
                "    float lL = l.b * 0.5 + (l.r * 0.5 + l.g);",
                "    float hL = h.b * 0.5 + (h.r * 0.5 + h.g);",
                "    float gL = g.b * 0.5 + (g.r * 0.5 + g.g);",
                "    float oL = o.b * 0.5 + (o.r * 0.5 + o.g);",
                "    float nL = n.b * 0.5 + (n.r * 0.5 + n.g);",
                "",
                "    // Accumulate direction and length.",
                "    vec2 dir = vec2(0.0);",
                "    float len = 0.0;",
                "    fsrEasuSet(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);",
                "    fsrEasuSet(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);",
                "    fsrEasuSet(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);",
                "    fsrEasuSet(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);",
                "",
                "    // Normalize with approximation, and cleanup close to zero.",
                "    vec2 dir2 = dir * dir;",
                "    float dirR = dir2.x + dir2.y;",
                "    bool zro = dirR < 1.0 / 32768.0;",
                "    dirR = inversesqrt(dirR);",
                "    dirR = zro ? 1.0 : dirR;",
                "    dir.x = zro ? 1.0 : dir.x;",
                "    dir *= dirR;",
                "    // Transform from {0 to 2} to {0 to 1} range, and shape with square.",
                "    len = len * 0.5;",
                "    len *= len;",
                "    // Stretch kernel {1.0 vert|horz, to sqrt(2.0) on diagonal}.",
                "    float stretch = (dir.x * dir.x + dir.y * dir.y) * (1.0 / max(abs(dir.x), abs(dir.y)));",
                "    // Anisotropic length after rotation.",
                "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);",
                "    // Based on the amount of 'edge', the window shifts from +/-{sqrt(2.0) to slightly beyond 2.0}.",
                "    float lob = 0.5 + (1.0 / 4.0 - 0.04 - 0.5) * len;",
                "    // Set distance^2 clipping point to the end of the adjustable window.",
                "    float clp = 1.0 / lob;",
                "",
                "    // Accumulation mixed with min/max of 4 nearest.",
                "    vec3 min4 = min(min(f, g), min(j, k));",
                "    vec3 max4 = max(max(f, g), max(j, k));",
                "    // Accumulation.",
                "    vec3 aC = vec3(0.0);",
                "    float aW = 0.0;",
                "    fsrEasuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);",
                "    fsrEasuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);",
                "    fsrEasuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);",
                "    fsrEasuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);",
                "    fsrEasuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);",
                "    fsrEasuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);",
                "    fsrEasuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);",
                "    fsrEasuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);",
                "    fsrEasuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);",
                "    fsrEasuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);",
                "    fsrEasuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);",
                "    fsrEasuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);",
                "",
                "    // Normalize and dering.",
                "    vec3 pix = min(max4, max(min4, aC * (1.0 / aW)));",
                "    gl_FragColor = vec4(pix, 1.0);",
                "}"
            });
        }
    }
}
