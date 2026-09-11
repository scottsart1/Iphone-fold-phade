package dev.foldphase.app

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.foldphase.core.FoldProgress
import dev.foldphase.core.ProgressSourceKind
import dev.foldphase.core.SceneMapping
import dev.foldphase.diagnostics.HingeTrace
import dev.foldphase.engine.FoldAnimationEngine
import dev.foldphase.engine.FoldTuning
import dev.foldphase.engine.FoldTuningPresets
import dev.foldphase.engine.FoldVisualState
import dev.foldphase.renderer.FrameMetrics
import dev.foldphase.renderer.SceneTextureCache
import dev.foldphase.sensors.CalibrationStore
import dev.foldphase.sensors.FilterConfig
import dev.foldphase.sensors.FoldPipeline
import dev.foldphase.sensors.HingeAngleSensorSource
import dev.foldphase.sensors.HingeCalibration
import dev.foldphase.sensors.VirtualHingeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of the entire fold pipeline.
 *
 * ## Why application-scoped and not a ViewModel (brief §19)
 *
 * Folding a Fold device is a **major configuration change**: the activity may be
 * destroyed and recreated, on a different `Display`, in a different orientation, mid-fold.
 * A `ViewModel` survives rotation, but this state has to survive something stronger — it
 * has to survive across the *very event it is animating*, and it has to keep running
 * while no activity exists at all so the overlay path still works.
 *
 * So the pipeline, the engine, the handoff learner and the textures all live here, tied
 * to the `Application`. Activities attach and detach; none of them own anything.
 *
 * Per the brief's caution in §19, per-frame values are held **in memory only**. The only
 * things written to disk are genuine preferences — calibration and tuning — via DataStore.
 */
