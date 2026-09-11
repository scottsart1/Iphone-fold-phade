package dev.foldphase.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.FoldController
import dev.foldphase.sensors.CalibrationSweepAnalyzer
import dev.foldphase.sensors.HingeCalibration
import kotlinx.coroutines.launch

private enum class WizardStep { INTRO, CLOSED, OPEN, SWEEP, REVIEW }

/**
 * The calibration wizard (brief §4).
 *
 * The brief is explicit that `0° = closed, 180° = open` must not be assumed, and the
 * research agrees — Samsung Fold hardware has been reported reading 178.5–181.5° when
 * flat, and the closed end is often not 0 either. So the wizard captures both extremes
 * from the device in front of it, then characterises the sensor over a slow sweep.
 *
 * Everything downstream runs on the normalised progress this produces, never on degrees.
 */
@Composable
fun CalibrationScreen(controller: FoldController, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val progress by controller.progress.collectAsStateWithLifecycle()
    val stored by controller.calibration.collectAsStateWithLifecycle()

    var step by remember { mutableStateOf(WizardStep.INTRO) }
    var closedAngle by remember { mutableFloatStateOf(Float.NaN) }
    var openAngle by remember { mutableFloatStateOf(Float.NaN) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val analyzer = remember { CalibrationSweepAnalyzer() }

    // Feed the sweep analyzer while, and only while, we are on the sweep step.
    DisposableEffect(step) {
        if (step == WizardStep.SWEEP) analyzer.reset()
        onDispose { }
    }
    if (step == WizardStep.SWEEP) {
        // Recomposition happens on every published sample, which is what we want here:
        // the sweep step is explicitly a data-collection screen, not an animation path.
        analyzer.add(progress.rawAngleDeg, progress.timestampNanos)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionCard("Live value") {
            Text("${progress.rawAngleDeg.fmt(2)}°", style = MaterialTheme.typography.headlineMedium)
            Readout("Filtered", "${progress.filteredAngleDeg.fmt(3)}°")
            Readout("Velocity", "${progress.velocityDegPerSec.fmt(2)}°/s")
        }

        when (step) {
            WizardStep.INTRO -> SectionCard("Calibration") {
                Text(
                    "This measures what your device's hinge sensor actually reports, " +
                        "rather than assuming 0° closed and 180° open. It takes about a " +
                        "minute.\n\n" +
                        "Three steps: capture closed, capture open, then one slow sweep " +
                        "so the app can measure noise, resolution and update rate.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (stored.isCalibrated) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Existing calibration", style = MaterialTheme.typography.titleSmall)
                    Readout("Closed", "${stored.closedAngleDeg.fmt(2)}°")
                    Readout("Open", "${stored.openAngleDeg.fmt(2)}°")
                    Readout("Span", "${stored.spanDeg.fmt(2)}°")
                }
                Button(
                    onClick = { step = WizardStep.CLOSED },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(if (stored.isCalibrated) "Re-calibrate" else "Start") }
            }

            WizardStep.CLOSED -> SectionCard("Step 1 of 3 — Closed") {
                Text(
                    "Close the phone as far as practical, then capture.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "\"As far as practical\" matters: most book foldables do not reach a " +
                        "true 0°, and forcing one would be the wrong reference anyway. " +
                        "Capture wherever it naturally rests shut.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        closedAngle = progress.rawAngleDeg
                        step = WizardStep.OPEN
                    }) { Text("Capture ${progress.rawAngleDeg.fmt(2)}°") }
                    OutlinedButton(onClick = { step = WizardStep.INTRO }) { Text("Back") }
                }
            }

            WizardStep.OPEN -> SectionCard("Step 2 of 3 — Open") {
                Text(
                    "Open the phone completely flat, then capture.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Readout("Captured closed", "${closedAngle.fmt(2)}°")
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        openAngle = progress.rawAngleDeg
                        step = WizardStep.SWEEP
                    }) { Text("Capture ${progress.rawAngleDeg.fmt(2)}°") }
                    OutlinedButton(onClick = { step = WizardStep.CLOSED }) { Text("Back") }
                }
            }

            WizardStep.SWEEP -> SectionCard("Step 3 of 3 — Slow sweep") {
                Text(
                    "Slowly open and close the phone once, taking a few seconds each way. " +
                        "Pause briefly at a couple of points so noise can be measured.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { (analyzer.sampleCount / 400f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Readout("Samples", analyzer.sampleCount.toString())
                Readout("Distinct values", analyzer.distinctValueCount().toString())
                Readout("Range seen", "${analyzer.minAngle.fmt(2)}° … ${analyzer.maxAngle.fmt(2)}°")
                Readout("Resolution", "${analyzer.observedResolution().fmt(3)}°")
                Readout("Median interval", "${analyzer.medianUpdateIntervalMs().fmt(2)} ms")
                Readout("Largest jump", "${analyzer.largestDiscontinuity().fmt(3)}°")
                Readout("Held noise (σ)", "${analyzer.heldNoiseStdDev().fmt(4)}°")

                Button(
                    onClick = { step = WizardStep.REVIEW },
                    enabled = analyzer.sampleCount >= MIN_SWEEP_SAMPLES,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        if (analyzer.sampleCount >= MIN_SWEEP_SAMPLES) {
                            "Finish"
                        } else {
                            "Keep sweeping (${analyzer.sampleCount}/$MIN_SWEEP_SAMPLES)"
                        },
                    )
                }
            }

            WizardStep.REVIEW -> {
                val candidate = HingeCalibration(
                    closedAngleDeg = minOf(closedAngle, analyzer.minAngle),
                    openAngleDeg = maxOf(openAngle, analyzer.maxAngle),
                    noiseStdDevDeg = analyzer.heldNoiseStdDev(),
                    observedResolutionDeg = analyzer.observedResolution(),
                    medianUpdateIntervalMs = analyzer.medianUpdateIntervalMs(),
                    largestDiscontinuityDeg = analyzer.largestDiscontinuity(),
                    measuredHandoffProgress = stored.measuredHandoffProgress,
                    isCalibrated = true,
                )

                SectionCard("Review") {
                    Text(
                        "Extremes are widened to include anything seen during the sweep, " +
                            "so a slightly conservative capture still yields the full range.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Readout("Closed", "${candidate.closedAngleDeg.fmt(2)}°")
                    Readout("Open", "${candidate.openAngleDeg.fmt(2)}°")
                    Readout("Span", "${candidate.spanDeg.fmt(2)}°")
                    Readout("Noise (σ)", "${candidate.noiseStdDevDeg.fmt(4)}°")
                    Readout("Resolution", "${candidate.observedResolutionDeg.fmt(3)}°")
                    Readout("Median interval", "${candidate.medianUpdateIntervalMs.fmt(2)} ms")
                    Readout("Largest jump", "${candidate.largestDiscontinuityDeg.fmt(3)}°")
                    Readout("Derived deadband", candidate.noiseDeadbandProgress().fmt(4))

                    if (candidate.spanDeg <= HingeCalibration.MIN_SPAN + 0.01f) {
                        Text(
                            "Span is implausibly small. The sensor may not have moved " +
                                "between captures — re-run before saving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Row(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {
                            scope.launch {
                                controller.saveCalibration(candidate)
                                saveMessage = "Saved."
                                step = WizardStep.INTRO
                            }
                        }) { Text("Save") }
                        OutlinedButton(onClick = { step = WizardStep.SWEEP }) { Text("Redo sweep") }
                    }
                }
            }
        }

        saveMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** Enough samples that the statistics mean something without being tedious to collect. */
private const val MIN_SWEEP_SAMPLES = 120
