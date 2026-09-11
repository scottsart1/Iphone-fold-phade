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
 * Diagnostics export for the Sensors screen.
 *
 * ## Getting data *out* is the hard part
 *
 * Writing a file to `cacheDir/exports` is easy and completely useless on its own: since
 * Android 11, `Android/data/<pkg>/` is not browsable by file managers, so a report written
 * there is unreachable on the very device that produced it unless the user has adb — which
 * is exactly the situation this app is built for people *not* to need.
 *
 * So every export is available three ways, in descending order of convenience:
 *
 * 1. **[buildSensorReport] returns the text**, so the UI can copy it to the clipboard or
 *    render it on screen. This is the one that actually works for someone with only a
 *    phone.
 * 2. **[shareIntent]** hands it to the system share sheet via [FileProvider], so it can go
 *    to email, notes, a messaging app — anywhere.
 * 3. The file on disk, for anyone who does have adb.
 *
 * Always off the main thread: a diagnostics path that stutters the UI would defeat the
 * purpose of an instrument meant to detect stutter.
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
            file.writeText(buildSensorReport(inventory, trace, extraNotes))
            file
        }
    }

    /**
     * Build the report as a string.
     *
     * Separate from [exportSensorReport] because the string is the genuinely useful
     * artefact — it can be copied to the clipboard and pasted straight into a message,
     * which needs no file access at all.
     */
    suspend fun buildSensorReport(
        inventory: SensorInventory,
        trace: HingeTrace?,
        extraNotes: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.Default) {
        buildString {
            reportInto(this, inventory, trace, extraNotes)
        }
    }

    /**
     * A short, pasteable summary — the ~30 lines that actually drive a diagnosis.
     *
     * The full report enumerates every sensor on the device and runs to thousands of
     * characters, which is fine as an attachment and miserable to paste into a message.
     * This is the version to send first; the full one is there if it turns out to be
     * needed.
     */
    suspend fun buildSummary(
        inventory: SensorInventory,
        trace: HingeTrace?,
        state: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.Default) {
        buildString {
            appendLine("FoldPhase summary — ${Date()}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} / ${Build.DISPLAY}")
            appendLine()

            val hinge = inventory.hinge
            if (hinge == null) {
                appendLine("HINGE SENSOR: NOT FOUND")
                appendLine("  no TYPE_HINGE_ANGLE (36), no '${SensorInfo.HINGE_ANGLE_STRING_TYPE}',")
                appendLine("  and no name heuristic match among ${inventory.all.size} sensors")
            } else {
                appendLine("HINGE SENSOR: ${hinge.name}")
                appendLine("  type=${hinge.type} stringType=${hinge.stringType}")
                appendLine("  vendor=${hinge.vendor} version=${hinge.version}")
                appendLine("  resolution=${hinge.resolution} maxRange=${hinge.maximumRange}")
                appendLine("  minDelay=${hinge.minDelayUs}us (${hinge.maxRateHz.format3()} Hz max)")
                appendLine("  reporting=${hinge.reportingModeLabel} wakeup=${hinge.isWakeUpSensor}")
                appendLine("  power=${hinge.powerMa} mA")
                if (inventory.hingeIsHeuristicMatch) {
                    appendLine("  WARNING: matched by name heuristic, not a standard identifier")
                }
            }
            appendLine()

            if (trace != null && trace.hasObservations) {
                appendLine("OBSERVED")
                appendLine("  angle range seen : ${trace.minObservedAngle.format3()} .. ${trace.maxObservedAngle.format3()}")
                appendLine("  samples          : ${trace.size}")
                appendLine("  event rate       : ${trace.recentEventRateHz().format3()} Hz recent, ${trace.averageEventRateHz().format3()} Hz avg")
            } else {
                appendLine("OBSERVED: no samples recorded yet — move the hinge, then re-copy")
            }
            appendLine()

            if (state.isNotEmpty()) {
                appendLine("APP STATE")
                state.forEach { (k, v) -> appendLine("  ${k.padEnd(18)}: $v") }
                appendLine()
            }

            appendLine("Sensors on device: ${inventory.all.size}")
            val related = inventory.foldRelated
            if (related.isNotEmpty()) {
                appendLine("Fold-related: " + related.joinToString { it.name })
            }
        }
    }

    private fun reportInto(
        sb: StringBuilder,
        inventory: SensorInventory,
        trace: HingeTrace?,
        extraNotes: Map<String, String>,
    ) {
        with(sb) {
            run {
                val w = this
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
        }
    }

    /** Convert an exported file to a shareable content URI. */
    fun shareUri(file: File): android.net.Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    /**
     * A ready-to-launch share chooser for an exported file.
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` is what lets the receiving app actually open the
     * content URI; without it the share appears to work and the target sees nothing.
     */
    fun shareIntent(file: File, mimeType: String = "text/plain"): android.content.Intent {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri(file))
            putExtra(android.content.Intent.EXTRA_SUBJECT, "FoldPhase diagnostics — ${file.name}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return android.content.Intent.createChooser(send, "Share diagnostics")
    }

    /** Share plain text directly, with no file involved at all. */
    fun shareText(text: String, subject: String): android.content.Intent {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        }
        return android.content.Intent.createChooser(send, subject)
    }

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
