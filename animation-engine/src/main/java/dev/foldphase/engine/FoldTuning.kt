package dev.foldphase.engine

import dev.foldphase.core.CurveShape

/**
 * Every animation constant in the project, in one place (brief §8, §12, §21).
 *
 * Each field is a phase boundary, a magnitude, or a curve shape. Nothing here is baked
 * into a Compose component, a shader literal, or a magic number at a call site — the dev
 * panel mutates this object at runtime and the next frame reflects it, with no recompile
 * and no restart.
 *
 * Values are annotated with *why* they are what they are, referencing `docs/RESEARCH.md`.
 * Where the research contradicted the brief's proposed starting numbers, the research
 * wins and the deviation is called out explicitly.
 */
data class FoldTuning(

    // ---------------------------------------------------------------------------
    // Phase boundaries, in normalised progress.
    // ---------------------------------------------------------------------------

    /** Phase A: below this nothing moves at all. Kills wobble from hinge micro-movement. */
    val deadbandEnd: Float = 0.08f,

    /** Phase B start — the first, deliberately understated response. */
    val outerFadeStart: Float = 0.08f,

    /** Phase C/D — the cover layer has lost visual dominance by here. */
    val outerFadeEnd: Float = 0.62f,

    /** Phase D — the inner layer starts being perceptible. */
    val innerFadeStart: Float = 0.46f,

    /** Phase E — the inner layer is fully resolved by here. */
    val innerFadeEnd: Float = 0.94f,

    /**
     * Where the cover and inner curves overlap. The overlap is what makes the transition
     * read as a dissolve rather than a cut; too little and you see a gap, too much and
     * both layers are simultaneously legible, which destroys the illusion.
     */
    val transitionOverlap: Float = 0.16f,

    // ---------------------------------------------------------------------------
    // Handoff concealment (brief §9 Phase D, research §1.2 B and §3.4).
    // ---------------------------------------------------------------------------

    /**
     * Fallback centre of the concealment window, used **only** until `HandoffLearner`
     * has observed a real panel swap on this device. Once it has, the measured value
     * replaces this — see `HandoffLearner` for why that matters so much.
     */
    val handoffCenterFallback: Float = 0.58f,

    /** Half-width of the concealment window in progress units. */
    val handoffHalfWidth: Float = 0.11f,

    /**
     * Peak opacity of the black veil.
     *
     * **This deliberately departs from the brief's proposal.** The brief suggests going
     * "nearly black" around the handoff and treating the black frame as a transition
     * mask. The research (§1.2 B) found Apple does something different: hands-on
     * descriptions are of *"seeing through from the outer screen to the larger screen"*
     * with *"depth-of-field"* — a defocused see-through crossfade, not a blackout. A
     * blackout is the safe engineering answer but it is not the reference answer, and it
     * costs the "one continuous interface" impression that is the acceptance criterion.
     *
     * So the default is low: enough to crush the contrast that would make a hardware
     * discontinuity visible, not enough to read as a black frame. If a particular device
     * *does* flash, `FoldTuningPresets.SAFE_HANDOFF` raises this to 0.85 and the dev
     * panel slider goes to 1.0.
     */
    val blackHandoffPeak: Float = 0.12f,

    // ---------------------------------------------------------------------------
    // Magnitudes.
    // ---------------------------------------------------------------------------

    /**
     * Peak blur at the hinge edge, in dp.
     *
     * The hinge edge and the outer edge are tuned separately because research §1.2(A) is
     * the most specific published detail we have: the blur appears on *the side of each
     * screen that meets the fold*, not uniformly. [maxOuterEdgeBlurDp] is therefore much
     * smaller — the outer edge stays comparatively sharp throughout.
     */
    val maxHingeBlurDp: Float = 26f,
    val maxOuterEdgeBlurDp: Float = 7f,

    /** How sharply blur falls off with distance from the hinge. Higher = tighter to the seam. */
    val hingeFalloff: Float = 1.7f,

    /** Peak dimming, as a multiplier floor on brightness. */
    val maxDim: Float = 0.34f,

    /** Cover layer scale-down at peak. Brief suggests 1–3%; 1.8% sits in that band. */
    val coverScaleAmount: Float = 0.018f,

    /** Inner layer's starting oversize. Brief §10 suggests 1.04–1.08. */
    val innerStartScale: Float = 1.055f,

    /** Wallpaper recedes slightly more than icons, which creates the parallax depth cue. */
    val wallpaperScaleAmount: Float = 0.042f,

    val maxVignette: Float = 0.46f,
    val maxHingeShadow: Float = 0.55f,

    /**
     * A thin bright line along the fold, peaking at the handoff.
     *
     * Not from the reference material — it is an original addition. A specular edge sells
     * "a physical panel is emerging" far more cheaply than any amount of extra blur,
     * because it reads as light catching a real edge. Subtle by default; set to 0 to
     * disable.
     */
    val maxEdgeIllumination: Float = 0.30f,

    /** Simulated perspective strength. Kept low — strong perspective reads as gimmicky. */
    val maxPerspective: Float = 0.16f,

    /** Peak desaturation. Slight: colour draining fully reads as a "system" effect. */
    val maxDesaturation: Float = 0.22f,

    /** Width of the dissolve gradient in scene units at its widest. */
    val dissolveSoftness: Float = 0.38f,

    // ---------------------------------------------------------------------------
    // Curve shapes, per property (brief §12).
    // ---------------------------------------------------------------------------

    val blurCurve: CurveShape = CurveShape.EASE_IN,
    val coverAlphaCurve: CurveShape = CurveShape.APPLE_STANDARD,
    val innerAlphaCurve: CurveShape = CurveShape.APPLE_SETTLE,
    val scaleCurve: CurveShape = CurveShape.APPLE_STANDARD,
    val viewportCurve: CurveShape = CurveShape.APPLE_STANDARD,
    val dimCurve: CurveShape = CurveShape.SMOOTHSTEP,
    val vignetteCurve: CurveShape = CurveShape.SMOOTHSTEP,

    // ---------------------------------------------------------------------------
    // Direction handling (brief §11).
    // ---------------------------------------------------------------------------

    /**
     * Asymmetry between opening and closing.
     *
     * **Defaults to zero, on purpose.** The brief says to implement closing as the exact
     * mathematical reverse first, and research §1.3 could not verify that Apple's is
     * asymmetric at all. The hook exists so asymmetry can be introduced later from
     * evidence rather than assumed now from taste. Positive shifts the concealment window
     * slightly later when opening.
     */
    val directionalBias: Float = 0f,

    /**
     * Extra concealment applied in proportion to hinge speed.
     *
     * A fast snap-open gives the eye less time to resolve detail, so a little extra blur
     * is free perceptually and buys margin around a panel swap that may land anywhere in
     * a wide progress range when moving quickly. Normalised against
     * [velocityReferenceDegPerSec].
     */
    val velocityConcealmentBoost: Float = 0.22f,
    val velocityReferenceDegPerSec: Float = 420f,

    // ---------------------------------------------------------------------------
    // Rendering quality.
    // ---------------------------------------------------------------------------

    /** Blur kernel taps. Trades fidelity for fill rate; see `ShaderQuality`. */
    val shaderQuality: ShaderQuality = ShaderQuality.HIGH,
) {
    /** Effective handoff centre once direction is taken into account. */
    fun handoffCenter(base: Float, opening: Boolean): Float {
        val bias = if (opening) directionalBias else -directionalBias
        return (base + bias).coerceIn(0.05f, 0.95f)
    }
}

