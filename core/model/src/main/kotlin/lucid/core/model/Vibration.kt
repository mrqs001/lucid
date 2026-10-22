package lucid.core.model

import kotlinx.serialization.Serializable

/**
 * A vibration pattern as a list of pulses. Watches convert this to a platform waveform;
 * `level` is relative (0..1) and is multiplied by the cue intensity before conversion.
 */
@Serializable
data class VibrationPattern(
    val id: String,
    val name: String,
    val pulses: List<Pulse>,
) {
    @Serializable
    data class Pulse(val onMs: Int, val offMs: Int, val level: Float = 1f)

    val durationMs: Int get() = pulses.sumOf { it.onMs + it.offMs }

    /**
     * Android `VibrationEffect.createWaveform` arguments: alternating off/on timings starting
     * with an initial delay, and per-segment amplitudes (1..255, 0 for off segments).
     */
    fun toWaveform(intensity: Float, repeats: Int = 1): Pair<LongArray, IntArray> {
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        timings += 0L
        amplitudes += 0
        repeat(repeats.coerceAtLeast(1)) {
            for (p in pulses) {
                timings += p.onMs.toLong()
                amplitudes += amplitudeFor(p.level * intensity)
                timings += p.offMs.toLong()
                amplitudes += 0
            }
        }
        return timings.toLongArray() to amplitudes.toIntArray()
    }

    companion object {
        fun amplitudeFor(level: Float): Int = (level.coerceIn(0f, 1f) * 255f).toInt().coerceIn(1, 255)

        /** 200 ms on / 500 ms off, the tactile cue unit used by Esfahani et al. (bioRxiv 2024/2026). */
        val Pulse200 = VibrationPattern(
            id = "pulse-200",
            name = "Pulse (200/500 ms)",
            pulses = listOf(Pulse(200, 500), Pulse(200, 500), Pulse(200, 500)),
        )
        val TlrTriple = VibrationPattern(
            id = "tlr-triple",
            name = "Triple knock",
            pulses = listOf(Pulse(140, 160), Pulse(140, 160), Pulse(140, 600)),
        )
        val Heartbeat = VibrationPattern(
            id = "heartbeat",
            name = "Heartbeat",
            pulses = listOf(Pulse(90, 110, 0.8f), Pulse(160, 640)),
        )
        val RisingWave = VibrationPattern(
            id = "rising-wave",
            name = "Rising wave",
            pulses = listOf(Pulse(200, 80, 0.35f), Pulse(200, 80, 0.55f), Pulse(200, 80, 0.75f), Pulse(260, 600, 1f)),
        )
        val LongShortLong = VibrationPattern(
            id = "long-short-long",
            name = "Long-short-long",
            pulses = listOf(Pulse(380, 140), Pulse(120, 140), Pulse(380, 600)),
        )

        val Presets: List<VibrationPattern> = listOf(Pulse200, TlrTriple, Heartbeat, RisingWave, LongShortLong)

        fun byId(id: String): VibrationPattern = Presets.firstOrNull { it.id == id } ?: Pulse200
    }
}
