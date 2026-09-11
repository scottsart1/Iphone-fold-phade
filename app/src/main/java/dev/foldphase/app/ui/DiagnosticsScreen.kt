package dev.foldphase.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.FoldController
import dev.foldphase.diagnostics.DebugExport
import dev.foldphase.diagnostics.SensorInfo
import dev.foldphase.diagnostics.SensorInventory
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
    val calibration by controller.calibration.collectAsStateWithLifecycle()

    var exportMessage by remember { mutableStateOf<String?>(null) }

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
                Readout("Calibrated", if (progress.calibrated) "yes" else "NO — run calibration")
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
            SectionCard("Export") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            exportMessage = exporter.exportTraceCsv(controller.trace).fold(
                                onSuccess = { "Wrote ${it.name}" },
                                onFailure = { "CSV export failed: ${it.message}" },
                            )
                        }
                    }) { Text("Export CSV") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            exportMessage = exporter.exportSensorReport(
                                inventory,
                                controller.trace,
                            ).fold(
                                onSuccess = { "Wrote ${it.name}" },
                                onFailure = { "Report failed: ${it.message}" },
                            )
                        }
                    }) { Text("Sensor report") }
                }
                exportMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Text(
                    "Files are written to the app cache and listed below; share them from " +
                        "a file manager or via adb.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                exporter.listExports().take(5).forEach { f ->
                    Text("· ${f.name}", style = MonoNumber)
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