/**
 * Blur kernel size. The variable-radius blur samples the source texture this many times
 * per pixel, so it is the dominant cost of the whole effect.
 *
 * At 120 Hz there are ~8.33 ms per frame (brief §22), and the effect is fullscreen, so
 * this is the knob that decides whether the transition holds refresh rate. The dev panel
 * exposes it and the frame-timing readout shows the consequence immediately.
 */
enum class ShaderQuality(val taps: Int) {
    /** 5 taps. For 60 Hz mode, battery saver, or if profiling shows fill-rate limits. */
    LOW(5),

    /** 9 taps. Good middle ground. */
    MEDIUM(9),

    /** 13 taps on a golden-angle spiral. The default; holds 120 Hz on Fold 7 class GPUs. */
    HIGH(13),
}

/**
 * Named presets (brief §21 — "Reset to Apple-like defaults").
 */
object FoldTuningPresets {

    /** The default. Derived from `docs/RESEARCH.md` §1.2 and §4.2. */
    val APPLE_LIKE = FoldTuning()

    /**
     * For devices where the panel swap visibly flashes despite a correctly-learned
     * handoff point. Trades the "see-through" quality for a guaranteed-invisible
     * discontinuity by going properly black across a wider window.
     */
    val SAFE_HANDOFF = FoldTuning(
        blackHandoffPeak = 0.85f,
        handoffHalfWidth = 0.15f,
        maxHingeBlurDp = 30f,
        maxDim = 0.5f,
        maxVignette = 0.55f,
    )

    /**
     * Exaggerated, for development. Makes every effect obvious so you can see what each
     * slider actually does before dialling it back.
     */
    val EXAGGERATED = FoldTuning(
        maxHingeBlurDp = 48f,
        maxOuterEdgeBlurDp = 20f,
        coverScaleAmount = 0.08f,
        innerStartScale = 1.18f,
        maxVignette = 0.8f,
        maxHingeShadow = 0.9f,
        maxEdgeIllumination = 0.7f,
        maxPerspective = 0.4f,
        maxDesaturation = 0.6f,
        blackHandoffPeak = 0.3f,
    )

    /** Minimal — nearly a straight crossfade. Useful as a perceptual baseline for A/B. */
    val SUBTLE = FoldTuning(
        maxHingeBlurDp = 12f,
        maxOuterEdgeBlurDp = 2f,
        coverScaleAmount = 0.008f,
        innerStartScale = 1.02f,
        maxVignette = 0.2f,
        maxHingeShadow = 0.25f,
        maxEdgeIllumination = 0.12f,
        maxPerspective = 0.06f,
        maxDesaturation = 0.08f,
        blackHandoffPeak = 0.05f,
    )

    val ALL: Map<String, FoldTuning> = linkedMapOf(
        "Apple-like (default)" to APPLE_LIKE,
        "Safe handoff" to SAFE_HANDOFF,
        "Subtle" to SUBTLE,
        "Exaggerated (dev)" to EXAGGERATED,
    )
}
