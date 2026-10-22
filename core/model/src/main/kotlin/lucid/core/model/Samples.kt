package lucid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Measurements as a watch (real or simulated) reports them.
 *
 * Timestamps are epoch milliseconds. Simulated sessions use virtual time, so a whole night
 * can be replayed in minutes while every downstream component still sees a consistent clock.
 *
 * Deliberately absent: any sleep-stage label. Simulator ground truth travels on a separate
 * channel (see `lucid.core.sim.SimFrame.truth`) so the processing pipeline can never read it.
 */
@Serializable
sealed interface SensorSample {
    val t: Long
}

/** Heart rate as computed by the watch (typically 1 Hz). */
@Serializable
@SerialName("hr")
data class HeartRateSample(
    override val t: Long,
    val bpm: Float,
    val quality: SampleQuality,
) : SensorSample

/**
 * One inter-beat interval (time between two consecutive heartbeats), timestamped at the
 * second beat. Only some providers expose these (e.g. Samsung Health Sensor SDK); others
 * report heart rate only, and the pipeline must cope.
 */
@Serializable
@SerialName("ibi")
data class IbiSample(
    override val t: Long,
    val ibiMs: Int,
    val quality: SampleQuality,
) : SensorSample

/**
 * One-second summary of the wrist accelerometer, computed on the watch so raw 25-50 Hz
 * data never has to cross Bluetooth.
 *
 * @property activityMg mean absolute deviation of the acceleration magnitude from 1 g, in milli-g.
 * @property angleChangeDeg change of the wrist z-angle against the previous second, in degrees.
 */
@Serializable
@SerialName("mot")
data class MotionSample(
    override val t: Long,
    val activityMg: Float,
    val angleChangeDeg: Float,
) : SensorSample

/** Sensor state changes the watch reports (worn/not worn, sensor errors). */
@Serializable
@SerialName("st")
data class SensorStatusSample(
    override val t: Long,
    val status: SensorStatus,
) : SensorSample

@Serializable
enum class SampleQuality { GOOD, FAIR, POOR, NO_CONTACT }

@Serializable
enum class SensorStatus { ON_WRIST, OFF_WRIST, SENSOR_ERROR, SENSOR_UNAVAILABLE }

/** Polysomnography-style stages collapsed to what wrist sensors can plausibly separate. */
@Serializable
enum class SleepStage(val label: String) {
    WAKE("Wake"),
    LIGHT("Light (N1/N2)"),
    DEEP("Deep (N3)"),
    REM("REM"),
}
