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
import dev.foldphase.renderer.AdaptiveQuality
import dev.foldphase.renderer.FrameMetrics
import dev.foldphase.renderer.SceneTextureCache
import dev.foldphase.sensors.AutoCalibrator
import dev.foldphase.sensors.CalibrationStore
import dev.foldphase.sensors.DualAccelHingeSource
import dev.foldphase.sensors.DisplayProfile
import dev.foldphase.sensors.FilterConfig
import dev.foldphase.sensors.FoldPipeline
import dev.foldphase.sensors.HingeAngleSensorSource
import dev.foldphase.sensors.HingeCalibration
import dev.foldphase.sensors.ProbeStats
import dev.foldphase.sensors.SensorProbe
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

    /**
     * Learns the hinge range from ordinary use, so the app is usable before — or without —
     * the calibration wizard. An explicit wizard calibration always wins; see
     * [AutoCalibrator].
     */
    val autoCalibrator = AutoCalibrator()

    /** Learns both panels' real window aspects, so the scene mapping is measured. */
    val displayProfile = DisplayProfile()

    /** Steps shader quality down if the device cannot hold its frame budget. */
    val adaptiveQuality = AdaptiveQuality()

    /**
     * Watches every candidate hinge sensor so the usable one can be chosen by evidence.
     *
     * Necessary because the Fold 7's standard hinge sensor declares a resolution that, if
     * accurate, makes it unusable for a scrubbed animation — and the device exposes
     * several vendor alternatives. See [SensorProbe].
     */
    val sensorProbe = SensorProbe(app)

    /** The sensor currently driving the pipeline. Null means the platform default. */
    private var selectedSensor: android.hardware.Sensor? = null

    /**
     * Continuous hinge angle derived from the two per-half accelerometers.
     *
     * The fallback for devices whose `TYPE_HINGE_ANGLE` is quantised — which the Galaxy
     * Z Fold 7 measurably is, reporting only 0/90/180. See [DualAccelHingeSource].
     */
    val dualAccelSource by lazy { DualAccelHingeSource(app) }

    private val _usingDualAccel = MutableStateFlow(false)
    val usingDualAccel: StateFlow<Boolean> = _usingDualAccel.asStateFlow()

    private val _tuning = MutableStateFlow(FoldTuningPresets.APPLE_LIKE)
    val tuning: StateFlow<FoldTuning> = _tuning.asStateFlow()

    private val _calibration = MutableStateFlow(HingeCalibration.UNCALIBRATED)
    val calibration: StateFlow<HingeCalibration> = _calibration.asStateFlow()

    private val _usingVirtualHinge = MutableStateFlow(false)
    val usingVirtualHinge: StateFlow<Boolean> = _usingVirtualHinge.asStateFlow()

    /**
     * The calibration actually in force: the stored one if the wizard has been run,
     * otherwise the provisional range inferred from observation.
     */
    private val _effectiveCalibration = MutableStateFlow(HingeCalibration.UNCALIBRATED)
    val effectiveCalibration: StateFlow<HingeCalibration> = _effectiveCalibration.asStateFlow()

    /** Shader quality currently in use, after any automatic step-down. */
    private val _activeQuality = MutableStateFlow(adaptiveQuality.current)
    val activeQuality: StateFlow<dev.foldphase.engine.ShaderQuality> = _activeQuality.asStateFlow()

    /** Which render path the surface resolved to, for the diagnostics screen. */
    private val _renderPath = MutableStateFlow<String?>(null)
    val renderPath: StateFlow<String?> = _renderPath.asStateFlow()

    /** Name of the sensor driving the pipeline, for diagnostics. */
    private val _activeSensorName = MutableStateFlow<String?>(null)
    val activeSensorName: StateFlow<String?> = _activeSensorName.asStateFlow()

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
                // Seed the auto-calibrator from whatever range was last persisted, so a
                // fresh process is not blind until the user happens to fold fully.
                if (!stored.isCalibrated) {
                    autoCalibrator.seed(stored.closedAngleDeg, stored.openAngleDeg)
                }
                applyEffectiveCalibration(stored)
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
        // Widen the provisional range from what the sensor actually reports. Only a real
        // widening past the trust threshold triggers the (rare) recompute and write.
        if (autoCalibrator.observe(sample.rawAngleDeg) && !_calibration.value.isCalibrated) {
            applyEffectiveCalibration(_calibration.value)
            scope.launch {
                calibrationStore.saveProvisionalRange(
                    autoCalibrator.observedMin,
                    autoCalibrator.observedMax,
                )
            }
        }

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
        // Only record while the frame loop is genuinely running. The pipeline stops when
        // the hinge is still and restarts when it moves, so recording unconditionally
        // measured the idle gaps between transitions and reported them as frame times —
        // which is how a stationary device came back claiming 24 ms average frames.
        if (pipeline.isAnimating.value && sample.state.isMoving) {
            frameMetrics.record(sample.timestampNanos)
        } else {
            frameMetrics.markIdle()
        }

        // Judge frame health only occasionally: snapshot() sorts the ring buffer, which is
        // far too expensive to do on every frame of the thing it is measuring.
        if (frameCheckCountdown-- <= 0) {
            frameCheckCountdown = FRAME_CHECK_INTERVAL
            val chosen = adaptiveQuality.update(frameMetrics.snapshot())
            if (chosen != _activeQuality.value) _activeQuality.value = chosen
        }

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

    /**
     * Recompute which calibration is in force and push it into the pipeline.
     *
     * A wizard calibration is used verbatim. Otherwise the provisional range is used, but
     * still reported with `isCalibrated = false` so the UI keeps saying so.
     */
    private fun applyEffectiveCalibration(stored: HingeCalibration) {
        val effective = autoCalibrator.refine(stored)
        _effectiveCalibration.value = effective
        pipeline.calibration = effective
    }

    /** Record the render path the surface resolved to, for diagnostics. */
    fun reportRenderPath(path: String, detail: String?) {
        _renderPath.value = if (detail.isNullOrBlank()) path else "$path — $detail"
    }

    /** Manual quality override from the tuning panel; disables automatic stepping. */
    fun setShaderQuality(quality: dev.foldphase.engine.ShaderQuality) {
        adaptiveQuality.set(quality)
        _activeQuality.value = quality
        updateTuning(_tuning.value.copy(shaderQuality = quality))
    }

    /** Attach the real hinge sensor, or the simulator if there is no hinge. */
    fun useRealSensor() {
        _usingVirtualHinge.value = false
        pipeline.resetFilters()

        if (_usingDualAccel.value && dualAccelSource.isAvailable()) {
            pipeline.attach(dualAccelSource)
            _activeSensorName.value = "Dual accelerometer fusion"
            return
        }

        val source = currentSensorSource()
        if (source != null && source.isAvailable()) {
            pipeline.attach(source)
            _activeSensorName.value = source.sensor?.name
        } else {
            pipeline.attach(virtualSource)
            _activeSensorName.value = null
        }
    }

    /**
     * Switch between the hardware hinge sensor and the dual-accelerometer fusion.
     *
     * Clears calibration on the way, because the fusion reports a derived dihedral angle
     * whose zero point and direction need not match the hardware sensor's.
     */
    fun useDualAccel(enabled: Boolean) {
        _usingDualAccel.value = enabled
        _usingVirtualHinge.value = false
        autoCalibrator.reset()
        autoCalibrator.configureForRange(180f)
        scope.launch { calibrationStore.clear() }
        pipeline.resetFilters()
        useRealSensor()
    }

    private var activeSensorSource: HingeAngleSensorSource? = null

    private fun currentSensorSource(): HingeAngleSensorSource? {
        val chosen = selectedSensor
        if (chosen == null) {
            activeSensorSource = sensorSource
            return sensorSource
        }
        val existing = activeSensorSource
        if (existing != null && existing.sensor?.type == chosen.type) return existing
        return HingeAngleSensorSource(app, chosen).also { activeSensorSource = it }
    }

    /**
     * Switch which sensor drives the pipeline.
     *
     * Re-scales the auto-calibrator's guard rails to the new sensor's declared range,
     * because a vendor sensor may report a normalised `0 … 1` rather than degrees, and
     * clears the learned range — a calibration in one unit is meaningless in another.
     */
    fun selectSensor(sensor: android.hardware.Sensor?) {
        selectedSensor = sensor
        _usingDualAccel.value = false
        autoCalibrator.reset()
        sensor?.let { autoCalibrator.configureForRange(it.maximumRange) }
        scope.launch { calibrationStore.clear() }
        pipeline.resetFilters()
        useRealSensor()
    }

    /** The sensor the pipeline is reading, or null when on the simulator. */
    fun currentSensor(): android.hardware.Sensor? = activeSensorSource?.sensor

    /** Adopt the probe's best-evidenced continuous candidate, if there is one. */
    fun adoptBestProbedSensor(): ProbeStats? {
        val best = sensorProbe.bestCandidate() ?: return null
        val sensor = sensorProbe.candidates.firstOrNull { it.type == best.sensorType }
            ?: return null
        selectSensor(sensor)
        return best
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

        // Feed the profile from the panel's real size, so the scene mapping comes from
        // this device's actual geometry rather than published Z Fold 7 numbers.
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        d.getRealSize(size)
        if (displayProfile.observe(size.x, size.y)) {
            engine.sceneMapping = displayProfile.deriveMapping(
                coverHalf = engine.sceneMapping.coverHalf,
                hingeAxis = engine.sceneMapping.hingeAxis,
                hingePosition = engine.sceneMapping.hingePosition,
            )
        }

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

    /** Override the observed panel aspects, then rebuild the mapping from them. */
    fun deriveSceneMappingFrom(coverAspect: Float, innerAspect: Float) {
        displayProfile.seed(coverAspect, innerAspect)
        engine.sceneMapping = displayProfile.deriveMapping(
            coverHalf = engine.sceneMapping.coverHalf,
            hingeAxis = engine.sceneMapping.hingeAxis,
            hingePosition = engine.sceneMapping.hingePosition,
        )
    }

    /**
     * Flip which physical half the cover display sits behind.
     *
     * This is a property of the chassis that no API reports, so if the effect looks
     * mirrored on a given device this is the fix — exposed in the tuning panel rather than
     * requiring a rebuild.
     */
    fun setCoverHalf(half: dev.foldphase.core.CoverHalf) {
        engine.sceneMapping = engine.sceneMapping.copy(coverHalf = half)
    }

    fun currentSourceKind(): ProgressSourceKind =
        if (_usingVirtualHinge.value) ProgressSourceKind.SIMULATOR else ProgressSourceKind.SENSOR

    fun shutdown() {
        pipeline.detach()
    }

    private var frameCheckCountdown = FRAME_CHECK_INTERVAL

    private companion object {
        /** Frames between adaptive-quality evaluations. ~1 s at 120 Hz. */
        const val FRAME_CHECK_INTERVAL = 120

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
