package dev.foldphase.renderer

/**
 * The AGSL source for the fold transition (brief §13).
 *
 * Held as a Kotlin constant rather than a raw resource so that compiling the shader
 * involves no file I/O at all — brief §22 forbids file reads on the animation path, and
 * the simplest way to honour that is to have nothing to read.
 *
 * `@TAPS@` is substituted at compile time by [FoldShaderProgram] so each quality level
 * gets a genuinely smaller kernel. AGSL does not permit a uniform as a loop bound, and
 * branching inside the loop would not save fill rate anyway, so specialising the source
 * is the only approach that actually costs less.
 *
 * ## What this shader does
 *
 * Both physical displays run this same shader with the same uniforms. Per fragment:
 *
 * 1. Map the output pixel into **scene space** through the current viewport — the portal
 *    model from `docs/RESEARCH.md` §4.1. This is why the panel swap is invisible: the
 *    scene coordinate for a given screen position is a function of progress alone, so the
 *    frame the inner display draws at the swap is the frame the cover display would have
 *    drawn.
 * 2. Compute a signed distance from the hinge line and derive a per-pixel blur radius
 *    from it, so blur is strongest at the seam and falls away toward the outer edge
 *    (research §1.2 A — the most specific detail published about Apple's version).
 * 3. Sample both scene representations with a variable-radius golden-angle spiral blur.
 * 4. Cross-dissolve them with a **hinge-directed gradient**, so content resolves outward
 *    from the fold rather than fading uniformly.
 * 5. Apply depth cues: hinge-centred vignette, fold shadow, specular edge, dimming,
 *    desaturation, and finally the (small) black veil.
 *
 * ## Portability notes
 *
 * The blur is written out twice, once per texture, rather than factored into a function
 * taking a `shader` parameter. Passing child shaders as function arguments is not
 * reliably supported across the Skia versions this app can land on; calling `.eval()` on
 * the uniform directly always is. The duplication is deliberate.
 */
internal object FoldShaderSource {

    /** Replaced with the tap count before compilation. */
    const val TAPS_PLACEHOLDER = "@TAPS@"

