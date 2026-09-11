package dev.foldphase.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.FoldController
import dev.foldphase.diagnostics.DebugExport
import dev.foldphase.diagnostics.SensorInfo
import dev.foldphase.diagnostics.SensorInventory
import dev.foldphase.sensors.ProbeStats
import kotlinx.coroutines.launch

/**
 * Milestone 1 (brief §29): the hardware diagnostics build.
 *
 * The success criterion is "I can move the hinge and watch a clean live value", so the
 * live hinge readout and graph come first on the screen and everything else is below it.
 * The sensor inventory is complete rather than filtered, because the point of this screen
 * is to find out what the device actually has — including whatever Samsung exposes that
 * nobody documented.
 */
@Composable
fun DiagnosticsScreen(controller: FoldController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val inventory = remember { SensorInventory(context) }
    val exporter = remember { DebugExport(context) }
    val progress by controller.progress.collectAsStateWithLifecycle()
    val calibration by controller.effectiveCalibration.collectAsStateWithLifecycle()
    val storedCalibration by controller.calibration.collectAsStateWithLifecycle()
    val renderPath by controller.renderPath.collectAsStateWithLifecycle()
    val activeQuality by controller.activeQuality.collectAsStateWithLifecycle()

    var exportMessage by remember { mutableStateOf<String?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
    val probeStats by controller.sensorProbe.stats.collectAsStateWithLifecycle()
    val activeSensorName by controller.activeSensorName.collectAsStateWithLifecycle()

    // The probe registers several sensors at once, so it runs only while this screen is
    // visible rather than for the life of the app.
    androidx.compose.runtime.DisposableEffect(Unit) {
        controller.sensorProbe.start()
        onDispose { controller.sensorProbe.stop() }
    }
    // The platform clipboard rather than LocalClipboardManager: that composition local is
    // deprecated, and its replacement's API shape differs between Compose releases, which
    // is not a dependency worth taking for one call.
    val clipboard = remember(context) {
        context.getSystemService(ClipboardManager::class.java)
    }

    val hinge = inventory.hinge

    LazyColumn(modifier.fillMaxSize()) {

        // ---- Live hinge value: the headline of this screen ----------------------
        item {
            SectionCard("Live hinge") {
                Text(
                    text = "${progress.rawAngleDeg.fmt(2)}°",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = if (hinge == null) {
                        "No hinge sensor found — values are from the simulator"
                    } else {
                        "Raw sensor value"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Readout("Raw angle", "${progress.rawAngleDeg.fmt(3)}°")
                Readout("Filtered angle", "${progress.filteredAngleDeg.fmt(3)}°")
                Readout("Velocity", "${progress.velocityDegPerSec.fmt(2)}°/s")
                Readout("Normalized progress", progress.progress.fmt(4))
                Readout("Direction", progress.direction.name)
                Readout("Fold state", progress.state.name)
                Readout("Active display", progress.activeDisplay.name)
                Readout("Source", progress.source.name)
                Readout("Sensor", activeSensorName ?: "platform default / simulator")
                Readout(
                    "Calibration",
                    when {
                        storedCalibration.isCalibrated -> "measured (wizard)"
                        controller.autoCalibrator.isTrusted -> "provisional (auto-learned)"
                        else -> "none yet — fold fully once, or run the wizard"
                    },
                )
            }
        }

        // ---- Sensor probe: which sensor can actually scrub an animation? --------
        item {
            SectionCard("Sensor probe — ${probeStats.size} candidates") {
                Text(
                    "Fold the phone slowly, all the way open and shut. Then read the " +
                        "DISTINCT column: that is how many different values each sensor " +
                        "actually produced. A sensor that reports only a few detent " +
                        "positions cannot drive a continuous animation, no matter how " +
                        "much smoothing is applied — the values in between were never " +
                        "measured.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Declared resolution is not evidence: vendor HALs often fill it with " +
                        "a placeholder. Only the observed count settles it.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                probeStats.forEach { p ->
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    ProbeRow(
                        stats = p,
                        isActive = controller.currentSensor()?.type == p.sensorType,
                        onUse = {
                            controller.sensorProbe.candidates
                                .firstOrNull { it.type == p.sensorType }
                                ?.let { controller.selectSensor(it) }
                        },
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Button(onClick = {
                        val best = controller.adoptBestProbedSensor()
                        exportMessage = if (best == null) {
                            "No candidate looks continuous yet — fold slowly a few more times."
                        } else {
                            "Switched to ${best.name} (${best.distinctValues} distinct values)."
                        }
                    }) { Text("Use best candidate") }

                    OutlinedButton(onClick = { controller.sensorProbe.reset() }) {
                        Text("Reset counts")
                    }
                }
                Text(
                    "Switching sensor clears calibration — a range measured in one " +
                        "sensor's units means nothing in another's.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---- Graph -------------------------------------------------------------
        item {
            SectionCard("Time → hinge angle") {
                HingeGraph(
                    trace = controller.trace,
                    repaintKey = controller.visualState,
                    minAngle = calibration.closedAngleDeg - 5f,
                    maxAngle = calibration.openAngleDeg + 5f,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("raw (thin) · filtered (thick)", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${(calibration.closedAngleDeg - 5f).fmt(0)}° … " +
                            "${(calibration.openAngleDeg + 5f).fmt(0)}°",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ---- Derived statistics -------------------------------------------------
        item {
            SectionCard("Observed statistics") {
                Readout("Min observed angle", "${controller.trace.estimatedClosedAngle().fmt(2)}°")
                Readout("Max observed angle", "${controller.trace.estimatedOpenAngle().fmt(2)}°")
                Readout("Samples recorded", controller.trace.size.toString())
                Readout("Event rate (recent)", "${controller.trace.recentEventRateHz().fmt(1)} Hz")
                Readout("Event rate (average)", "${controller.trace.averageEventRateHz().fmt(1)} Hz")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    "Estimated extremes are what the sensor has actually reported, not the " +
                        "stored calibration. If they disagree, re-run calibration.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Readout("Calibrated closed", "${calibration.closedAngleDeg.fmt(2)}°")
                Readout("Calibrated open", "${calibration.openAngleDeg.fmt(2)}°")
                Readout("Noise (σ)", "${calibration.noiseStdDevDeg.fmt(3)}°")
                Readout("Observed resolution", "${calibration.observedResolutionDeg.fmt(3)}°")
                Readout("Median update interval", "${calibration.medianUpdateIntervalMs.fmt(2)} ms")
                Readout("Largest discontinuity", "${calibration.largestDiscontinuityDeg.fmt(3)}°")
                Readout(
                    "Measured handoff",
                    if (calibration.hasMeasuredHandoff) {
                        "p = ${calibration.measuredHandoffProgress.fmt(3)}"
                    } else {
                        "not yet observed"
                    },
                )
            }
        }

        // ---- Render path --------------------------------------------------------
        item {
            SectionCard("Rendering") {
                Readout("Path", renderPath ?: "not yet resolved")
                Readout("Shader quality", "${activeQuality.name} (${activeQuality.taps} taps)")
                Readout(
                    "Quality chosen by",
                    if (controller.adaptiveQuality.hasStepped) {
                        "app — stepped down to hold frame budget"
                    } else {
                        "default or manual"
                    },
                )
                if (renderPath?.startsWith("FALLBACK") == true) {
                    Text(
                        "The AGSL shader did not compile on this device, so the " +
                            "platform-only fallback is running. The effect still tracks " +
                            "the hinge, but the blur is uniform rather than concentrated " +
                            "at the fold. The reason is shown above — please report it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Readout(
                    "Cover panel aspect",
                    if (controller.displayProfile.hasCover) {
                        controller.displayProfile.coverAspect.fmt(4)
                    } else {
                        "not seen yet"
                    },
                )
                Readout(
                    "Inner panel aspect",
                    if (controller.displayProfile.hasInner) {
                        controller.displayProfile.innerAspect.fmt(4)
                    } else {
                        "not seen yet"
                    },
                )
                Readout(
                    "Scene mapping",
                    if (controller.displayProfile.isComplete) "measured" else "partly default",
                )
            }
        }

        // ---- Frame performance --------------------------------------------------
        item {
            val stats = controller.frameMetrics.snapshot()
            SectionCard("Frame performance") {
                Readout("Average frame", "${stats.averageMs.fmt(2)} ms")
                Readout("P95 frame", "${stats.p95Ms.fmt(2)} ms")
                Readout("P99 frame", "${stats.p99Ms.fmt(2)} ms")
                Readout("Dropped frames", "${stats.droppedFrames} / ${stats.totalFrames}")
                Readout("Effective rate", "${stats.effectiveHz.fmt(1)} Hz")
                Readout("Display rate", "${stats.expectedHz.fmt(1)} Hz")
            }
        }

        // ---- The hinge sensor ---------------------------------------------------
        item {
            SectionCard("Hinge sensor") {
                if (hinge == null) {
                    Text(
                        "No sensor matched TYPE_HINGE_ANGLE (36), " +
                            "'${SensorInfo.HINGE_ANGLE_STRING_TYPE}', or a hinge/fold name " +
                            "heuristic. The transition can still be driven by the virtual " +
                            "hinge in the simulator.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    if (inventory.hingeIsHeuristicMatch) {
                        Text(
                            "Matched by NAME HEURISTIC, not by a standard identifier — " +
                                "treat its units and range with suspicion and calibrate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SensorDetail(hinge)
                }
            }
        }

        // ---- Export -------------------------------------------------------------
        item {
            SectionCard("Diagnostics report") {
                Text(
                    "Copy summary is the one to use — about 30 lines, enough to diagnose " +
                        "almost anything. Android blocks file managers from opening " +
                        "Android/data, so a file written there is unreachable without " +
                        "adb; the clipboard needs no file access at all.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Button(onClick = {
                        scope.launch {
                            val text = exporter.buildSummary(
                                inventory,
                                controller.trace,
                                appState(controller, progress, calibration, storedCalibration, renderPath, activeQuality)
                                    + probeState(probeStats),
                            )
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("FoldPhase diagnostics", text),
                            )
                            reportText = text
                            showReport = true
                            exportMessage = "Summary copied (${text.length} chars). Paste it anywhere."
                        }
                    }) { Text("Copy summary") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            val text = exporter.buildSummary(
                                inventory,
                                controller.trace,
                                appState(controller, progress, calibration, storedCalibration, renderPath, activeQuality)
                                    + probeState(probeStats),
                            )
                            reportText = text
                            runCatching {
                                context.startActivity(
                                    exporter.shareText(text, "FoldPhase diagnostics"),
                                )
                            }.onFailure { exportMessage = "No app to share to: ${it.message}" }
                        }
                    }) { Text("Share") }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val text = exporter.buildSensorReport(inventory, controller.trace)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("FoldPhase full report", text),
                            )
                            reportText = text
                            showReport = true
                            exportMessage =
                                "Full report copied (${text.length} chars) — every sensor included."
                        }
                    }) { Text("Copy FULL report") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            exportMessage = exporter.exportTraceCsv(controller.trace).fold(
                                onSuccess = { file ->
                                    runCatching {
                                        context.startActivity(exporter.shareIntent(file, "text/csv"))
                                    }
                                    "Sharing ${file.name}"
                                },
                                onFailure = { "CSV export failed: ${it.message}" },
                            )
                        }
                    }) { Text("Share hinge CSV") }
                }

                exportMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (showReport && reportText != null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(
                        "Long-press to select, or screenshot it.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    SelectionContainer {
                        Text(
                            text = reportText.orEmpty(),
                            style = MonoNumber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // ---- Fold-related sensors ----------------------------------------------
        if (inventory.foldRelated.isNotEmpty()) {
            item {
                SectionCard("Fold-related sensors (${inventory.foldRelated.size})") {
                    inventory.foldRelated.forEach { info ->
                        SensorDetail(info)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        // ---- Everything ---------------------------------------------------------
        item {
            Text(
                "All sensors (${inventory.all.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(inventory.all, key = { "${it.type}:${it.name}" }) { info ->
            SectionCard(info.name) { SensorDetail(info) }
        }
    }
}

@Composable
private fun SensorDetail(info: SensorInfo) {
    Column {
        Readout("Type", info.type.toString())
        Readout("String type", info.stringType)
        Readout("Vendor", info.vendor)
        Readout("Version", info.version.toString())
        Readout("Resolution", info.resolution.fmt(4))
        Readout("Maximum range", info.maximumRange.fmt(3))
        Readout(
            "Min delay",
            if (info.minDelayUs > 0) {
                "${info.minDelayUs} µs (${info.maxRateHz.fmt(1)} Hz)"
            } else {
                "${info.minDelayUs} µs"
            },
        )
        Readout("Max delay", "${info.maxDelayUs} µs")
        Readout("Reporting mode", info.reportingModeLabel)
        Readout("Power", "${info.powerMa.fmt(3)} mA")
        Readout("Wake-up", if (info.isWakeUpSensor) "yes" else "no")
        Readout("Dynamic", if (info.isDynamic) "yes" else "no")
    }
}

/**
 * Probe verdicts, formatted for the summary.
 *
 * Included because "which sensor can actually scrub" is currently the decisive unknown,
 * and it is far easier to read a one-line verdict per sensor than to infer it from a
 * declared resolution that may well be a placeholder.
 */
private fun probeState(stats: List<ProbeStats>): Map<String, String> =
    stats.associate { p ->
        "probe/${p.stringType.substringAfterLast('.').ifBlank { p.sensorType.toString() }}" to
            "${p.verdict.name} distinct=${p.distinctValues} events=${p.eventCount} " +
            "range=${p.minValue.fmt(2)}..${p.maxValue.fmt(2)} declRes=${p.declaredResolution.fmt(2)}"
    }

/**
 * The live app state worth putting in a summary.
 *
 * These are the values that distinguish "the sensor is wrong" from "the app misread a
 * correct sensor", which is the first fork in almost every diagnosis.
 */
private fun appState(
    controller: FoldController,
    progress: dev.foldphase.core.FoldProgress,
    effective: dev.foldphase.sensors.HingeCalibration,
    stored: dev.foldphase.sensors.HingeCalibration,
    renderPath: String?,
    quality: dev.foldphase.engine.ShaderQuality,
): Map<String, String> {
    val stats = controller.frameMetrics.snapshot()
    return linkedMapOf(
        "render path" to (renderPath ?: "not resolved"),
        "shader quality" to "${quality.name} (${quality.taps} taps)" +
            if (controller.adaptiveQuality.hasStepped) " auto-stepped" else "",
        "calibration" to when {
            stored.isCalibrated -> "measured via wizard"
            controller.autoCalibrator.isTrusted -> "provisional (auto-learned)"
            else -> "none yet"
        },
        "range in use" to "${effective.closedAngleDeg.fmt(2)} .. ${effective.openAngleDeg.fmt(2)}",
        "auto-cal span" to "${controller.autoCalibrator.observedSpan.fmt(2)} deg",
        "handoff" to if (stored.hasMeasuredHandoff) {
            "measured p=${stored.measuredHandoffProgress.fmt(3)}"
        } else {
            "not observed yet (using fallback)"
        },
        "cover aspect" to if (controller.displayProfile.hasCover) {
            controller.displayProfile.coverAspect.fmt(4)
        } else {
            "not seen"
        },
        "inner aspect" to if (controller.displayProfile.hasInner) {
            controller.displayProfile.innerAspect.fmt(4)
        } else {
            "not seen"
        },
        "cover half" to controller.engine.sceneMapping.coverHalf.name,
        "fold state" to "${progress.state.name} / ${progress.direction.name}",
        "active display" to progress.activeDisplay.name,
        "progress" to progress.progress.fmt(4),
        "frame avg/P95/P99" to
            "${stats.averageMs.fmt(2)} / ${stats.p95Ms.fmt(2)} / ${stats.p99Ms.fmt(2)} ms",
        "effective Hz" to "${stats.effectiveHz.fmt(1)} of ${stats.expectedHz.fmt(1)}",
        "dropped frames" to "${stats.droppedFrames} / ${stats.totalFrames}",
    )
}

/**
 * One candidate sensor in the probe table.
 *
 * The verdict line is deliberately blunt: the whole point of this screen is to answer a
 * yes/no question — can this sensor scrub an animation — and a wall of numbers without a
 * conclusion would leave that to the reader.
 */
@Composable
private fun ProbeRow(
    stats: ProbeStats,
    isActive: Boolean,
    onUse: () -> Unit,
) {
    val verdictColor = when (stats.verdict) {
        ProbeStats.Verdict.CONTINUOUS -> MaterialTheme.colorScheme.primary
        ProbeStats.Verdict.COARSE -> MaterialTheme.colorScheme.secondary
        ProbeStats.Verdict.QUANTISED -> MaterialTheme.colorScheme.error
        ProbeStats.Verdict.INSUFFICIENT_DATA -> MaterialTheme.colorScheme.onSurface
    }
    val verdictText = when (stats.verdict) {
        ProbeStats.Verdict.CONTINUOUS -> "CONTINUOUS — usable"
        ProbeStats.Verdict.COARSE -> "COARSE — usable but will feel stepped"
        ProbeStats.Verdict.QUANTISED -> "QUANTISED — cannot scrub an animation"
        ProbeStats.Verdict.INSUFFICIENT_DATA ->
            "need ${ProbeStats.MIN_EVENTS_TO_JUDGE - stats.eventCount} more events"
    }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stats.name.trim(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Text(
                    "IN USE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            stats.stringType.ifBlank { "type ${stats.sensorType}" },
            style = MaterialTheme.typography.labelSmall,
        )

        Text(
            verdictText,
            style = MaterialTheme.typography.bodyMedium,
            color = verdictColor,
            modifier = Modifier.padding(top = 6.dp),
        )

        Readout("DISTINCT values seen", stats.distinctValues.toString())
        Readout("Events", stats.eventCount.toString())
        Readout(
            "Observed range",
            if (stats.minValue.isNaN()) {
                "—"
            } else {
                "${stats.minValue.fmt(3)} … ${stats.maxValue.fmt(3)}"
            },
        )
        Readout(
            "Live value",
            (0 until stats.valueCount).joinToString("  ") { stats.values[it].fmt(3) }
                .ifBlank { "—" },
        )
        Readout("Declared resolution", stats.declaredResolution.fmt(3))
        Readout("Declared max range", stats.declaredMaxRange.fmt(3))
        Readout("Event rate", "${stats.recentRateHz.fmt(1)} Hz")

        if (stats.sampleValues.isNotEmpty()) {
            Text(
                "Values: " + stats.sampleValues.joinToString(", ") { it.fmt(2) } +
                    if (stats.distinctValues > stats.sampleValues.size) " …" else "",
                style = MonoNumber,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!isActive) {
            OutlinedButton(
                onClick = onUse,
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Drive the animation with this") }
        }
    }
}
