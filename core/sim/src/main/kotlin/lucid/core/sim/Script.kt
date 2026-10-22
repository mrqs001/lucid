package lucid.core.sim

import lucid.core.model.SleepStage

/** A stretch of simulated time spent in one ground-truth stage. Times are seconds from start. */
data class StageSegment(val startSec: Int, val endSec: Int, val stage: SleepStage) {
    val durationSec: Int get() = endSec - startSec
}

/** Things that happen during a simulated night besides the stage sequence. */
sealed interface SimEvent {
    val startSec: Int
    val endSec: Int

    fun activeAt(sec: Int): Boolean = sec in startSec until endSec

    /** Gross body movement (posture shift, arm movement). */
    data class Movement(override val startSec: Int, val durationSec: Int, val intensity: Float) : SimEvent {
        override val endSec: Int get() = startSec + durationSec
    }

    /** Heart-rate surge without movement, e.g. an autonomic arousal in NREM. */
    data class AutonomicSurge(override val startSec: Int, val durationSec: Int, val bpm: Float) : SimEvent {
        override val endSec: Int get() = startSec + durationSec
    }

    /** Lying still with irregular, elevated heart rate: physiology that resembles REM. */
    data class RemLikePhysiology(override val startSec: Int, override val endSec: Int) : SimEvent

    /** Watch not touching the skin: no heart data at all. */
    data class ContactLoss(override val startSec: Int, override val endSec: Int) : SimEvent

    /** Loose strap or motion artefacts: noisy heart rate and corrupted inter-beat intervals. */
    data class NoisySignal(override val startSec: Int, override val endSec: Int, val artifactRate: Float) : SimEvent

    /** Watch and phone cannot talk. The watch keeps measuring and buffers. */
    data class LinkDown(override val startSec: Int, override val endSec: Int) : SimEvent
}

/** Person-level physiology the generator works from. */
data class PhysiologyProfile(
    val restingHr: Float = 58f,
    /** Heart rate drift across the night (negative = slows down), bpm per hour. */
    val hrDriftPerHour: Float = -0.6f,
    /** Random sample dropout (no HR sample emitted for a second). */
    val dropoutRate: Float = 0.003f,
    /** Baseline inter-beat-interval artefact probability per beat. */
    val baseArtifactRate: Float = 0.004f,
    /** Movement-rate multipliers per stage (1 = typical). */
    val movementScale: Map<SleepStage, Float> = emptyMap(),
)

/** A complete, deterministic description of one simulated night. */
data class ScenarioScript(
    val durationSec: Int,
    val hypnogram: List<StageSegment>,
    val events: List<SimEvent>,
    val profile: PhysiologyProfile = PhysiologyProfile(),
) {
    init {
        require(hypnogram.isNotEmpty()) { "empty hypnogram" }
        require(hypnogram.first().startSec == 0) { "hypnogram must start at 0" }
        hypnogram.zipWithNext().forEach { (a, b) -> require(a.endSec == b.startSec) { "gap in hypnogram at ${a.endSec}" } }
        require(hypnogram.last().endSec == durationSec) { "hypnogram must end at durationSec" }
    }

    fun stageAt(sec: Int): SleepStage {
        // Binary search: hypnograms have ~50-100 segments and this runs once per simulated second.
        var lo = 0
        var hi = hypnogram.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (hypnogram[mid].startSec <= sec) lo = mid else hi = mid - 1
        }
        return hypnogram[lo].stage
    }

    inline fun <reified T : SimEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    fun totalSec(stage: SleepStage): Int = hypnogram.filter { it.stage == stage }.sumOf { it.durationSec }
}
