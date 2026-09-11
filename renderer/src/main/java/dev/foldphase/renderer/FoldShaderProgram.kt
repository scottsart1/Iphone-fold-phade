package dev.foldphase.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.foldphase.core.CoverHalf
import dev.foldphase.core.HingeAxis
import dev.foldphase.core.SceneMapping
import dev.foldphase.engine.FoldVisualState
import dev.foldphase.engine.ShaderQuality

/**
 * Owns the compiled [RuntimeShader] and pushes per-frame uniforms into it.
 *
 * ## Performance contract
 *
 * - The [RuntimeShader] is compiled **once per quality level** and cached. Compilation is
 *   expensive; `setFloatUniform` is not, and does not trigger recompilation.
 * - [BitmapShader]s are built **once per bitmap identity**, not per frame. Rebuilding one
 *   every frame would re-upload the texture and is the easiest way to destroy the budget.
 * - [apply] allocates nothing on the happy path.
 *
 * ## Why this class is defensive
 *
 * Two AGSL failure modes will crash an app on a device the author never tested, and both
 * are invisible until they happen:
 *
 * 1. **Compilation can fail.** `RuntimeShader(source)` throws `IllegalArgumentException`
 *    on a driver that rejects otherwise-valid source. Constructed eagerly inside a
 *    composable's `remember {}`, that takes the whole screen down.
 * 2. **Uniforms can vanish.** Skia's optimiser removes uniforms it decides are unused,
 *    and `setFloatUniform` on a name that is no longer in the compiled program **throws**.
 *    A uniform used only inside a branch is a realistic candidate. This is the classic
 *    AGSL foot-gun and it would fire on the very first frame.
 *
 * So compilation is wrapped and reported through [isSupported], and every uniform write
 * goes through [setF], which records a missing name on first failure and skips it
 * thereafter. A `try`/`catch` that does not throw costs nothing on the JVM, so the guard
 * is free on the hot path — and it self-heals rather than needing a probe pass.
 *
 * When [isSupported] is false the caller must fall back to [FoldFallbackSurface].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FoldShaderProgram(quality: ShaderQuality = ShaderQuality.HIGH) {

    var quality: ShaderQuality = quality
        set(value) {
            if (field != value) {
                field = value
                shader = compileSafely(value)
                // A different program has a different set of surviving uniforms.
                missingUniforms.clear()
                rebindTextures()
            }
        }

    private var shader: RuntimeShader? = compileSafely(quality)

    /** Names Skia optimised away. Populated on first failure, then skipped for free. */
    private val missingUniforms = HashSet<String>(4)

    private var coverBitmap: Bitmap? = null
    private var coverShader: BitmapShader? = null
    private var innerBitmap: Bitmap? = null
    private var innerShader: BitmapShader? = null

    /**
     * False when the shader could not be compiled on this device.
     *
     * Callers must render [FoldFallbackSurface] instead of failing.
     */
    val isSupported: Boolean get() = shader != null

    /** Why compilation failed, for the diagnostics screen. Null when it succeeded. */
    var compileError: String? = null
        private set

    /**
     * The live shader, or null if unsupported. Safe to hand to a Paint every frame; it is
     * the same instance until [quality] changes.
     */
    val runtimeShader: RuntimeShader? get() = shader

    /** How tightly blur hugs the fold line. Mirrors `FoldTuning.hingeFalloff`. */
    var hingeFalloff: Float = 1.7f

    private fun compileSafely(q: ShaderQuality): RuntimeShader? = try {
        RuntimeShader(
            FoldShaderSource.SOURCE.replace(
                FoldShaderSource.TAPS_PLACEHOLDER,
                q.taps.toString(),
            ),
        ).also { compileError = null }
    } catch (t: Throwable) {
        // Message carries the offending line, which is the only diagnostic available on a
        // device with no debugger attached — so surface it rather than swallowing it.
        compileError = t.message ?: t.javaClass.simpleName
        Log.e(TAG, "AGSL compilation failed; falling back to the non-shader renderer", t)
        null
    }

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
        coverShader = bitmap?.takeIf { !it.isRecycled }?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        bindInput("coverTex", coverShader)
    }

    fun setInnerTexture(bitmap: Bitmap?) {
        if (bitmap === innerBitmap) return
        innerBitmap = bitmap
        innerShader = bitmap?.takeIf { !it.isRecycled }?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        bindInput("innerTex", innerShader)
    }

    private fun rebindTextures() {
        bindInput("coverTex", coverShader)
        bindInput("innerTex", innerShader)
    }

    private fun bindInput(name: String, value: Shader?) {
        val s = shader ?: return
        if (value == null) return
        try {
            s.setInputShader(name, value)
        } catch (t: Throwable) {
            Log.w(TAG, "setInputShader('$name') failed", t)
        }
    }

    /** True once the shader compiled and at least the cover texture is bound. */
    val isReady: Boolean get() = shader != null && coverShader != null

    /** Push one frame's worth of uniforms. No-op when the shader is unsupported. */
    fun apply(
        state: FoldVisualState,
        mapping: SceneMapping,
        widthPx: Float,
        heightPx: Float,
        densityDp: Float,
    ) {
        val s = shader ?: return

        setF(s, "resolution", widthPx, heightPx)

        val cb = coverBitmap
        val ib = innerBitmap
        setF(s, "coverTexSize", (cb?.width ?: 1).toFloat(), (cb?.height ?: 1).toFloat())
        setF(s, "innerTexSize", (ib?.width ?: 1).toFloat(), (ib?.height ?: 1).toFloat())

        val vp = state.viewport
        setF(s, "viewport", vp.left, vp.top, vp.right, vp.bottom)

        val cr = mapping.coverSceneRect
        setF(s, "coverSceneRect", cr.left, cr.top, cr.right, cr.bottom)
        val ir = mapping.innerSceneRect
        setF(s, "innerSceneRect", ir.left, ir.top, ir.right, ir.bottom)

        setF(s, "hingePos", mapping.hingePosition)
        setF(s, "hingeIsY", if (mapping.hingeAxis == HingeAxis.HORIZONTAL) 1f else 0f)
        setF(s, "coverSign", if (mapping.coverHalf == CoverHalf.RIGHT) 1f else -1f)
        setF(s, "hingeFalloff", hingeFalloff)

        // Blur radii arrive in dp and must reach the shader in pixels: the same visual
        // blur has to be a bigger pixel radius on the denser panel, or the effect would
        // visibly change strength across the handoff.
        setF(s, "coverBlur", state.coverBlurDp * densityDp, state.coverEdgeBlurDp * densityDp)
        setF(s, "innerBlur", state.innerBlurDp * densityDp, state.innerEdgeBlurDp * densityDp)

        setF(s, "layerScale", state.coverScale, state.innerScale)
        setF(s, "brightness", state.coverBrightness, state.innerBrightness)
        setF(s, "saturation", state.coverSaturation, state.innerSaturation)
        // The surface itself stays opaque; per-layer alpha is carried by the dissolve.
        setF(s, "layerAlpha", 1f, 1f)

        setF(s, "crossDissolve", state.crossDissolve)
        setF(s, "dissolveSoftness", state.dissolveSoftness)

        setF(s, "vignette", state.vignetteIntensity)
        setF(s, "hingeShadow", state.hingeShadowIntensity)
        setF(s, "edgeIllum", state.edgeIlluminationIntensity)
        setF(s, "blackAlpha", state.blackOverlayAlpha)
        setF(s, "perspective", state.perspective)
        setF(s, "hasInner", if (innerShader != null) 1f else 0f)
    }

    // --- Uniform setters that cannot throw ------------------------------------------
    //
    // Skia removes uniforms it considers unused, after which setFloatUniform throws.
    // Recording the name on first failure means each vanished uniform costs one caught
    // exception in the app's lifetime and nothing thereafter.

    private fun setF(s: RuntimeShader, name: String, a: Float) {
        if (name in missingUniforms) return
        try {
            s.setFloatUniform(name, a)
        } catch (t: Throwable) {
            noteMissing(name, t)
        }
    }

    private fun setF(s: RuntimeShader, name: String, a: Float, b: Float) {
        if (name in missingUniforms) return
        try {
            s.setFloatUniform(name, a, b)
        } catch (t: Throwable) {
            noteMissing(name, t)
        }
    }

    private fun setF(s: RuntimeShader, name: String, a: Float, b: Float, c: Float, d: Float) {
        if (name in missingUniforms) return
        try {
            s.setFloatUniform(name, a, b, c, d)
        } catch (t: Throwable) {
            noteMissing(name, t)
        }
    }

    private fun noteMissing(name: String, t: Throwable) {
        missingUniforms.add(name)
        Log.w(TAG, "Uniform '$name' is not present in the compiled shader; skipping it", t)
    }

    /** Drop texture references so the bitmaps can be collected. */
    fun release() {
        coverBitmap = null
        coverShader = null
        innerBitmap = null
        innerShader = null
    }

    private companion object {
        const val TAG = "FoldShaderProgram"
    }
}
