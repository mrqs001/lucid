package lucid.wear.sensors

import lucid.core.model.SensorSample
import lucid.core.model.SourceInfo
import lucid.core.model.WatchCapabilities

/**
 * Something that produces watch measurements. Real hardware and the simulator implement the same
 * interface, so everything downstream (batching, buffering, transport, the phone pipeline) is
 * identical for both.
 */
interface SensorSource {
    val info: SourceInfo
    val capabilities: SensorCapabilities

    /** Current time on this source's clock (virtual for an accelerated simulation). */
    fun now(): Long

    /**
     * Start producing. [onSamples] gets batches in timestamp order; [onLinkSimulation] lets a
     * simulated night script watch-to-phone outages (real sources never call it).
     */
    fun start(onSamples: (List<SensorSample>) -> Unit, onLinkSimulation: (Boolean) -> Unit = {})

    fun stop()

    /** Simulation only: let the simulated body react to a delivered cue. */
    fun onCueDelivered(intensity: Float) = Unit
}

data class SensorCapabilities(val heartRate: Boolean, val interBeatIntervals: Boolean, val accelerometer: Boolean) {
    fun toWatch(hasVibrator: Boolean, hasAmplitudeControl: Boolean, isEmulator: Boolean) =
        WatchCapabilities(heartRate, interBeatIntervals, accelerometer, hasVibrator, hasAmplitudeControl, isEmulator)
}
