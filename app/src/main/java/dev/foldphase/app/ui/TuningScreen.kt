package dev.foldphase.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.FoldController
import dev.foldphase.core.CoverHalf
import dev.foldphase.engine.FoldTuningPresets
import dev.foldphase.engine.ShaderQuality
import dev.foldphase.sensors.FilterConfig

/**
 * The developer control panel (brief §21).
 *
 * Every slider here mutates the shared [dev.foldphase.engine.FoldTuning] object that the
 * engine evaluates against, so a change takes effect on the very next frame with no
 * recompile and no restart. That is the point: tuning this effect is an iterative visual
 * process, and a tuning loop that costs a build is a tuning loop nobody completes.
 *
 * The live readouts sit at the top so the numbers are visible while the sliders below are
 * being dragged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuningScreen(controller: FoldController, modifier: Modifier = Modifier) {
    val tuning by controller.tuning.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    val usingVirtual by controller.usingVirtualHinge.collectAsStateWithLifecycle()
    val calibration by controller.effectiveCalibration.collectAsStateWithLifecycle()
    val activeQuality by controller.activeQuality.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(FilterConfig()) }
    var presetName by remember { mutableStateOf("Apple-like (default)") }

    fun pushFilter(update: FilterConfig.() -> Unit) {
        filter = filter.copy().apply(update)
        controller.updateFilterConfig(filter)
    }

    LazyColumn(modifier.fillMaxSize()) {

        item {
            SectionCard("Live") {
                Readout("Raw angle", "${progress.rawAngleDeg.fmt(2)}°")
                Readout("Filtered angle", "${progress.filteredAngleDeg.fmt(2)}°")
                Readout("Velocity", "${progress.velocityDegPerSec.fmt(1)}°/s")
                Readout("Normalized progress", progress.progress.fmt(4))
                Readout("Direction", progress.direction.name)
                Readout("Fold state", progress.state.name)
                Readout("Active display", progress.activeDisplay.name)
                val stats = controller.frameMetrics.snapshot()
                Readout("FPS", stats.effectiveHz.fmt(1))
                Readout("Frame time", "${stats.averageMs.fmt(2)} ms (P99 ${stats.p99Ms.fmt(2)})")
                Readout(
                    "Handoff centre",
                    if (calibration.hasMeasuredHandoff) {
                        "${calibration.measuredHandoffProgress.fmt(3)} (measured)"
                    } else {
                        "${tuning.handoffCenterFallback.fmt(3)} (fallback)"
                    },
                )
            }
        }

        item {
            SectionCard("Presets") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FoldTuningPresets.ALL.forEach { (name, preset) ->
                        FilterChip(
                            selected = presetName == name,
                            onClick = {
                                presetName = name
                                controller.updateTuning(preset)
                            },
                            label = { Text(name) },
                        )
                    }
                }
                Text(
                    "\"Apple-like\" is derived from docs/RESEARCH.md. \"Safe handoff\" " +
                        "trades the see-through quality for a guaranteed-invisible panel " +
                        "swap if your device flashes.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            SectionCard("Phase boundaries (normalized progress)") {
                TuningSlider("Animation start (deadband end)", tuning.deadbandEnd, {
                    controller.updateTuning(tuning.copy(deadbandEnd = it))
                }, 0f..0.3f)
                TuningSlider("Outer fade start", tuning.outerFadeStart, {
                    controller.updateTuning(tuning.copy(outerFadeStart = it))
                }, 0f..0.6f)
                TuningSlider("Outer fade end", tuning.outerFadeEnd, {
                    controller.updateTuning(tuning.copy(outerFadeEnd = it))
                }, 0.2f..1f)
                TuningSlider("Inner fade start", tuning.innerFadeStart, {
                    controller.updateTuning(tuning.copy(innerFadeStart = it))
                }, 0f..1f)
                TuningSlider("Inner fade end", tuning.innerFadeEnd, {
                    controller.updateTuning(tuning.copy(innerFadeEnd = it))
                }, 0.3f..1f)
                TuningSlider("Transition overlap", tuning.transitionOverlap, {
                    controller.updateTuning(tuning.copy(transitionOverlap = it))
                }, 0.01f..0.4f)
            }
        }

        item {
            SectionCard("Handoff concealment") {
                TuningSlider("Handoff centre (fallback)", tuning.handoffCenterFallback, {
                    controller.updateTuning(tuning.copy(handoffCenterFallback = it))
                }, 0.1f..0.9f)
                TuningSlider("Handoff half-width", tuning.handoffHalfWidth, {
                    controller.updateTuning(tuning.copy(handoffHalfWidth = it))
                }, 0.02f..0.3f)
                TuningSlider("Black handoff opacity", tuning.blackHandoffPeak, {
                    controller.updateTuning(tuning.copy(blackHandoffPeak = it))
                }, 0f..1f)
                Text(
                    "The fallback centre is only used until the app has observed a real " +
                        "panel swap on this device. Once it has, the measured value wins.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            SectionCard("Magnitudes") {
                TuningSlider("Max blur at hinge (dp)", tuning.maxHingeBlurDp, {
                    controller.updateTuning(tuning.copy(maxHingeBlurDp = it))
                }, 0f..60f, decimals = 1)
                TuningSlider("Max blur at outer edge (dp)", tuning.maxOuterEdgeBlurDp, {
                    controller.updateTuning(tuning.copy(maxOuterEdgeBlurDp = it))
                }, 0f..40f, decimals = 1)
                TuningSlider("Hinge falloff", tuning.hingeFalloff, {
                    controller.updateTuning(tuning.copy(hingeFalloff = it))
                }, 0.3f..4f)
                TuningSlider("Max dimming", tuning.maxDim, {
                    controller.updateTuning(tuning.copy(maxDim = it))
                }, 0f..1f)
                TuningSlider("Cover scale amount", tuning.coverScaleAmount, {
                    controller.updateTuning(tuning.copy(coverScaleAmount = it))
                }, 0f..0.2f)
                TuningSlider("Inner start scale", tuning.innerStartScale, {
                    controller.updateTuning(tuning.copy(innerStartScale = it))
                }, 1f..1.3f)
                TuningSlider("Wallpaper scale amount", tuning.wallpaperScaleAmount, {
                    controller.updateTuning(tuning.copy(wallpaperScaleAmount = it))
                }, 0f..0.2f)
                TuningSlider("Vignette", tuning.maxVignette, {
                    controller.updateTuning(tuning.copy(maxVignette = it))
                }, 0f..1f)
                TuningSlider("Hinge shadow", tuning.maxHingeShadow, {
                    controller.updateTuning(tuning.copy(maxHingeShadow = it))
                }, 0f..1f)
                TuningSlider("Edge illumination", tuning.maxEdgeIllumination, {
                    controller.updateTuning(tuning.copy(maxEdgeIllumination = it))
                }, 0f..1f)
                TuningSlider("Perspective", tuning.maxPerspective, {
                    controller.updateTuning(tuning.copy(maxPerspective = it))
                }, 0f..0.6f)
                TuningSlider("Desaturation", tuning.maxDesaturation, {
                    controller.updateTuning(tuning.copy(maxDesaturation = it))
                }, 0f..1f)
                TuningSlider("Dissolve softness", tuning.dissolveSoftness, {
                    controller.updateTuning(tuning.copy(dissolveSoftness = it))
                }, 0.02f..1f)
            }
        }

        item {
            SectionCard("Sensor filtering") {
                TuningSlider("One Euro min cutoff", filter.oneEuroMinCutoff, {
                    pushFilter { oneEuroMinCutoff = it }
                }, 0.1f..8f)
                TuningSlider("One Euro beta (speed coupling)", filter.oneEuroBeta, {
                    pushFilter { oneEuroBeta = it }
                }, 0f..0.5f, decimals = 4)
                TuningSlider("Prediction (seconds)", filter.predictionSeconds, {
                    pushFilter { predictionSeconds = it }
                }, 0f..0.05f, decimals = 4)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TuningSlider("Hysteresis: enter (°/s)", filter.enterVelocityThreshold, {
                    pushFilter { enterVelocityThreshold = it }
                }, 0.5f..40f, decimals = 1)
                TuningSlider("Hysteresis: exit (°/s)", filter.exitVelocityThreshold, {
                    pushFilter { exitVelocityThreshold = it }
                }, 0.1f..20f, decimals = 1)
                TuningSlider("Direction dwell (ms)", filter.directionDwellMs, {
                    pushFilter { directionDwellMs = it }
                }, 0f..120f, decimals = 0)
                TuningSlider("Closed deadband (progress)", filter.closedDeadband, {
                    pushFilter { closedDeadband = it }
                }, 0f..0.15f, decimals = 4)
                Text(
                    "Enter must stay above exit, or direction will chatter on noise. " +
                        "The diagnostics screen reports the measured noise floor — pick " +
                        "thresholds from that, not by feel.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            SectionCard("Rendering") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShaderQuality.entries.forEach { q ->
                        FilterChip(
                            selected = activeQuality == q,
                            onClick = { controller.setShaderQuality(q) },
                            label = { Text("${q.name} (${q.taps} taps)") },
                        )
                    }
                }
                Text(
                    if (controller.adaptiveQuality.hasStepped) {
                        "Quality was stepped down automatically because P95 frame time " +
                            "exceeded the budget. Picking a level here overrides that."
                    } else {
                        "Left alone, the app steps this down by itself if it cannot hold " +
                            "the frame budget. Picking a level here takes manual control."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            SectionCard("Device geometry") {
                Text(
                    "Which half the cover display sits behind is a property of the " +
                        "chassis that no Android API reports. If the effect looks " +
                        "mirrored — content emerging from the wrong edge — flip this.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CoverHalf.entries.forEach { half ->
                        FilterChip(
                            selected = controller.engine.sceneMapping.coverHalf == half,
                            onClick = { controller.setCoverHalf(half) },
                            label = { Text("Cover on ${half.name.lowercase()} half") },
                        )
                    }
                }
                Readout(
                    "Panel aspects",
                    if (controller.displayProfile.isComplete) {
                        "measured on this device"
                    } else {
                        "partly using Z Fold 7 defaults"
                    },
                )
            }
        }

        item {
            SectionCard("Source") {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Virtual hinge", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (controller.hasHingeSensor) {
                                "Drive the same pipeline from a slider instead of the sensor"
                            } else {
                                "No hinge sensor on this device — virtual hinge is the only source"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = usingVirtual || !controller.hasHingeSensor,
                        enabled = controller.hasHingeSensor,
                        onCheckedChange = { on ->
                            if (on) controller.useVirtualHinge() else controller.useRealSensor()
                        },
                    )
                }
            }
        }

        item {
            SectionCard("Actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            presetName = "Apple-like (default)"
                            controller.updateTuning(FoldTuningPresets.APPLE_LIKE)
                            filter = FilterConfig()
                            controller.updateFilterConfig(filter)
                        },
                        label = { Text("Reset to Apple-like defaults") },
                    )
                }
                Text(
                    "Calibration, sensor diagnostics, and the animation tests live on " +
                        "their own tabs.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
