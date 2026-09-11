package dev.foldphase.engine

import dev.foldphase.core.SceneRect

/**
 * The complete description of what one physical display should look like at a given fold
 * progress.
 *
 * Brief §8: every animation constant lives centrally rather than scattered through
 * Compose components, and this is the value type that carries the result out. It is a
 * plain immutable data class with no Android or Compose types in it, which means it is
 * unit-testable, and means the shader, the Compose layer and the overlay can all consume
 * the same evaluation without any of them re-deriving anything.
 *
 * All fields are "what to draw", never "how far through an animation we are" — there is
 * no time in here at all, which is the whole point (brief §7).
 */
data class FoldVisualState(
    // ---- Global ----------------------------------------------------------------
    /** Fold progress this state was evaluated at. Carried for diagnostics/overlays. */
    val progress: Float = 0f,

    // ---- Cover (outgoing) layer ------------------------------------------------
    val coverAlpha: Float = 1f,
    /** Uniform scale applied to the cover layer. <1 reads as receding. */
    val coverScale: Float = 1f,
    val coverScaleX: Float = 1f,
    val coverScaleY: Float = 1f,
    val coverTranslationX: Float = 0f,
    val coverTranslationY: Float = 0f,
    /** Blur radius in dp *at the hinge edge*. Falls off toward the outer edge. */
    val coverBlurDp: Float = 0f,
    /** Blur radius in dp at the outer edge. Normally much smaller than the hinge edge. */
    val coverEdgeBlurDp: Float = 0f,
    val coverBrightness: Float = 1f,
    val coverSaturation: Float = 1f,

    // ---- Inner (incoming) layer ------------------------------------------------
    val innerAlpha: Float = 0f,
    val innerScale: Float = 1f,
    val innerScaleX: Float = 1f,
    val innerScaleY: Float = 1f,
    val innerTranslationX: Float = 0f,
    val innerTranslationY: Float = 0f,
    val innerBlurDp: Float = 0f,
    val innerEdgeBlurDp: Float = 0f,
    val innerBrightness: Float = 1f,
    val innerSaturation: Float = 1f,

    // ---- Content sub-layers ----------------------------------------------------
    val backgroundAlpha: Float = 1f,
    val wallpaperScale: Float = 1f,
    val iconAlpha: Float = 1f,
    val iconScale: Float = 1f,

    // ---- Concealment / depth ---------------------------------------------------
    /** Opacity of the black veil used to mask the physical panel handoff. */
    val blackOverlayAlpha: Float = 0f,
    val vignetteIntensity: Float = 0f,
    /** Darkness of the soft shadow cast along the hinge line. */
    val hingeShadowIntensity: Float = 0f,
    /** Brightness of the thin specular highlight along the hinge line. */
    val edgeIlluminationIntensity: Float = 0f,
    /** Simulated perspective / camera distance effect, 0 = flat. */
    val perspective: Float = 0f,

    // ---- Scene geometry --------------------------------------------------------
    /** The viewport onto the shared scene both displays should render (research §4.1). */
    val viewport: SceneRect = SceneRect.FULL,
    /**
     * How far the cross-dissolve between cover and inner representations has advanced.
     * 0 = pure cover, 1 = pure inner.
     */
    val crossDissolve: Float = 0f,
    /**
     * Width of the hinge-directed gradient over which the dissolve happens, in scene
     * units. Narrow = a hard wipe, wide = a soft bloom outward from the hinge.
     */
    val dissolveSoftness: Float = 0.35f,
) {
    /** True if nothing visible is happening and the renderer may take the cheap path. */
    val isIdentity: Boolean
        get() = coverBlurDp < 0.01f && innerBlurDp < 0.01f &&
            blackOverlayAlpha < 0.001f && vignetteIntensity < 0.001f &&
            (progress <= 0f || progress >= 1f)

    companion object {
        /** Fully folded: cover shown untouched. */
        val CLOSED = FoldVisualState(progress = 0f, coverAlpha = 1f, innerAlpha = 0f)

        /** Fully open: inner shown untouched. */
        val OPEN = FoldVisualState(
            progress = 1f,
            coverAlpha = 0f,
            innerAlpha = 1f,
            crossDissolve = 1f,
        )
    }
}
