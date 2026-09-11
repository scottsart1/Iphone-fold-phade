package dev.foldphase.sensors

import dev.foldphase.core.CoverHalf
import dev.foldphase.core.HingeAxis
import dev.foldphase.core.SceneMapping

/**
 * Learns the two physical panels' geometry by observing them, then derives the scene
 * mapping from measurement rather than from a hard-coded guess.
 *
 * ## Why this is not a constant
 *
 * `SceneMapping.Z_FOLD_7_DEFAULT` is built from published Z Fold 7 panel dimensions, but
 * what the app actually needs is the aspect ratio of the **window it is given** — which is
 * not the panel's raw resolution. It is affected by the system bars, the user's display
 * size and font scale settings, multi-window mode, and whatever One UI decides to do with
 * the cutout.
 *
 * Getting it wrong distorts the cover crop, which is the one thing that has to line up
 * across the panel handoff. So the app measures both panels the only way it reliably can:
 * by noting the window aspect each time it finds itself on a different display.
 *
 * ## Classification
 *
 * A panel is the inner display when its short/long aspect ratio exceeds
 * [INNER_ASPECT_THRESHOLD] — the inner panel is close to square, the cover panel is
 * extremely tall. This is unambiguous on a book foldable and does not rely on display ids,
 * which Samsung hardware reuses inconsistently.
 */
class DisplayProfile {

    /** Cover panel aspect (width / height) as last observed. NaN until seen. */
    var coverAspect: Float = Float.NaN
        private set

    /** Inner panel aspect (width / height) as last observed. NaN until seen. */
    var innerAspect: Float = Float.NaN
        private set

    val hasCover: Boolean get() = coverAspect.isFinite() && coverAspect > 0f
    val hasInner: Boolean get() = innerAspect.isFinite() && innerAspect > 0f

    /** True once both panels have been seen, so the derived mapping is fully grounded. */
    val isComplete: Boolean get() = hasCover && hasInner

    /**
     * Record a window's pixel size.
     *
     * @return true if this changed the profile enough to warrant rebuilding the mapping.
     */
    fun observe(widthPx: Int, heightPx: Int): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false

        val aspect = widthPx.toFloat() / heightPx.toFloat()
        val shortLong = minOf(widthPx, heightPx).toFloat() / maxOf(widthPx, heightPx).toFloat()
        val isInner = shortLong > INNER_ASPECT_THRESHOLD

        return if (isInner) {
            val changed = !hasInner || !nearlyEqual(innerAspect, aspect)
            innerAspect = aspect
            changed
        } else {
            val changed = !hasCover || !nearlyEqual(coverAspect, aspect)
            coverAspect = aspect
            changed
        }
    }

    /**
     * Build a scene mapping from what has been observed, falling back per-panel to the
     * Z Fold 7 defaults for anything not yet seen.
     *
     * Falling back per-panel rather than all-or-nothing matters: the app is on exactly one
     * panel at a time, so a mapping that needed both before it improved on the default
     * would only ever improve after the user's first fold — which is the fold it most
     * needs to get right.
     */
    fun deriveMapping(
        coverHalf: CoverHalf = CoverHalf.RIGHT,
        hingeAxis: HingeAxis = HingeAxis.VERTICAL,
        hingePosition: Float = 0.5f,
    ): SceneMapping = SceneMapping.fromAspectRatios(
        coverAspect = if (hasCover) coverAspect else FALLBACK_COVER_ASPECT,
        innerAspect = if (hasInner) innerAspect else FALLBACK_INNER_ASPECT,
        coverHalf = coverHalf,
        hingeAxis = hingeAxis,
        hingePosition = hingePosition,
    )

    fun seed(cover: Float, inner: Float) {
        if (cover.isFinite() && cover > 0f) coverAspect = cover
        if (inner.isFinite() && inner > 0f) innerAspect = inner
    }

    fun reset() {
        coverAspect = Float.NaN
        innerAspect = Float.NaN
    }

    private fun nearlyEqual(a: Float, b: Float) = kotlin.math.abs(a - b) < ASPECT_EPSILON

    companion object {
        /**
         * Short/long ratio above which a panel counts as the inner display.
         *
         * The Z Fold 7's inner panel is ~0.90 and its cover panel ~0.43, so 0.72 sits in
         * a wide gap between them and holds for other book foldables too.
         */
        const val INNER_ASPECT_THRESHOLD = 0.72f

        /** Z Fold 7 published dimensions, used only for a panel not yet observed. */
        const val FALLBACK_COVER_ASPECT = 1080f / 2520f
        const val FALLBACK_INNER_ASPECT = 1968f / 2184f

        /** Aspect changes below this are noise (rotation of a system bar, say). */
        private const val ASPECT_EPSILON = 0.01f
    }
}
