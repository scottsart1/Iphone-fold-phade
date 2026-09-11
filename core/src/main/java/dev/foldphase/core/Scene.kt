package dev.foldphase.core

/** An axis-aligned rectangle in normalised scene space. */
data class SceneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun lerpTo(other: SceneRect, t: Float): SceneRect = SceneRect(
        left = Curves.lerp(left, other.left, t),
        top = Curves.lerp(top, other.top, t),
        right = Curves.lerp(right, other.right, t),
        bottom = Curves.lerp(bottom, other.bottom, t),
    )

    companion object {
        val FULL = SceneRect(0f, 0f, 1f, 1f)
    }
}

/** Which physical half of the unfolded device the cover display sits behind. */
enum class CoverHalf { LEFT, RIGHT }

/** Orientation of the fold line within the unfolded scene. */
enum class HingeAxis {
    /** Fold line runs top-to-bottom; the scene splits left/right. Z Fold in portrait. */
    VERTICAL,

    /** Fold line runs left-to-right; the scene splits top/bottom. Flip-style, or Fold in landscape. */
    HORIZONTAL,
}

/**
 * The mapping between one abstract scene and the two physical displays.
 *
 * ## Why this exists (brief §14, research §4.1)
 *
 * The cover and inner displays differ in resolution, physical size, aspect ratio and
 * density. Naively scaling a cover-sized image up until it fills the inner display looks
 * cheap, because it scales *everything* — including elements whose perceived size should
 * not change.
 *
 * Instead we define a single normalised scene, `[0,1] x [0,1]`, describing the
 * **unfolded** layout, and treat each physical display as a **viewport** onto it:
 *
 * - Folded, the cover display shows [coverSceneRect] — a tall narrow crop of one half.
 * - Unfolded, the inner display shows the whole scene.
 * - Mid-fold, the rendered viewport interpolates between the two.
 *
 * Two properties fall out of this, and they are the entire point of the design:
 *
 * 1. Content appears to **expand outward from the hinge** rather than zoom uniformly,
 *    which is what Apple's "stretch / flows across displays" actually looks like.
 * 2. **Both physical displays evaluate the same function of progress.** At the instant
 *    One UI swaps panels, the frame the inner display draws is the frame the cover
 *    display would have drawn. There is no discontinuity to conceal, because there is
 *    no discontinuity.
 */
