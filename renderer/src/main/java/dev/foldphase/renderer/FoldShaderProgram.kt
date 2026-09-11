package dev.foldphase.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.foldphase.core.CoverHalf
import dev.foldphase.core.HingeAxis
import dev.foldphase.core.SceneMapping
import dev.foldphase.engine.FoldVisualState
import dev.foldphase.engine.ShaderQuality

/**
 * Owns the compiled [RuntimeShader] and pushes per-frame uniforms into it.
 *
 * ## Performance contract (brief §22)
 *
 * - The [RuntimeShader] is compiled **once per quality level** and cached. Compilation is
 *   expensive; `setFloatUniform` is not, and does not trigger recompilation.
 * - [BitmapShader]s are built **once per bitmap identity**, not per frame. Rebuilding one
 *   every frame would re-upload the texture and is the single easiest way to destroy the
 *   frame budget.
 * - [apply] allocates nothing. No `FloatArray` is created per call; the uniform setters
 *   take varargs of primitives which do not box.
 *
 * Requires API 33 for `RuntimeShader`; the module's `minSdk` is 33 so this is always
 * satisfied, and the annotation is there for lint's benefit rather than as a real gate.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FoldShaderProgram(quality: ShaderQuality = ShaderQuality.HIGH) {

    var quality: ShaderQuality = quality
        set(value) {
            if (field != value) {
                field = value
                shader = compile(value)
            }
        }

    private var shader: RuntimeShader = compile(quality)

    // Cached texture shaders, keyed by bitmap identity so a re-set of the *same* bitmap
    // does not rebuild anything.
    private var coverBitmap: Bitmap? = null
    private var coverShader: BitmapShader? = null
    private var innerBitmap: Bitmap? = null
    private var innerShader: BitmapShader? = null

    /** The live shader. Safe to hand to a Paint every frame; it is the same instance. */
    val runtimeShader: RuntimeShader get() = shader

    /**
     * How tightly blur hugs the fold line. Mirrors `FoldTuning.hingeFalloff`.
     *
     * A uniform rather than a shader constant so the dev panel can change it live; a
     * constant would mean a recompile per adjustment, which is exactly what the tuning
     * screen exists to avoid.
     */
    var hingeFalloff: Float = 1.7f

    private fun compile(q: ShaderQuality): RuntimeShader =
        RuntimeShader(
            FoldShaderSource.SOURCE.replace(
                FoldShaderSource.TAPS_PLACEHOLDER,
                q.taps.toString(),
            ),
        )

    /**
     * Bind the cover-side texture. Cheap and idempotent when the bitmap is unchanged.
     *
     * CLAMP tiling on both axes means sampling outside the texture returns the edge
     * texel, which matters because the blur kernel deliberately reaches past the edge
     * near the seam. MIRROR would produce a visible reflected fringe there.
     */
    fun setCoverTexture(bitmap: Bitmap?) {
        if (bitmap === coverBitmap) return
        coverBitmap = bitmap
        coverShader = bitmap?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        coverShader?.let { shader.setInputShader("coverTex", it) }
    }

    fun setInnerTexture(bitmap: Bitmap?) {
        if (bitmap === innerBitmap) return
        innerBitmap = bitmap
        innerShader = bitmap?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        innerShader?.let { shader.setInputShader("innerTex", it) }
    }

    /** True once at least the cover texture is bound; the shader cannot run without it. */
    val isReady: Boolean get() = coverShader != null

    /**
     * Push one frame's worth of uniforms.
     *
     * @param state     the evaluated visual state
     * @param mapping   scene/display mapping
     * @param widthPx   output surface width
     * @param heightPx  output surface height
     * @param densityDp px per dp, for converting blur radii
     */
    fun apply(
        state: FoldVisualState,
        mapping: SceneMapping,
        widthPx: Float,
        heightPx: Float,
        densityDp: Float,
    ) {
        val s = shader

        s.setFloatUniform("resolution", widthPx, heightPx)

        val cb = coverBitmap
        val ib = innerBitmap
        s.setFloatUniform(
            "coverTexSize",
            (cb?.width ?: 1).toFloat(),
            (cb?.height ?: 1).toFloat(),
        )
        s.setFloatUniform(
            "innerTexSize",
            (ib?.width ?: 1).toFloat(),
            (ib?.height ?: 1).toFloat(),
        )

        val vp = state.viewport
        s.setFloatUniform("viewport", vp.left, vp.top, vp.right, vp.bottom)

        val cr = mapping.coverSceneRect
        s.setFloatUniform("coverSceneRect", cr.left, cr.top, cr.right, cr.bottom)
        val ir = mapping.innerSceneRect
        s.setFloatUniform("innerSceneRect", ir.left, ir.top, ir.right, ir.bottom)

        s.setFloatUniform("hingePos", mapping.hingePosition)
        s.setFloatUniform("hingeIsY", if (mapping.hingeAxis == HingeAxis.HORIZONTAL) 1f else 0f)
        s.setFloatUniform("coverSign", if (mapping.coverHalf == CoverHalf.RIGHT) 1f else -1f)
        s.setFloatUniform("hingeFalloff", hingeFalloff)

        // Blur radii arrive in dp and must reach the shader in pixels: the same visual
        // blur has to be a bigger pixel radius on the denser panel, or the effect would
        // visibly change strength across the handoff.
        s.setFloatUniform(
            "coverBlur",
            state.coverBlurDp * densityDp,
            state.coverEdgeBlurDp * densityDp,
        )
        s.setFloatUniform(
            "innerBlur",
            state.innerBlurDp * densityDp,
            state.innerEdgeBlurDp * densityDp,
        )

        s.setFloatUniform("layerScale", state.coverScale, state.innerScale)
        s.setFloatUniform("brightness", state.coverBrightness, state.innerBrightness)
        s.setFloatUniform("saturation", state.coverSaturation, state.innerSaturation)
        // The surface itself stays opaque; per-layer alpha is carried by the dissolve.
        s.setFloatUniform("layerAlpha", 1f, 1f)

        s.setFloatUniform("crossDissolve", state.crossDissolve)
        s.setFloatUniform("dissolveSoftness", state.dissolveSoftness)

        s.setFloatUniform("vignette", state.vignetteIntensity)
        s.setFloatUniform("hingeShadow", state.hingeShadowIntensity)
        s.setFloatUniform("edgeIllum", state.edgeIlluminationIntensity)
        s.setFloatUniform("blackAlpha", state.blackOverlayAlpha)
        s.setFloatUniform("perspective", state.perspective)
        s.setFloatUniform("hasInner", if (innerShader != null) 1f else 0f)
    }

    /** Drop texture references so the bitmaps can be collected. */
    fun release() {
        coverBitmap = null
        coverShader = null
        innerBitmap = null
        innerShader = null
    }
}
