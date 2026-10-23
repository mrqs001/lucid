package lucid.core.eval

import kotlinx.serialization.Serializable
import lucid.core.engine.CueAction
import lucid.core.engine.EngineConfig
import java.io.File

/*
 * Tuning the cue rules on the lab nights, with the stage model fixed (held-out predictions only).
 * Goal: reach more late REM periods without more wrong cues per night than the current rules.
 * The safety caps (spacing, per-period and per-night limits, earliest time) are not tuned.
 */

@Serializable
data class CueRules(
    val remThreshold: Float,
    val minSustainedRemMin: Float,
    val minConfidence: Float,
    val maxWakeProbability: Float,
    val motionLockoutMin: Float,
) {
    fun applyTo(c: EngineConfig) = c.copy(
        cueing = c.cueing.copy(
            remThreshold = remThreshold, minSustainedRemMin = minSustainedRemMin, minConfidence = minConfidence,
            maxWakeProbability = maxWakeProbability, motionLockoutMin = motionLockoutMin,
        ),
    )

    companion object {
        fun of(c: EngineConfig) = with(c.cueing) { CueRules(remThreshold, minSustainedRemMin, minConfidence, maxWakeProbability, motionLockoutMin) }
    }
}

@Serializable
data class RuleScore(
    val rules: CueRules,
    val nights: Int,
    val cues: Int,
    /** Would-be cues by PSG stage: wake, light, deep, REM. */
    val byStage: List<Int>,
    val precision: Double,
    val wrongPerNight: Double,
    /** Late REM periods (at least 5 min of REM after the earliest cue time) that got a cue inside REM. */
    val periodsReached: Int,
    val periods: Int,
    val coverage: Double,
)

@Serializable
data class CueRuleReport(
    val selection: String,
    val current: RuleScore,
    val chosenOnAllNights: RuleScore,
    /** Each night scored with rules chosen on the other 30 nights: the honest estimate. */
    val heldOutTuned: RuleScore,
    val heldOutRuleSets: Int,
    /** The same with up to 0.1 more wrong cues per night allowed (about one every ten nights). */
    val chosenWithTolerance: RuleScore,
    val heldOutWithTolerance: RuleScore,
    val heldOutWithToleranceRuleSets: Int,
    val top: List<RuleScore>,
)

/** Late REM periods of a lab night: REM epochs merged across gaps of up to 15 min. */
fun lateRemPeriods(s: WalchSubject, fromHours: Double): List<IntRange> {
    val epochs = s.labels.keys.sorted()
    val onset = epochs.firstOrNull { s.labels.getValue(it).let(::psgToStage).let { st -> st != null && st != 0 } } ?: return emptyList()
    val rem = epochs.filter { psgToStage(s.labels.getValue(it)) == 3 }
    val periods = ArrayList<IntRange>()
    var start = -1
    var last = -1
    for (e in rem) {
        if (start >= 0 && e - last > 30) {
            periods += start..last
            start = -1
        }
        if (start < 0) start = e
        last = e
    }
    if (start >= 0) periods += start..last
    val from = onset + (fromHours * 120).toInt()
    return periods.filter { p -> rem.count { it in p && it >= from } >= 10 }
}

/** Extra wrong cues per night the tolerant selection accepts. */
private const val TOLERANCE = 0.1

private class Outcome(val byStage: IntArray, val reached: Int, val periods: Int)