data class SceneMapping(
    /** The crop of the scene that the cover display natively frames when fully folded. */
    val coverSceneRect: SceneRect,
    /** The crop the inner display frames when fully open. Normally the whole scene. */
    val innerSceneRect: SceneRect = SceneRect.FULL,
    /** Which half the cover display sits behind. Determines the hinge-adjacent edge. */
    val coverHalf: CoverHalf = CoverHalf.RIGHT,
    val hingeAxis: HingeAxis = HingeAxis.VERTICAL,
    /** Position of the fold line in scene space along the axis it splits. */
    val hingePosition: Float = 0.5f,
) {

    /**
     * The viewport to render at fold progress [t] (already eased by the caller).
     *
     * `t = 0` gives the cover's native framing, `t = 1` the inner's.
     */
    fun viewportAt(t: Float): SceneRect = coverSceneRect.lerpTo(innerSceneRect, Curves.clamp(t))

    /**
     * Signed distance from the hinge line, in scene units, for a point in scene space.
     *
     * **Positive means "on the cover display's half"**; negative means the half being
     * newly revealed. This is deliberately the same convention as the `coverSign` uniform
     * in `fold_transition.agsl`, so the Kotlin and GPU sides agree: two functions
     * computing the same quantity with opposite signs is a trap that only shows up as a
     * mirrored effect at runtime, long after the mistake was made.
     *
     * The shader uses the sign to apply the asymmetric, hinge-directed blur that research
     * §1.2(A) identified — strongest at the seam, falling off toward the outer edges —
     * and to make the revealed half cross over in the dissolve first.
     */
    fun signedHingeDistance(sceneX: Float, sceneY: Float): Float {
        val coord = if (hingeAxis == HingeAxis.VERTICAL) sceneX else sceneY
        val raw = coord - hingePosition
        return if (coverHalf == CoverHalf.RIGHT) raw else -raw
    }

    /**
     * The scene-space edge of the cover viewport that abuts the hinge.
     *
     * This is the edge Apple blurs ("the right side of the cover screen"), so it is the
     * anchor for the reveal gradient.
     */
    fun coverHingeEdge(): Float = when {
        hingeAxis == HingeAxis.VERTICAL && coverHalf == CoverHalf.RIGHT -> coverSceneRect.left
        hingeAxis == HingeAxis.VERTICAL -> coverSceneRect.right
        coverHalf == CoverHalf.RIGHT -> coverSceneRect.top
        else -> coverSceneRect.bottom
    }

    companion object {

        /**
         * Derive a mapping from the two displays' physical aspect ratios.
         *
         * The cover crop is chosen so that **scene content keeps its proportions**: we fit
         * the cover's aspect ratio inside the scene, anchored to the hinge-side half,
         * rather than stretching. That is the "proportional layout continuity" the brief
         * asks for in §14 — the cover is a *window* onto the scene, not a squashed copy
         * of it.
         *
         * @param coverAspect  cover display width / height
         * @param innerAspect  inner display width / height (unfolded)
         */
        fun fromAspectRatios(
            coverAspect: Float,
            innerAspect: Float,
            coverHalf: CoverHalf = CoverHalf.RIGHT,
            hingeAxis: HingeAxis = HingeAxis.VERTICAL,
            hingePosition: Float = 0.5f,
            /**
             * How much of the scene the cover viewport shows beyond a strict half.
             * 1.0 = exactly half. Slightly above 1 reads better because the cover
             * display is physically a touch wider than half the inner panel on the
             * Z Fold 7, and because a hair of overlap hides edge sampling artefacts.
             */
            coverage: Float = 1.06f,
        ): SceneMapping {
            val safeInner = if (innerAspect <= 0f) 1f else innerAspect
            val safeCover = if (coverAspect <= 0f) 1f else coverAspect

            // Width of the cover viewport in scene units, if it showed exactly one half.
            val halfWidth = if (hingeAxis == HingeAxis.VERTICAL) hingePosition else 1f - hingePosition

            // The cover panel is narrower (taller aspect) than half the inner panel, so it
            // shows a *narrower* slice of the scene at the same content scale. The ratio of
            // aspect ratios is exactly that difference.
            val halfAspect = safeInner * (if (hingeAxis == HingeAxis.VERTICAL) halfWidth else 1f)
            val shrink = Curves.clamp(safeCover / halfAspect, 0.35f, 1.4f)

            val w = Curves.clamp(halfWidth * shrink * coverage, 0.10f, 1f)
            val h = 1f

            return if (hingeAxis == HingeAxis.VERTICAL) {
                val rect = if (coverHalf == CoverHalf.RIGHT) {
                    SceneRect(left = 1f - w, top = 0f, right = 1f, bottom = h)
                } else {
                    SceneRect(left = 0f, top = 0f, right = w, bottom = h)
                }
                SceneMapping(rect, SceneRect.FULL, coverHalf, hingeAxis, hingePosition)
            } else {
                val rect = if (coverHalf == CoverHalf.RIGHT) {
                    SceneRect(left = 0f, top = 1f - w, right = 1f, bottom = 1f)
                } else {
                    SceneRect(left = 0f, top = 0f, right = 1f, bottom = w)
                }
                SceneMapping(rect, SceneRect.FULL, coverHalf, hingeAxis, hingePosition)
            }
        }

        /**
         * Measured defaults for the Galaxy Z Fold 7.
         *
         * Cover display 6.5" 2520x1080 (aspect ~0.4286), inner 8.0" 2184x1968
         * (aspect ~1.11 in landscape-natural orientation, ~0.90 held in portrait).
         * These are starting values — the calibration screen recomputes them from the
         * real `DisplayMetrics` of the device it is running on, so this constant only
         * matters before first calibration and in the virtual-hinge simulator.
         */
        val Z_FOLD_7_DEFAULT: SceneMapping = fromAspectRatios(
            coverAspect = 1080f / 2520f,
            innerAspect = 1968f / 2184f,
            coverHalf = CoverHalf.RIGHT,
            hingeAxis = HingeAxis.VERTICAL,
        )
    }
}
