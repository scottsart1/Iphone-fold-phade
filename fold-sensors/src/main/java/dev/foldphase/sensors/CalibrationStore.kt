package dev.foldphase.sensors

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibrationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hinge_calibration")

/**
 * Persists [HingeCalibration] locally (brief §4, §26 — everything stays on device).
 *
 * DataStore rather than SharedPreferences because the read is a Flow, so the pipeline
 * picks up a re-calibration without anyone having to remember to re-read it, and because
 * writes are transactional and cannot tear.
 *
 * Note the deliberate split from frame state: only *preferences* live here. Per-frame
 * values are never written to disk (brief §19).
 */
class CalibrationStore(private val context: Context) {

    val calibration: Flow<HingeCalibration> = context.calibrationDataStore.data.map { prefs ->
        HingeCalibration(
            closedAngleDeg = prefs[KEY_CLOSED] ?: HingeCalibration.DEFAULT_CLOSED,
            openAngleDeg = prefs[KEY_OPEN] ?: HingeCalibration.DEFAULT_OPEN,
            noiseStdDevDeg = prefs[KEY_NOISE] ?: 0f,
            observedResolutionDeg = prefs[KEY_RESOLUTION] ?: 0f,
            medianUpdateIntervalMs = prefs[KEY_INTERVAL] ?: 0f,
            largestDiscontinuityDeg = prefs[KEY_DISCONTINUITY] ?: 0f,
            measuredHandoffProgress = prefs[KEY_HANDOFF] ?: Float.NaN,
            isCalibrated = prefs[KEY_CALIBRATED] ?: false,
        )
    }

    suspend fun save(calibration: HingeCalibration) {
        context.calibrationDataStore.edit { prefs ->
            prefs[KEY_CLOSED] = calibration.closedAngleDeg
            prefs[KEY_OPEN] = calibration.openAngleDeg
            prefs[KEY_NOISE] = calibration.noiseStdDevDeg
            prefs[KEY_RESOLUTION] = calibration.observedResolutionDeg
            prefs[KEY_INTERVAL] = calibration.medianUpdateIntervalMs
            prefs[KEY_DISCONTINUITY] = calibration.largestDiscontinuityDeg
            prefs[KEY_CALIBRATED] = calibration.isCalibrated
            if (!calibration.measuredHandoffProgress.isNaN()) {
                prefs[KEY_HANDOFF] = calibration.measuredHandoffProgress
            }
        }
    }

    /**
     * Store only the learned handoff point, leaving the rest of the calibration alone.
     *
     * Called opportunistically as the handoff learner accumulates observations, so the
     * device gets better at concealing the panel swap the more it is used — without the
     * user ever re-running the wizard.
     */
    suspend fun saveHandoffProgress(progress: Float) {
        if (progress.isNaN()) return
        context.calibrationDataStore.edit { prefs -> prefs[KEY_HANDOFF] = progress }
    }

    suspend fun clear() {
        context.calibrationDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_CLOSED = floatPreferencesKey("closed_angle")
        val KEY_OPEN = floatPreferencesKey("open_angle")
        val KEY_NOISE = floatPreferencesKey("noise_std_dev")
        val KEY_RESOLUTION = floatPreferencesKey("observed_resolution")
        val KEY_INTERVAL = floatPreferencesKey("median_interval_ms")
        val KEY_DISCONTINUITY = floatPreferencesKey("largest_discontinuity")
        val KEY_HANDOFF = floatPreferencesKey("measured_handoff_progress")
        val KEY_CALIBRATED = booleanPreferencesKey("is_calibrated")
    }
}