fun tuneCueRules(
    subjects: List<WalchSubject>,
    run: (index: Int, config: EngineConfig) -> ReplayOutput,
    base: EngineConfig,
    out: File,
): CueRuleReport {
    val current = CueRules.of(base)
    val grid = buildList {
        for (t in listOf(0.5f, 0.6f, 0.7f, 0.8f)) for (s in listOf(2f, 3f, 5f, 7f, 10f)) for (c in listOf(0.3f, 0.45f, 0.6f))
            for (w in listOf(0.1f, 0.2f, 0.3f)) for (m in listOf(1f, 2f, 4f)) add(CueRules(t, s, c, w, m))
    }.let { (it + current).distinct() }
    val periods = subjects.map { lateRemPeriods(it, base.cueing.minMinutesAfterSleepOnset / 60.0) }

    val outcomes: List<List<Outcome>> = grid.parallelStream().map { rules ->
        subjects.indices.map { i ->
            val byStage = IntArray(4)
            val cueEpochs = HashSet<Int>()
            for (d in run(i, rules.applyTo(base)).decisions) if (d.action == CueAction.WOULD_DELIVER) {
                val st = subjects[i].labels[d.epochIndex]?.let(::psgToStage) ?: continue
                byStage[st]++
                if (st == 3) cueEpochs += d.epochIndex
            }
            Outcome(byStage, periods[i].count { p -> cueEpochs.any { it in p } }, periods[i].size)
        }
    }.toList()

    fun score(k: Int, nights: Collection<Int>): RuleScore {
        val byStage = IntArray(4)
        var reached = 0
        var total = 0
        for (i in nights) {
            val o = outcomes[k][i]
            for (s in 0 until 4) byStage[s] += o.byStage[s]
            reached += o.reached
            total += o.periods
        }
        val cues = byStage.sum()
        val wrong = cues - byStage[3]
        return RuleScore(
            grid[k], nights.size, cues, byStage.toList(), if (cues == 0) Double.NaN else byStage[3].toDouble() / cues,
            wrong.toDouble() / nights.size, reached, total, if (total == 0) Double.NaN else reached.toDouble() / total,
        )
    }

    val currentIndex = grid.indexOf(current)
    // Most late REM periods reached without more wrong cues than the current rules (plus [tolerance]
    // per night); then fewer wrong cues.
    fun select(nights: Collection<Int>, tolerance: Double = 0.0): Int {
        val limit = score(currentIndex, nights).wrongPerNight + tolerance
        return grid.indices.map { score(it, nights) to it }
            .filter { it.first.wrongPerNight <= limit + 1e-9 }
            .sortedWith(compareByDescending<Pair<RuleScore, Int>> { it.first.periodsReached }.thenBy { it.first.wrongPerNight }.thenByDescending { it.first.byStage[3] })
            .first().second
    }

    val all = subjects.indices.toList()
    // Held out: every night is scored with rules picked on the other 30.
    fun heldOut(tolerance: Double): Pair<RuleScore, Int> {
        val picks = subjects.indices.map { i -> select(all - i, tolerance) }
        val byStage = IntArray(4)
        var reached = 0
        var total = 0
        for (i in subjects.indices) {
            val o = outcomes[picks[i]][i]
            for (s in 0 until 4) byStage[s] += o.byStage[s]
            reached += o.reached
            total += o.periods
        }
        val cues = byStage.sum()
        val mostPicked = picks.groupingBy { it }.eachCount().maxBy { it.value }.key
        return RuleScore(
            grid[mostPicked], subjects.size, cues, byStage.toList(), byStage[3].toDouble() / cues,
            (cues - byStage[3]).toDouble() / subjects.size, reached, total, reached.toDouble() / total,
        ) to picks.distinct().size
    }
    val chosen = select(all)
    val (heldOut, heldOutSets) = heldOut(0.0)
    val chosenTol = select(all, TOLERANCE)
    val (heldOutTol, heldOutTolSets) = heldOut(TOLERANCE)

    File(out, "cue-rules.csv").printWriter().use { w ->
        w.println("rem_threshold,sustained_min,min_confidence,max_p_wake,motion_lockout_min,cues,in_rem,precision,wrong_per_night,periods_reached,periods,coverage")
        for (k in grid.indices) {
            val s = score(k, all)
            val r = s.rules
            w.println("${r.remThreshold},${r.minSustainedRemMin},${r.minConfidence},${r.maxWakeProbability},${r.motionLockoutMin},${s.cues},${s.byStage[3]},${"%.4f".format(s.precision)},${"%.4f".format(s.wrongPerNight)},${s.periodsReached},${s.periods},${"%.4f".format(s.coverage)}")
        }
    }

    val limit = score(currentIndex, all).wrongPerNight
    return CueRuleReport(
        selection = "most late REM periods reached with no more wrong cues per night than the current rules",
        current = score(currentIndex, all),
        chosenOnAllNights = score(chosen, all),
        heldOutTuned = heldOut,
        heldOutRuleSets = heldOutSets,
        chosenWithTolerance = score(chosenTol, all),
        heldOutWithTolerance = heldOutTol,
        heldOutWithToleranceRuleSets = heldOutTolSets,
        top = grid.indices.map { score(it, all) }.filter { it.wrongPerNight <= limit + 1e-9 }
            .sortedWith(compareByDescending<RuleScore> { it.periodsReached }.thenBy { it.wrongPerNight }).take(10),
    )
}
