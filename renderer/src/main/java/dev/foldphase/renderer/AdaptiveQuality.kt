package dev.foldphase.renderer

import dev.foldphase.engine.ShaderQuality

/**
 * Drops shader quality when the device cannot hold its frame budget, and only then.
 *
 * ## Why this is automatic
 *
 * Tap count is the dominant cost of the effect, and the right value depends on the GPU,
 * the panel's refresh rate and whatever else the system is doing. The tuning panel exposes
 * it, but that assumes someone is watching the P99 readout while folding — and the whole
 * point of shipping a considered default is that most people will not.
 *
 * So the renderer measures itself and steps down when it is genuinely missing frames.
 *
 * ## Why it never steps back up
 *
 * Oscillating between quality levels would be far more visible than simply running at the
 * lower one: the blur radius would visibly pulse as the level changed mid-fold. A ratchet
 * that only descends is stable, and the cost of being one level too conservative is a
 * slightly softer blur that nobody can see. The cost of oscillating is obvious.
 *
 * The user can always override from the tuning panel, and [reset] restores automatic
 * behaviour.
 */
class AdaptiveQuality(
    initial: ShaderQuality = ShaderQuality.HIGH,
    /** Multiple of the frame budget that counts as missing. */
    private val overBudgetFactor: Float = 1.25f,
    /** Consecutive over-budget windows before stepping down. */
    private val strikesBeforeDrop: Int = 3,
    /** Frames each window must contain before it is judged. */
    private val minSamplesPerWindow: Int = 60,
) {
    var current: ShaderQuality = initial
        private set

    /** True once the ratchet has fired, so the UI can say the level was chosen for them. */
    var hasStepped: Boolean = false
        private set

    private var strikes = 0

    /**
     * Feed a metrics snapshot. Returns the quality to use — unchanged unless it just
     * stepped down.
     *
     * Judged on **P95** rather than the average: an average comfortably inside budget can
     * still hide one late frame in twenty, and that is precisely the stutter this is meant
     * to catch.
     */
    fun update(stats: FrameStats): ShaderQuality {
        if (stats.sampleCount < minSamplesPerWindow) return current
        if (stats.expectedHz <= 0f) return current

        val budgetMs = 1000f / stats.expectedHz
        val overBudget = stats.p95Ms > budgetMs * overBudgetFactor

        if (!overBudget) {
            // One good window clears the count. Transient hitches from another app
            // scrolling past should not accumulate toward a permanent downgrade.
            strikes = 0
            return current
        }

        strikes++
        if (strikes < strikesBeforeDrop) return current

        strikes = 0
        val next = stepDown(current)
        if (next != current) {
            current = next
            hasStepped = true
        }
        return current
    }

    /** Manual override from the tuning panel; disables further automatic stepping. */
    fun set(quality: ShaderQuality) {
        current = quality
        strikes = 0
        hasStepped = false
    }

    fun reset(to: ShaderQuality = ShaderQuality.HIGH) {
        current = to
        strikes = 0
        hasStepped = false
    }

    private fun stepDown(q: ShaderQuality): ShaderQuality = when (q) {
        ShaderQuality.HIGH -> ShaderQuality.MEDIUM
        ShaderQuality.MEDIUM -> ShaderQuality.LOW
        ShaderQuality.LOW -> ShaderQuality.LOW
    }
}
