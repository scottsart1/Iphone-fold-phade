package dev.foldphase.sensors

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.foldphase.core.CoverHalf
import dev.foldphase.core.HingeAxis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Geometry of the fold, as reported by Jetpack WindowManager.
 *
 * Brief §20 is right that `FoldingFeature` must be used *alongside* the sensor rather
 * than instead of it: it gives authoritative **geometry** (where the fold line actually
 * is on screen, which way it runs, whether the panel is separated) but only **discrete**
 * state — `FLAT` or `HALF_OPENED` — never continuous degrees.
 *
 * So: sensor for the timeline, `FoldingFeature` for the stage.
 */
data class FoldGeometry(
    val state: FoldingFeature.State? = null,
    val orientation: FoldingFeature.Orientation? = null,
    val isSeparating: Boolean = false,
    val occlusionIsFull: Boolean = false,
    /** Fold line position as a fraction of the window's width (vertical) or height. */
    val hingePositionFraction: Float = 0.5f,
    /** Thickness of the fold feature in pixels; 0 on a seamless panel like the Fold 7. */
    val hingeThicknessPx: Int = 0,
    val hasFoldingFeature: Boolean = false,
) {
    val hingeAxis: HingeAxis
        get() = if (orientation == FoldingFeature.Orientation.HORIZONTAL) {
            // FoldingFeature.Orientation.HORIZONTAL means the fold line runs horizontally,
            // which splits the scene top/bottom.
            HingeAxis.HORIZONTAL
        } else {
            HingeAxis.VERTICAL
        }

    val isFlat: Boolean get() = state == FoldingFeature.State.FLAT
    val isHalfOpened: Boolean get() = state == FoldingFeature.State.HALF_OPENED
}

/**
 * Observes [WindowLayoutInfo] for an activity and reduces it to [FoldGeometry].
 */
class FoldingFeatureMonitor(private val activity: Activity) {

    val geometry: Flow<FoldGeometry> =
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { info -> reduce(info) }

    private fun reduce(info: WindowLayoutInfo): FoldGeometry {
        val feature = info.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull()
            ?: return FoldGeometry(hasFoldingFeature = false)

        val bounds = feature.bounds
        val windowWidth = activity.window?.decorView?.width ?: 0
        val windowHeight = activity.window?.decorView?.height ?: 0

        val vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
        val fraction = if (vertical) {
            if (windowWidth > 0) bounds.centerX().toFloat() / windowWidth else 0.5f
        } else {
            if (windowHeight > 0) bounds.centerY().toFloat() / windowHeight else 0.5f
        }

        val thickness = if (vertical) bounds.width() else bounds.height()

        return FoldGeometry(
            state = feature.state,
            orientation = feature.orientation,
            isSeparating = feature.isSeparating,
            occlusionIsFull = feature.occlusionType == FoldingFeature.OcclusionType.FULL,
            hingePositionFraction = fraction.coerceIn(0.05f, 0.95f),
            hingeThicknessPx = thickness,
            hasFoldingFeature = true,
        )
    }

    companion object {
        /**
         * Which half the cover display sits behind cannot be derived from
         * `FoldingFeature` — it is a physical property of the chassis. The Z Fold 7's
         * cover display is on the back of the half you hold in your right hand when the
         * device is open, hence RIGHT. Exposed in the dev panel so it can be flipped if
         * this is wrong for a given device or grip.
         */
        val DEFAULT_COVER_HALF = CoverHalf.RIGHT
    }
}
