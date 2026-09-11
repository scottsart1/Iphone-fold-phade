package dev.foldphase.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV and text export for the diagnostics screen (brief §3, §21).
 *
 * Writes into `cacheDir/exports` and returns a `content://` URI via [FileProvider], which
 * is the only way to hand a file to another app on a modern Android release without
 * either `MANAGE_EXTERNAL_STORAGE` or a `FileUriExposedException`.
 *
 * Always off the main thread: this is a diagnostics path, but a diagnostics path that
 * stutters the UI would defeat the purpose of an instrument meant to detect stutter.
 */
class DebugExport(private val context: Context) {

    private val exportDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Write the hinge trace as CSV.
     *
     * Timestamps are emitted both as raw nanoseconds and as milliseconds relative to the
     * first sample, because the absolute value is only meaningful for correlating with a
     * logcat capture while the relative one is what you actually plot.
     */
    suspend fun exportTraceCsv(trace: HingeTrace, label: String = "hinge"): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val samples = trace.snapshot()
                require(samples.isNotEmpty()) { "No samples recorded yet" }

                val file = File(exportDir, "${label}_${timestamp()}.csv")
                val t0 = samples.first().timestampNanos

                file.bufferedWriter().use { w ->
                    w.appendLine(
                        "timestamp_nanos,elapsed_ms,raw_angle_deg,filtered_angle_deg," +
                            "velocity_deg_per_sec,progress,direction,state",
                    )
                    samples.forEach { s ->
                        w.append(s.timestampNanos.toString()).append(',')
                        w.append(((s.timestampNanos - t0) / 1_000_000.0).format3()).append(',')
                        w.append(s.rawAngleDeg.format3()).append(',')
                        w.append(s.filteredAngleDeg.format3()).append(',')
                        w.append(s.velocityDegPerSec.format3()).append(',')
                        w.append(s.progress.format3()).append(',')
                        w.append(s.direction.name).append(',')
                        w.appendLine(s.state.name)
                    }
                }
                file
            }
        }

    /**
     * Write a human-readable report: device identity, the full sensor inventory, and the
     * observed hinge statistics.
     *
     * This is the artefact to attach when reporting that something behaves differently on
     * a particular device — it contains everything needed to tell whether the problem is
     * the app or the hardware.
     */
    suspend fun exportSensorReport(
        inventory: SensorInventory,
        trace: HingeTrace?,
        extraNotes: Map<String, String> = emptyMap(),
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(exportDir, "sensor_report_${timestamp()}.txt")
            file.bufferedWriter().use { w ->
                w.appendLine("FoldPhase sensor report")
                w.appendLine("Generated: ${Date()}")
                w.appendLine()
                w.appendLine("== Device ==")
                w.appendLine("Manufacturer : ${Build.MANUFACTURER}")
                w.appendLine("Brand        : ${Build.BRAND}")
                w.appendLine("Model        : ${Build.MODEL}")
                w.appendLine("Device       : ${Build.DEVICE}")
                w.appendLine("Product      : ${Build.PRODUCT}")
                w.appendLine("Android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                w.appendLine("Build ID     : ${Build.DISPLAY}")
                w.appendLine()

                if (extraNotes.isNotEmpty()) {
                    w.appendLine("== Notes ==")
                    extraNotes.forEach { (k, v) -> w.appendLine("$k: $v") }
                    w.appendLine()
                }

                w.appendLine("== Hinge sensor ==")
                val hinge = inventory.hinge
                if (hinge == null) {
                    w.appendLine("NOT FOUND. No sensor matched TYPE_HINGE_ANGLE (36),")
                    w.appendLine("'${SensorInfo.HINGE_ANGLE_STRING_TYPE}', or a hinge/fold name heuristic.")
                } else {
                    if (inventory.hingeIsHeuristicMatch) {
                        w.appendLine("(matched by NAME HEURISTIC, not by a standard identifier)")
                    }
                    w.appendLine(hinge.describe())
                }
                w.appendLine()

                if (trace != null && trace.hasObservations) {
                    w.appendLine("== Observed hinge behaviour ==")
                    w.appendLine("Samples recorded     : ${trace.size}")
                    w.appendLine("Min observed angle   : ${trace.minObservedAngle.format3()}")
                    w.appendLine("Max observed angle   : ${trace.maxObservedAngle.format3()}")
                    w.appendLine("Average event rate   : ${trace.averageEventRateHz().format3()} Hz")
                    w.appendLine("Recent event rate    : ${trace.recentEventRateHz().format3()} Hz")
                    w.appendLine()
                }

                w.appendLine("== All sensors (${inventory.all.size}) ==")
                inventory.all.forEach { info ->
                    w.appendLine(info.describe())
                    w.appendLine()
                }
            }
            file
        }
    }

    /** Convert an exported file to a shareable content URI. */
    fun shareUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    fun listExports(): List<File> =
        exportDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearExports() {
        exportDir.listFiles()?.forEach { it.delete() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private companion object {
        fun SensorInfo.describe(): String = buildString {
            appendLine("Name              : $name")
            appendLine("Type              : $type")
            appendLine("String type       : $stringType")
            appendLine("Vendor            : $vendor")
            appendLine("Version           : $version")
            appendLine("Resolution        : $resolution")
            appendLine("Maximum range     : $maximumRange")
            appendLine("Min delay (us)    : $minDelayUs  (max ${maxRateHz.format3()} Hz)")
            appendLine("Max delay (us)    : $maxDelayUs")
            appendLine("Reporting mode    : $reportingModeLabel")
            appendLine("Power (mA)        : $powerMa")
            appendLine("Wake-up sensor    : $isWakeUpSensor")
            appendLine("Dynamic           : $isDynamic")
            append("Direct report lvl : $highestDirectReportRateLevel")
        }

        fun Float.format3(): String = String.format(Locale.US, "%.3f", this)
        fun Double.format3(): String = String.format(Locale.US, "%.3f", this)
    }
}