class FoldController(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val calibrationStore = CalibrationStore(app)
    val textureCache = SceneTextureCache(app)
    val trace = HingeTrace()
    val frameMetrics = FrameMetrics()

    val pipeline = FoldPipeline(scope, FilterConfig())
    val engine = FoldAnimationEngine(FoldTuningPresets.APPLE_LIKE, SceneMapping.Z_FOLD_7_DEFAULT)

    private val sensorSource by lazy { HingeAngleSensorSource(app) }
    val virtualSource = VirtualHingeSource()

    private val _tuning = MutableStateFlow(FoldTuningPresets.APPLE_LIKE)
    val tuning: StateFlow<FoldTuning> = _tuning.asStateFlow()

    private val _calibration = MutableStateFlow(HingeCalibration.UNCALIBRATED)
    val calibration: StateFlow<HingeCalibration> = _calibration.asStateFlow()

    private val _usingVirtualHinge = MutableStateFlow(false)
    val usingVirtualHinge: StateFlow<Boolean> = _usingVirtualHinge.asStateFlow()

    /**
     * The live visual state, as a Compose [MutableState] rather than a Flow.
     *
     * This is read inside draw lambdas so that a new frame invalidates only drawing.
     * A `StateFlow` collected with `collectAsState` would invalidate *composition* on
     * every frame instead, which at 120 Hz is exactly the cost brief §22 forbids.
     */
    val visualState: MutableState<FoldVisualState> = mutableStateOf(FoldVisualState.CLOSED)

    val progress: StateFlow<FoldProgress> get() = pipeline.progress
    val isAnimating: StateFlow<Boolean> get() = pipeline.isAnimating

    /** True if this device actually exposes a hinge angle sensor. */
    val hasHingeSensor: Boolean get() = sensorSource.isAvailable()

    /** Guards the handoff persist against re-writing on every frame. See [onSample]. */
    private var lastPersistedObservationCount = -1

    private val displayManager =
        app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    init {
        scope.launch {
            calibrationStore.calibration.collect { stored ->
                _calibration.value = stored
                pipeline.calibration = stored
                if (stored.hasMeasuredHandoff) {
                    pipeline.handoffCenterFallback = stored.measuredHandoffProgress
                }
            }
        }

        scope.launch { textureCache.loadPersisted() }

        // Translate every pipeline sample into a visual state and a trace row. This is
        // the single place the engine is driven from, so there is exactly one definition
        // of "what the screen should look like right now".
        scope.launch {
            pipeline.progress.collect { sample -> onSample(sample) }
        }
    }

    private fun onSample(sample: FoldProgress) {
        val center = pipeline.handoffLearner.estimate(
            opening = sample.velocityDegPerSec >= 0f,
        ) ?: _tuning.value.handoffCenterFallback

        visualState.value = engine.evaluate(sample, center)

        trace.add(
            timestampNanos = sample.timestampNanos,
            rawAngleDeg = sample.rawAngleDeg,
            filteredAngleDeg = sample.filteredAngleDeg,
            velocityDegPerSec = sample.velocityDegPerSec,
            progressValue = sample.progress,
            dir = sample.direction,
            foldState = sample.state,
        )
        frameMetrics.record(sample.timestampNanos)

        // Opportunistically persist a newly learned handoff point so the device gets
        // better at concealing the panel swap the more it is used.
        //
        // Gated on the observation count having actually *changed*. This runs at frame
        // rate, so an ungated write here would mean ~120 DataStore commits per second
        // during a fold — file I/O on the animation path, which is precisely what the
        // performance budget forbids. Observations only arrive on a display change, so in
        // practice this writes at most a handful of times in the app's lifetime.
        val observations = pipeline.handoffLearner.observationCount
        if (observations != lastPersistedObservationCount &&
            observations in 1..MAX_PERSISTED_OBS
        ) {
            lastPersistedObservationCount = observations
            val learned = pipeline.handoffLearner.combinedEstimate()
            if (learned != null) {
                scope.launch { calibrationStore.saveHandoffProgress(learned) }
            }
        }
    }

    /** Attach the real hinge sensor, or the simulator if there is no hinge. */
    fun useRealSensor() {
        _usingVirtualHinge.value = false
        pipeline.resetFilters()
        if (sensorSource.isAvailable()) {
            pipeline.attach(sensorSource)
        } else {
            pipeline.attach(virtualSource)
        }
    }

    /** Switch to the virtual hinge (brief §27). Identical code path below this point. */
    fun useVirtualHinge() {
        _usingVirtualHinge.value = true
        pipeline.resetFilters()
        pipeline.attach(virtualSource)
    }

    /** Drive the virtual hinge from a slider, in normalised progress. */
    fun setVirtualProgress(p: Float) {
        virtualSource.emit(_calibration.value.angleFor(p))
    }

    /** Drive the virtual hinge in raw degrees. */
    fun setVirtualAngle(deg: Float) {
        virtualSource.emit(deg)
    }

    fun updateTuning(new: FoldTuning) {
        _tuning.value = new
        engine.tuning = new
        pipeline.handoffHalfWidth = new.handoffHalfWidth
        if (!_calibration.value.hasMeasuredHandoff) {
            pipeline.handoffCenterFallback = new.handoffCenterFallback
        }
    }

    fun updateFilterConfig(new: FilterConfig) {
        pipeline.config = new
    }

    fun updateSceneMapping(mapping: SceneMapping) {
        engine.sceneMapping = mapping
    }

    suspend fun saveCalibration(c: HingeCalibration) {
        calibrationStore.save(c)
    }

    /**
     * Report which display the window is on, so the handoff learner can measure the real
     * panel-swap point (see `HandoffLearner` for why this matters so much).
     */
    fun reportDisplay(display: Display?) {
        val d = display ?: return
        // The inner panel is the larger one. Comparing against the default display id is
        // not reliable on Samsung hardware — both panels can present as the default at
        // different times — so classify by size, which is unambiguous.
        val isInner = isInnerDisplay(d)
        pipeline.reportDisplay(d.displayId, isInner)
        frameMetrics.setRefreshRate(d.refreshRate)
    }

    private fun isInnerDisplay(display: Display): Boolean {
        val all = displayManager.getDisplays(null) ?: return true
        if (all.size <= 1) {
            // Only one display exposed: decide by aspect ratio. The inner panel is close
            // to square; the cover panel is extremely tall.
            val metrics = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(metrics)
            if (metrics.x == 0 || metrics.y == 0) return true
            val aspect = minOf(metrics.x, metrics.y).toFloat() / maxOf(metrics.x, metrics.y)
            return aspect > INNER_ASPECT_THRESHOLD
        }
        val areaOf = { d: Display ->
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            d.getRealSize(p)
            p.x.toLong() * p.y.toLong()
        }
        val largest = all.maxByOrNull(areaOf) ?: return true
        return largest.displayId == display.displayId
    }

    /** Recompute the scene mapping from the running display's real metrics. */
    fun deriveSceneMappingFrom(coverAspect: Float, innerAspect: Float) {
        engine.sceneMapping = SceneMapping.fromAspectRatios(
            coverAspect = coverAspect,
            innerAspect = innerAspect,
        )
    }

    fun currentSourceKind(): ProgressSourceKind =
        if (_usingVirtualHinge.value) ProgressSourceKind.SIMULATOR else ProgressSourceKind.SENSOR

    fun shutdown() {
        pipeline.detach()
    }

    private companion object {
        /**
         * Stop rewriting the stored handoff point once we have a solid median. Beyond a
         * handful of observations the estimate stops improving and the writes are just
         * disk churn during every fold.
         */
        const val MAX_PERSISTED_OBS = 8

        /** Aspect (short/long) above which a panel is "squarish", i.e. the inner display. */
        const val INNER_ASPECT_THRESHOLD = 0.72f
    }
}

/** Application holding the single [FoldController]. */
class FoldPhaseApplication : Application() {
    val controller: FoldController by lazy { FoldController(this) }
}

/** Convenience accessor. */
val Context.foldController: FoldController
    get() = (applicationContext as FoldPhaseApplication).controller