    val SOURCE: String = """
        uniform shader coverTex;
        uniform shader innerTex;

        // Output surface size in pixels.
        uniform float2 resolution;
        // Texture dimensions in pixels, for scene -> texel mapping.
        uniform float2 coverTexSize;
        uniform float2 innerTexSize;

        // Current viewport onto the scene: (left, top, right, bottom) in scene space.
        uniform float4 viewport;
        // Where each texture's content lives in scene space.
        uniform float4 coverSceneRect;
        uniform float4 innerSceneRect;

        // Hinge geometry.
        uniform float hingePos;    // scene coordinate of the fold line along the split axis
        uniform float hingeIsY;    // 0 = fold splits left/right, 1 = splits top/bottom
        uniform float coverSign;   // +1 or -1: which way from the hinge the cover half lies
        uniform float hingeFalloff;

        // Blur radii in pixels: .x at the hinge edge, .y at the outer edge.
        uniform float2 coverBlur;
        uniform float2 innerBlur;

        // Layer transforms.
        uniform float2 layerScale;   // (cover, inner)
        uniform float2 brightness;   // (cover, inner)
        uniform float2 saturation;   // (cover, inner)
        uniform float2 layerAlpha;   // (cover, inner)

        // Dissolve.
        uniform float crossDissolve;
        uniform float dissolveSoftness;

        // Depth / concealment.
        uniform float vignette;
        uniform float hingeShadow;
        uniform float edgeIllum;
        uniform float blackAlpha;
        uniform float perspective;
        uniform float hasInner;

        const float GOLDEN_ANGLE = 2.399963229728653;
        const int TAP_COUNT = @TAPS@;

        // Rec. 709 luma, so desaturation preserves perceived brightness.
        float luma(half3 c) {
            return dot(float3(c), float3(0.2126, 0.7152, 0.0722));
        }

        half3 applyGrade(half3 c, float bright, float sat) {
            float l = luma(c);
            half3 desat = half3(half(l));
            half3 graded = mix(desat, c, half(sat));
            return graded * half(bright);
        }

        // Scene coordinate -> normalised position within a texture's scene rect.
        float2 sceneToTexUv(float2 scene, float4 rect) {
            // A 1e-5 floor is far below any real rect size and avoids a divide by zero
            // if a degenerate rect is ever supplied.
            float2 size = max(float2(rect.z - rect.x, rect.w - rect.y), float2(1e-5));
            return (scene - rect.xy) / size;
        }

        // Scale a uv about its centre.
        float2 scaleAboutCenter(float2 uv, float s) {
            return (uv - float2(0.5)) / max(s, 1e-3) + float2(0.5);
        }

        // Weight nearer taps slightly higher: approximates a Gaussian falloff instead of
        // a flat disc, which reads as defocus rather than smear.
        float tapWeight(float r, float radiusPx) {
            return 1.0 - 0.45 * (r / max(radiusPx, 1e-3));
        }

        // --- Variable-radius spiral blur, cover texture -------------------------------
        // Taps sit on a golden-angle spiral, which distributes them far more evenly than
        // a square grid for the same count and avoids the axis-aligned banding a box
        // kernel produces on hard edges.
        half4 blurCover(float2 uv, float radiusPx) {
            float2 texel = uv * coverTexSize;
            if (radiusPx < 0.35) {
                return coverTex.eval(texel);
            }
            half4 acc = half4(0.0);
            float total = 0.0;
            for (int i = 0; i < TAP_COUNT; i++) {
                float fi = float(i);
                float a = fi * GOLDEN_ANGLE;
                // sqrt spreads taps uniformly over the disc area rather than clustering
                // them at the centre.
                float r = sqrt((fi + 0.5) / float(TAP_COUNT)) * radiusPx;
                float w = tapWeight(r, radiusPx);
                acc += coverTex.eval(texel + float2(cos(a), sin(a)) * r) * half(w);
                total += w;
            }
            return acc / half(max(total, 1e-3));
        }

        // --- Variable-radius spiral blur, inner texture -------------------------------
        half4 blurInner(float2 uv, float radiusPx) {
            float2 texel = uv * innerTexSize;
            if (radiusPx < 0.35) {
                return innerTex.eval(texel);
            }
            half4 acc = half4(0.0);
            float total = 0.0;
            for (int i = 0; i < TAP_COUNT; i++) {
                float fi = float(i);
                float a = fi * GOLDEN_ANGLE;
                float r = sqrt((fi + 0.5) / float(TAP_COUNT)) * radiusPx;
                float w = tapWeight(r, radiusPx);
                acc += innerTex.eval(texel + float2(cos(a), sin(a)) * r) * half(w);
                total += w;
            }
            return acc / half(max(total, 1e-3));
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;

            // ---- 1. Output pixel -> scene space through the current viewport --------
            float2 scene = mix(viewport.xy, viewport.zw, uv);

            // Signed distance from the fold line. Positive = the cover display's half.
            float axisCoord = mix(scene.x, scene.y, hingeIsY);
            float signedDist = (axisCoord - hingePos) * coverSign;

            // ---- 2. Perspective warp ------------------------------------------------
            // A gentle squeeze toward the fold. Sub-pixel at the default strength, but it
            // is what stops the expansion reading as a flat 2D zoom.
            if (perspective > 0.001) {
                float bend = perspective * (1.0 - abs(signedDist) * 2.0);
                float2 centered = scene - float2(hingePos, 0.5);
                centered.y = centered.y * (1.0 + bend * 0.12);
                scene = centered + float2(hingePos, 0.5);
                axisCoord = mix(scene.x, scene.y, hingeIsY);
                signedDist = (axisCoord - hingePos) * coverSign;
            }

            // ---- 3. Hinge-directed blur weight -------------------------------------
            // 1 at the fold line, decaying to 0 at the far edge. hingeFalloff controls
            // how tightly the blur hugs the seam.
            float hingeW = pow(clamp(1.0 - abs(signedDist) * 2.0, 0.0, 1.0), hingeFalloff);

            float coverRadius = mix(coverBlur.y, coverBlur.x, hingeW);
            float innerRadius = mix(innerBlur.y, innerBlur.x, hingeW);

            // ---- 4. Sample both representations ------------------------------------
            float2 coverUv = scaleAboutCenter(sceneToTexUv(scene, coverSceneRect), layerScale.x);
            half4 coverCol = blurCover(coverUv, coverRadius);
            coverCol = half4(applyGrade(coverCol.rgb, brightness.x, saturation.x), coverCol.a);

            half4 innerCol = coverCol;
            if (hasInner > 0.5) {
                float2 innerUv =
                    scaleAboutCenter(sceneToTexUv(scene, innerSceneRect), layerScale.y);
                innerCol = blurInner(innerUv, innerRadius);
                innerCol = half4(applyGrade(innerCol.rgb, brightness.y, saturation.y), innerCol.a);
            }

            // ---- 5. Hinge-directed cross-dissolve -----------------------------------
            // The dissolve front advances outward from the fold rather than crossfading
            // the whole surface at once, so content appears to emerge from the hinge.
            // signedDist is negative on the newly-revealed half, so that half crosses
            // over first.
            float soft = max(dissolveSoftness, 1e-3);
            float front = crossDissolve * (1.0 + soft) - soft * 0.5;
            float alongFold = 1.0 - (signedDist * 0.5 + 0.5);
            float local = smoothstep(front - soft * 0.5, front + soft * 0.5, alongFold);
            float mixT = clamp(mix(crossDissolve, local, 0.75), 0.0, 1.0);

            half3 color = mix(coverCol.rgb, innerCol.rgb, half(mixT));
            float layerA = mix(layerAlpha.x, layerAlpha.y, mixT);

            // ---- 6. Depth cues -------------------------------------------------------

            // Hinge shadow: a soft dark gradient hugging the fold, as if the two halves
            // occluded each other. Strongest exactly at the seam.
            if (hingeShadow > 0.001) {
                float shadow = pow(clamp(1.0 - abs(signedDist) * 3.2, 0.0, 1.0), 1.9);
                color = color * half(1.0 - hingeShadow * shadow * 0.85);
            }

            // Specular edge: a thin bright line along the fold. Sells "a physical panel
            // edge is catching light" far more cheaply than extra blur would.
            if (edgeIllum > 0.001) {
                float line = exp(-abs(signedDist) * 90.0);
                color = color + half3(half(edgeIllum * line * 0.55));
            }

            // Vignette, centred on the hinge rather than the screen centre (brief §13):
            // it should read as depth around the fold, not as a camera artefact.
            if (vignette > 0.001) {
                float d = length((scene - float2(hingePos, 0.5)) * float2(1.15, 0.85));
                color = color * half(1.0 - vignette * smoothstep(0.22, 0.92, d));
            }

            // ---- 7. Black veil -------------------------------------------------------
            // Deliberately small by default. See FoldTuning.blackHandoffPeak.
            color = color * half(1.0 - clamp(blackAlpha, 0.0, 1.0));

            // Skia expects premultiplied alpha.
            half a = half(clamp(layerA, 0.0, 1.0));
            return half4(clamp(color, half3(0.0), half3(1.0)) * a, a);
        }
    """.trimIndent()
}
