package dev.foldphase.engine

import dev.foldphase.core.Curves
import dev.foldphase.core.FoldDirection
import dev.foldphase.core.FoldProgress
import dev.foldphase.core.SceneMapping
import kotlin.math.abs

/**
 * Maps `(progress, velocity, direction)` onto a [FoldVisualState].
 *
 * ## The one rule (brief §7)
 *
 * > The hinge position is the timeline.
 *
 * [evaluate] is a **pure function of progress** (plus small, bounded velocity and
 * direction terms that affect concealment only, never geometry). There is no clock, no
 * animator, no duration, and no internal state that advances on its own. Consequences,
 * all of which are acceptance criteria:
 *
 * - Hold the hinge at `p = 0.27` for five seconds and the frame does not change, because
 *   nothing in here can change without `p` changing.
 * - Reverse through `0.27 → 0.22 → 0.18` and the visual state retraces exactly, because
 *   `evaluate(0.22)` returns the same thing on the way down as on the way up.
 * - Closing is the mathematical reverse of opening for free (brief §11), with no separate
 *   code path to keep in sync.
 *
 * This is also why the engine is trivially testable: it has no dependency on Android, on
 * time, or on a display.
 */
class FoldAnimationEngine(
    var tuning: FoldTuning = FoldTuningPresets.APPLE_LIKE,
    var sceneMapping: SceneMapping = SceneMapping.Z_FOLD_7_DEFAULT,
) {

    /**
     * @param progress       normalised fold progress `[0,1]`
     * @param velocityDegPerSec signed hinge velocity, for the concealment boost only
     * @param direction      committed direction after hysteresis
     * @param handoffCenter  measured handoff progress, or the fallback if unmeasured
     */
    fun evaluate(
        progress: Float,
        velocityDegPerSec: Float = 0f,
        direction: FoldDirection = FoldDirection.HOLD,
        handoffCenter: Float = tuning.handoffCenterFallback,
    ): FoldVisualState {
        val t = tuning
        val p = Curves.clamp(progress)

        // ---- Phase A: deadband -------------------------------------------------
        // Below the deadband the answer is exactly "untouched cover": no blur, no scale,
        // no veil. Returning a canonical state (rather than a computed near-identity)
        // guarantees that resting closed is bit-identical frame to frame, so nothing
        // shimmers and the renderer's idle fast-path can kick in.
        //
        // The viewport must still be the *cover* crop, not the scene default. Carrying
        // the default here would make the renderer show the whole scene while closed and
        // then snap to the cover framing the instant progress crossed the deadband — a
        // hard visible jump at the start of every single fold.
        if (p <= t.deadbandEnd) {
            return FoldVisualState.CLOSED.copy(
                progress = p,
                viewport = sceneMapping.coverSceneRect,
            )
        }
        if (p >= 1f) {
            return FoldVisualState.OPEN.copy(
                progress = p,
                viewport = sceneMapping.innerSceneRect,
            )
        }

        val opening = direction != FoldDirection.CLOSING
        val center = t.handoffCenter(handoffCenter, opening)

        // ---- Concealment envelope ----------------------------------------------
        // A raised-cosine bump centred on the *measured* handoff, not a guessed one.
        // smootherstep (C²) rather than smoothstep so there is no acceleration kink at
        // the window edges — a kink there is exactly what reads as "an animation played".
        val rawConceal = Curves.bumpAround(p, center, t.handoffHalfWidth)

        // Fast movement earns a little extra concealment: the eye resolves less detail,
        // and the panel swap lands over a wider progress range when moving quickly.
        val speedFactor = Curves.clamp(
            abs(velocityDegPerSec) / t.velocityReferenceDegPerSec,
            0f,
            1f,
        )
        val conceal = Curves.clamp(rawConceal * (1f + t.velocityConcealmentBoost * speedFactor))

        // ---- Cover (outgoing) layer --------------------------------------------
        val coverFade = t.coverAlphaCurve.apply(
            Curves.remap(p, t.outerFadeStart, t.outerFadeEnd),
        )
        val coverAlpha = 1f - coverFade

        val coverScaleT = t.scaleCurve.apply(Curves.remap(p, t.deadbandEnd, t.outerFadeEnd))
        val coverScale = 1f - t.coverScaleAmount * coverScaleT

        // ---- Inner (incoming) layer --------------------------------------------
        val innerReveal = t.innerAlphaCurve.apply(
            Curves.remap(p, t.innerFadeStart, t.innerFadeEnd),
        )
        val innerAlpha = innerReveal
        // Settles from oversized down to exactly 1.0 — the "settling into the larger
        // display" impression (brief §10), with no overshoot.
        val innerScale = Curves.lerp(t.innerStartScale, 1f, innerReveal)

        // ---- Blur ---------------------------------------------------------------
        // Two radii per layer: at the hinge edge and at the outer edge. The shader
        // interpolates between them by distance from the fold, which is what produces the
        // hinge-directed gradient the reference animation is built on (research §1.2 A).
        val blurEnvelope = t.blurCurve.apply(Curves.remap(p, t.deadbandEnd, center))
        val coverBlurBase = t.maxHingeBlurDp * maxOf(blurEnvelope, conceal)
        val coverEdgeBlur = t.maxOuterEdgeBlurDp * maxOf(blurEnvelope, conceal)

        // The inner layer's blur runs the other way: maximal as it appears, resolving to
        // zero as it settles.
        val innerBlurEnvelope = 1f - t.innerAlphaCurve.apply(
            Curves.remap(p, t.innerFadeStart, t.innerFadeEnd),
        )
        val innerBlur = t.maxHingeBlurDp * maxOf(innerBlurEnvelope * 0.75f, conceal)
        val innerEdgeBlur = t.maxOuterEdgeBlurDp * maxOf(innerBlurEnvelope * 0.75f, conceal)

        // ---- Luminance / colour -------------------------------------------------
        val dimT = t.dimCurve.apply(Curves.remap(p, t.deadbandEnd, center))
        val dim = maxOf(dimT, conceal)
        val brightness = 1f - t.maxDim * dim
        val saturation = 1f - t.maxDesaturation * dim

        // The inner layer comes up from dim to normal on the far side of the handoff.
        val innerBrightness = Curves.lerp(
            1f - t.maxDim,
            1f,
            t.innerAlphaCurve.apply(Curves.remap(p, center, t.innerFadeEnd)),
        )

        // ---- Depth cues ----------------------------------------------------------
        val vignette = t.maxVignette * t.vignetteCurve.apply(maxOf(dimT, conceal))
        val hingeShadow = t.maxHingeShadow * Curves.smoothstep(
            maxOf(Curves.remap(p, t.deadbandEnd, center), conceal),
        )
        // Edge illumination is a pure bump: it exists only around the handoff, where a
        // physical panel edge would actually be catching light.
        val edgeIllumination = t.maxEdgeIllumination * rawConceal
        val perspective = t.maxPerspective * Curves.smoothstep(
            Curves.remap(p, t.deadbandEnd, t.outerFadeEnd),
        )

        // ---- Wallpaper / icon sub-layers ----------------------------------------
        // The wallpaper recedes further than the icons do. That differential is the
        // parallax that makes the scene read as having depth rather than being a flat
        // image being scaled (brief §9 "wallpaper begins moving perceptually backward").
        val wallpaperScale = 1f - t.wallpaperScaleAmount * coverScaleT
        val iconAlpha = 1f - Curves.easeIn(
            Curves.remap(p, t.outerFadeStart + t.transitionOverlap * 0.5f, t.outerFadeEnd),
            1.6f,
        )
        val iconScale = 1f - t.coverScaleAmount * 1.35f * coverScaleT

        // ---- Black veil ----------------------------------------------------------
        // Low by default; see the doc on `FoldTuning.blackHandoffPeak` for why this
        // departs from the brief's proposal.
        val black = t.blackHandoffPeak * rawConceal

        // ---- Scene geometry ------------------------------------------------------
        // The portal: one scene, a viewport that grows from the cover's native framing to
        // the full scene. Both physical displays evaluate this identically, which is what
        // makes the panel swap invisible (research §4.1).
        val viewportT = t.viewportCurve.apply(Curves.remap(p, t.deadbandEnd, t.innerFadeEnd))
        val viewport = sceneMapping.viewportAt(viewportT)

        // Cross-dissolve is centred on the handoff so the two representations swap at the
        // moment they are least legible, not at an arbitrary progress.
        val crossDissolve = Curves.smootherstep(
            Curves.remap(
                p,
                center - t.transitionOverlap,
                center + t.transitionOverlap,
            ),
        )

        return FoldVisualState(
            progress = p,
            coverAlpha = coverAlpha,
            coverScale = coverScale,
            coverScaleX = coverScale,
            coverScaleY = coverScale,
            coverBlurDp = coverBlurBase,
            coverEdgeBlurDp = coverEdgeBlur,
            coverBrightness = brightness,
            coverSaturation = saturation,
            innerAlpha = innerAlpha,
            innerScale = innerScale,
            innerScaleX = innerScale,
            innerScaleY = innerScale,
            innerBlurDp = innerBlur,
            innerEdgeBlurDp = innerEdgeBlur,
            innerBrightness = innerBrightness,
            innerSaturation = 1f - t.maxDesaturation * (1f - innerReveal) * 0.5f,
            backgroundAlpha = 1f,
            wallpaperScale = wallpaperScale,
            iconAlpha = iconAlpha,
            iconScale = iconScale,
            blackOverlayAlpha = black,
            vignetteIntensity = vignette,
            hingeShadowIntensity = hingeShadow,
            edgeIlluminationIntensity = edgeIllumination,
            perspective = perspective,
            viewport = viewport,
            crossDissolve = crossDissolve,
            dissolveSoftness = t.dissolveSoftness * (0.6f + 0.4f * (1f - rawConceal)),
        )
    }

    /** Convenience overload taking a pipeline sample directly. */
    fun evaluate(sample: FoldProgress, handoffCenter: Float): FoldVisualState = evaluate(
        progress = sample.progress,
        velocityDegPerSec = sample.velocityDegPerSec,
        direction = sample.direction,
        handoffCenter = handoffCenter,
    )
}
