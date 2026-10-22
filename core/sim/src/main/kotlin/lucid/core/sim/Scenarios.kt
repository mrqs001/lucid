package lucid.core.sim

import lucid.core.model.SleepStage
import lucid.core.model.SleepStage.DEEP
import lucid.core.model.SleepStage.LIGHT
import lucid.core.model.SleepStage.REM
import lucid.core.model.SleepStage.WAKE
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** A named, seedable night. The same (scenario, seed) pair always yields the same script. */
data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val defaultSeed: Long,
    private val builder: (Random) -> ScenarioScript,
) {
    fun build(seed: Long = defaultSeed): ScenarioScript = builder(Random(seed))
}

object Scenarios {
    val Normal = Scenario(
        id = "normal",
        title = "Normal night",
        description = "8 h, five sleep cycles, REM periods lengthening toward morning.",
        defaultSeed = 1,
    ) { rnd -> night(rnd, hours = 8.0) }

    val Restless = Scenario(
        id = "restless",
        title = "Restless sleep",
        description = "Frequent posture shifts, movement during REM, many brief awakenings.",
        defaultSeed = 2,
    ) { rnd ->
        night(
            rnd, hours = 8.0, briefAwakeningBoost = 0.35,
            profile = PhysiologyProfile(movementScale = mapOf(LIGHT to 4f, REM to 3f, DEEP to 2f, WAKE to 1.5f)),
        )
    }

    val Interrupted = Scenario(
        id = "interrupted",
        title = "Interrupted sleep",
        description = "Three long awakenings (one in the middle of a REM period).",
        defaultSeed = 3,
    ) { rnd ->
        val base = night(rnd, hours = 8.0)
        val firstLateRem = base.hypnogram.filter { it.stage == REM && it.startSec > 3 * 3600 }.first()
        val midRem = (firstLateRem.startSec + firstLateRem.endSec) / 2
        val wakes = listOf(
            2 * 3600 + rnd.nextInt(0, 600) to rnd.nextInt(8, 14) * 60,
            midRem to rnd.nextInt(10, 18) * 60,
            6 * 3600 + rnd.nextInt(0, 900) to rnd.nextInt(15, 25) * 60,
        )
        var script = base
        for ((at, len) in wakes) script = script.withStageOverride(at, at + len, WAKE)
        script.copy(events = script.events + wakes.flatMap { (at, len) -> wakeMovements(rnd, at, at + len) })
    }

    val PoorContact = Scenario(
        id = "poor_contact",
        title = "Poor sensor contact",
        description = "Watch slips off the skin three times, long noisy stretches, sample dropouts.",
        defaultSeed = 4,
    ) { rnd ->
        val base = night(rnd, hours = 8.0, profile = PhysiologyProfile(dropoutRate = 0.03f, baseArtifactRate = 0.01f))
        val losses = listOf(
            SimEvent.ContactLoss(5400, 5400 + rnd.nextInt(5, 12) * 60),
            SimEvent.ContactLoss(15000, 15000 + rnd.nextInt(15, 25) * 60),
            SimEvent.ContactLoss(23500, 23500 + rnd.nextInt(5, 10) * 60),
        )
        val noisy = listOf(
            SimEvent.NoisySignal(9000, 9000 + 1800, artifactRate = 0.08f),
            SimEvent.NoisySignal(19800, 19800 + 2400, artifactRate = 0.12f),
        )
        base.copy(events = base.events + losses + noisy)
    }

    val LongRem = Scenario(
        id = "long_rem",
        title = "Long REM periods",
        description = "8.5 h with 40-55 min REM periods in the second half, for cue-delivery testing.",
        defaultSeed = 5,
    ) { rnd -> night(rnd, hours = 8.5, remBoost = 1.45) }

