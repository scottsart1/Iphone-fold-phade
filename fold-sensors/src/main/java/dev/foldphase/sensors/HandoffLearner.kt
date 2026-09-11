package dev.foldphase.sensors

import dev.foldphase.core.ActiveDisplay
import dev.foldphase.core.Curves

/**
 * Learns, by observation, the fold progress at which One UI swaps physical panels.
 *
 * ## Why this is the single most valuable calibration in the project
 *
 * The brief (§9 Phase D, §33) identifies the panel handoff as the moment the effect
 * either feels magical or fake, and guesses that it happens somewhere around
 * `p = 0.48–0.68`. But the switch point is a One UI decision that no app can configure or
 * query, and it varies by device, by One UI version, and with the "Continue apps on cover
 * screen" setting (research §3.4).
 *
 * Centring a concealment window on a *guess* means it will be off by some margin on any
 * given device, and being off is exactly what produces a visible flash.
 *
 * So we do not guess. Every time the app observes its own window move between display ids
 * we record the progress at that instant, and we centre the concealment window on the
 * running median of those observations. After two or three folds, the window is centred
 * on the truth for *this* phone.
 *
 * A median rather than a mean: the first observation after boot is often late (the app
 * may not be resumed yet), and a median discards those outliers instead of being dragged
 * by them.
 */
class HandoffLearner(private val capacity: Int = 16) {

    private val openingObservations = ArrayDeque<Float>()
    private val closingObservations = ArrayDeque<Float>()
    private var lastDisplayId: Int = INVALID_DISPLAY
    private var lastActive: ActiveDisplay = ActiveDisplay.UNKNOWN

    /**
     * Record the current display and progress. Returns a newly observed handoff progress,
     * or null if nothing changed.
     *
     * @param displayId  the id of the `Display` the window is currently on
     * @param isInner    whether that display is the inner panel
     * @param progress   normalised fold progress at this instant
     */
    fun observe(displayId: Int, isInner: Boolean, progress: Float): Float? {
        val active = if (isInner) ActiveDisplay.INNER else ActiveDisplay.COVER

        val changed = lastDisplayId != INVALID_DISPLAY &&
            (displayId != lastDisplayId || active != lastActive)

        val previousActive = lastActive
        lastDisplayId = displayId
        lastActive = active

        if (!changed) return null

        // Only record plausible mid-fold transitions. A switch recorded at p≈0 or p≈1 is
        // the app being launched or the screen waking, not the hinge crossing the point.
        if (progress < MIN_PLAUSIBLE || progress > MAX_PLAUSIBLE) return null

        val target = when {
            previousActive == ActiveDisplay.COVER && active == ActiveDisplay.INNER -> openingObservations
            previousActive == ActiveDisplay.INNER && active == ActiveDisplay.COVER -> closingObservations
            else -> return null
        }
        target.addLast(progress)
        while (target.size > capacity) target.removeFirst()
        return progress
    }

    /**
     * Best estimate of the handoff progress, or null if nothing has been observed yet.
     *
     * Opening and closing switch points can differ (the system applies its own hysteresis),
     * so they are tracked separately and the caller passes the direction it cares about.
     */
    fun estimate(opening: Boolean): Float? {
        val list = if (opening) openingObservations else closingObservations
        if (list.isEmpty()) {
            // Fall back to the other direction rather than returning nothing: one
            // observation in either direction beats a hard-coded guess.
            val other = if (opening) closingObservations else openingObservations
            if (other.isEmpty()) return null
            return median(other)
        }
        return median(list)
    }

    /** Combined estimate across both directions, for persistence. */
    fun combinedEstimate(): Float? {
        val all = openingObservations + closingObservations
        if (all.isEmpty()) return null
        return median(all)
    }

    val observationCount: Int get() = openingObservations.size + closingObservations.size

    fun reset() {
        openingObservations.clear()
        closingObservations.clear()
        lastDisplayId = INVALID_DISPLAY
        lastActive = ActiveDisplay.UNKNOWN
    }

    /** Seed from stored calibration so a fresh process is not blind on its first fold. */
    fun seed(progress: Float) {
        if (progress.isNaN() || progress < MIN_PLAUSIBLE || progress > MAX_PLAUSIBLE) return
        openingObservations.addLast(progress)
        closingObservations.addLast(progress)
    }

    private fun median(values: Collection<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val m = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) * 0.5f
        }
        return Curves.clamp(m, MIN_PLAUSIBLE, MAX_PLAUSIBLE)
    }

    private companion object {
        const val INVALID_DISPLAY = -1
        const val MIN_PLAUSIBLE = 0.05f
        const val MAX_PLAUSIBLE = 0.95f
    }
}
