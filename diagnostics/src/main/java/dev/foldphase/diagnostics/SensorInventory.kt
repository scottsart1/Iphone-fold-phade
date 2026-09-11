package dev.foldphase.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Everything Android will tell us about one sensor (brief §3).
 */
data class SensorInfo(
    val name: String,
    val type: Int,
    val stringType: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maximumRange: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val reportingMode: Int,
    val powerMa: Float,
    val isWakeUpSensor: Boolean,
    val isDynamic: Boolean,
    val highestDirectReportRateLevel: Int,
) {
    val reportingModeLabel: String
        get() = when (reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "CONTINUOUS"
            Sensor.REPORTING_MODE_ON_CHANGE -> "ON_CHANGE"
            Sensor.REPORTING_MODE_ONE_SHOT -> "ONE_SHOT"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "SPECIAL_TRIGGER"
            else -> "UNKNOWN($reportingMode)"
        }

    /** Maximum theoretical event rate implied by [minDelayUs]. */
    val maxRateHz: Float
        get() = if (minDelayUs > 0) 1_000_000f / minDelayUs else 0f

    val isHingeAngle: Boolean
        get() = type == Sensor.TYPE_HINGE_ANGLE || stringType == HINGE_ANGLE_STRING_TYPE

    companion object {
        /**
         * The canonical string type the brief asks us to search for explicitly.
         *
         * Matching on the string as well as the integer constant matters: an OEM may
         * expose the hinge through a vendor-specific type integer while still using the
         * standard string type, and we would rather find it than insist on the constant.
         */
        const val HINGE_ANGLE_STRING_TYPE = "android.sensor.hinge_angle"
    }
}

/**
 * Enumerates the device's sensors and picks out anything hinge-related.
 *
 * Runs once when the diagnostics screen opens; never on the animation path.
 */
class SensorInventory(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Every sensor the platform reports, sorted so the hinge appears first. */
    val all: List<SensorInfo> by lazy {
        sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { it.toInfo() }
            .sortedWith(compareByDescending<SensorInfo> { it.isHingeAngle }.thenBy { it.name })
    }

    /**
     * The hinge sensor, found by the standard constant, by string type, or — as a last
     * resort — by a name that mentions a hinge or fold.
     *
     * The third path exists because an OEM shipping a non-standard hinge sensor is far
     * more plausible than this app being useful without one, and a heuristic match that
     * the diagnostics screen clearly labels is better than silently finding nothing.
     */
    val hinge: SensorInfo? by lazy {
        all.firstOrNull { it.type == Sensor.TYPE_HINGE_ANGLE }
            ?: all.firstOrNull { it.stringType == SensorInfo.HINGE_ANGLE_STRING_TYPE }
            ?: all.firstOrNull { info ->
                val n = info.name.lowercase()
                ("hinge" in n || "fold" in n) && "angle" in n
            }
    }

    /** True if the hinge was found via the heuristic rather than a standard identifier. */
    val hingeIsHeuristicMatch: Boolean
        get() = hinge != null &&
            hinge?.type != Sensor.TYPE_HINGE_ANGLE &&
            hinge?.stringType != SensorInfo.HINGE_ANGLE_STRING_TYPE

    /** Anything that mentions fold/hinge/flip, for the "related sensors" section. */
    val foldRelated: List<SensorInfo>
        get() = all.filter { info ->
            val n = "${info.name} ${info.stringType}".lowercase()
            "hinge" in n || "fold" in n || "flip" in n || "posture" in n
        }

    private fun Sensor.toInfo() = SensorInfo(
        name = name ?: "(unnamed)",
        type = type,
        stringType = stringType ?: "(none)",
        vendor = vendor ?: "(unknown)",
        version = version,
        resolution = resolution,
        maximumRange = maximumRange,
        minDelayUs = minDelay,
        maxDelayUs = maxDelay,
        reportingMode = reportingMode,
        powerMa = power,
        isWakeUpSensor = isWakeUpSensor,
        isDynamic = isDynamicSensor,
        highestDirectReportRateLevel = highestDirectReportRateLevel,
    )
}