    val RemConfounders = Scenario(
        id = "rem_confounders",
        title = "REM-like signals outside REM",
        description = "Quiet wakefulness with racing heart, autonomic surges in light sleep: false-positive bait.",
        defaultSeed = 6,
    ) { rnd ->
        val base = night(rnd, hours = 8.0)
        val quietWakeAt = 3 * 3600 + 600
        var script = base.withStageOverride(quietWakeAt, quietWakeAt + 20 * 60, WAKE)
        val light = script.hypnogram.filter { it.stage == LIGHT && it.durationSec >= 15 * 60 && it.startSec > 3600 }
        val surges = light.shuffled(rnd).take(4).map {
            SimEvent.AutonomicSurge(it.startSec + 120, rnd.nextInt(120, 300), bpm = rnd.nextInt(8, 14).toFloat())
        }
        val lightRemLike = light.firstOrNull { it.startSec > 5 * 3600 }?.let {
            SimEvent.RemLikePhysiology(it.startSec + 60, min(it.endSec, it.startSec + 60 + 15 * 60))
        }
        script = script.copy(
            events = script.events.filterNot { it is SimEvent.Movement && it.startSec in quietWakeAt until quietWakeAt + 1200 } +
                SimEvent.RemLikePhysiology(quietWakeAt, quietWakeAt + 20 * 60) + surges + listOfNotNull(lightRemLike),
        )
        script
    }

    val Disconnection = Scenario(
        id = "disconnection",
        title = "Watch-phone disconnection",
        description = "40 min link loss mid-night plus two short blips; the watch buffers and backfills.",
        defaultSeed = 7,
    ) { rnd ->
        val base = night(rnd, hours = 8.0)
        val longStart = 2 * 3600 + 1800
        base.copy(
            events = base.events + listOf(
                SimEvent.LinkDown(longStart, longStart + 40 * 60),
                SimEvent.LinkDown(5 * 3600, 5 * 3600 + rnd.nextInt(30, 90)),
                SimEvent.LinkDown(6 * 3600 + 1200, 6 * 3600 + 1200 + rnd.nextInt(30, 90)),
            ),
        )
    }

    val all: List<Scenario> = listOf(Normal, Restless, Interrupted, PoorContact, LongRem, RemConfounders, Disconnection)

    fun byId(id: String): Scenario = all.firstOrNull { it.id == id } ?: error("unknown scenario '$id'")

    /**
     * Procedural sleep architecture: sleep-onset latency, ~90 min cycles, deep sleep front-loaded,
     * REM periods lengthening across the night, brief awakenings after late REM periods.
     */
    fun night(
        rnd: Random,
        hours: Double,
        remBoost: Double = 1.0,
        briefAwakeningBoost: Double = 0.0,
        profile: PhysiologyProfile = PhysiologyProfile(),
    ): ScenarioScript {
        val duration = (hours * 3600).roundToInt()
        val segments = mutableListOf<StageSegment>()
        val events = mutableListOf<SimEvent>()
        var t = 0

        fun add(stage: SleepStage, seconds: Int) {
            val end = min(duration, t + max(30, seconds))
            if (end <= t) return
            if (segments.isNotEmpty() && segments.last().stage == stage) {
                segments[segments.lastIndex] = segments.last().copy(endSec = end)
            } else {
                segments += StageSegment(t, end, stage)
            }
            t = end
        }

        val finalWake = rnd.nextInt(4, 10) * 60
        add(WAKE, rnd.nextInt(8, 22) * 60)
        var cycle = 0
        while (t < duration - finalWake - 20 * 60) {
            val cycleLen = rnd.gaussian(95.0, 10.0).coerceIn(75.0, 120.0) * 60
            val boost = if (cycle >= 2) remBoost else 1.0
            val rem = (min(10.0 + 7.5 * cycle, 38.0) * boost * rnd.gaussian(1.0, 0.15).coerceIn(0.6, 1.4) * 60)
                .coerceAtMost(cycleLen * 0.7)
            val deep = (max(0.0, 38.0 - 13.0 * cycle) * rnd.gaussian(1.0, 0.2).coerceIn(0.5, 1.5) * 60)
            val light = max(15.0 * 60, cycleLen - rem - deep)
            val lightBefore = light * rnd.nextDouble(0.55, 0.75)
            add(LIGHT, lightBefore.roundToInt())
            if (deep > 60) add(DEEP, deep.roundToInt())
            add(LIGHT, (light - lightBefore).roundToInt())
            // Posture shifts often precede REM onset.
            if (rnd.nextDouble() < 0.5) events += SimEvent.Movement(t - rnd.nextInt(20, 90), rnd.nextInt(3, 8), rnd.nextFloat(0.5f, 1f))
            add(REM, rem.roundToInt())
            if (rnd.nextDouble() < 0.6) events += SimEvent.Movement(t, rnd.nextInt(3, 10), rnd.nextFloat(0.5f, 1f))
            if (rnd.nextDouble() < 0.25 + 0.12 * cycle + briefAwakeningBoost) {
                val start = t
                add(WAKE, rnd.nextInt(1, 5) * 60)
                events += wakeMovements(rnd, start, t)
            }
            cycle++
        }
        if (t < duration - finalWake) add(LIGHT, duration - finalWake - t)
        val wakeStart = t
        add(WAKE, duration - t)
        events += wakeMovements(rnd, wakeStart, duration)
        events += wakeMovements(rnd, 0, segments.first().endSec)
        events += lightSleepMovements(rnd, segments, profile)
        return ScenarioScript(duration, segments, events.filter { it.startSec in 0 until duration }.sortedBy { it.startSec }, profile)
    }

    private fun wakeMovements(rnd: Random, from: Int, to: Int): List<SimEvent> {
        val out = mutableListOf<SimEvent>()
        var s = from + rnd.nextInt(10, 60)
        while (s < to) {
            out += SimEvent.Movement(s, rnd.nextInt(2, 12), rnd.nextFloat(0.3f, 1f))
            s += rnd.nextInt(40, 180)
        }
        return out
    }

    private fun lightSleepMovements(rnd: Random, segments: List<StageSegment>, profile: PhysiologyProfile): List<SimEvent> {
        val meanGapMin = mapOf(LIGHT to 20.0, DEEP to 90.0, REM to 45.0)
        val out = mutableListOf<SimEvent>()
        for (seg in segments) {
            val gap = meanGapMin[seg.stage] ?: continue
            val scale = profile.movementScale[seg.stage] ?: 1f
            var s = seg.startSec + (rnd.exponential(gap / scale) * 60).roundToInt()
            while (s < seg.endSec) {
                out += SimEvent.Movement(s, rnd.nextInt(2, 7), rnd.nextFloat(0.3f, 0.9f))
                s += (rnd.exponential(gap / scale) * 60).roundToInt().coerceAtLeast(30)
            }
        }
        return out
    }
}

/** Replace the stage between [from] and [to] (seconds), splitting segments as needed. */
fun ScenarioScript.withStageOverride(from: Int, to: Int, stage: SleepStage): ScenarioScript {
    val end = min(to, durationSec)
    val pieces = mutableListOf(StageSegment(from, end, stage))
    for (seg in hypnogram) {
        if (seg.startSec < from) pieces += seg.copy(endSec = min(seg.endSec, from))
        if (seg.endSec > end) pieces += seg.copy(startSec = max(seg.startSec, end))
    }
    val merged = mutableListOf<StageSegment>()
    for (seg in pieces.sortedBy { it.startSec }) {
        val last = merged.lastOrNull()
        if (last != null && last.stage == seg.stage && last.endSec == seg.startSec) {
            merged[merged.lastIndex] = last.copy(endSec = seg.endSec)
        } else {
            merged += seg
        }
    }
    return copy(hypnogram = merged)
}

internal fun Random.gaussian(mean: Double, sd: Double): Double {
    // Box-Muller; deterministic for a given Random state.
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return mean + sd * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}

internal fun Random.exponential(mean: Double): Double = -mean * kotlin.math.ln(nextDouble().coerceAtLeast(1e-12))

internal fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * (until - from)
