package lucid.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lucid.core.engine.CueAction
import lucid.core.engine.CueMode
import lucid.core.engine.EngineConfig
import lucid.core.engine.FeatureSpec
import lucid.core.engine.FeatureValues
import lucid.core.engine.HmmSpec
import lucid.core.engine.HmmStageEstimator
import lucid.core.engine.TransitionSpec
import lucid.core.model.Wire
import java.io.File
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/*
 * Fitting and testing the stage model on the Walch et al. (2019) sleep-accel dataset:
 * 31 PSG-scored nights with Apple Watch heart rate and accelerometer (PhysioNet, ODC-By).
 *
 *   cargo run --release --manifest-path tools/walch-prep/Cargo.toml -- datasets/walch datasets/walch-prepared
 *   ./gradlew :core:eval:run --args="walch datasets/walch-prepared docs/model"
 *
 * Every night goes through the real pipeline (SessionProcessor), so features are computed exactly
 * as in the app. Models are tested leave-one-subject-out: fitted on 30 nights, scored on the 31st.
 */

private const val W = 0
private const val L = 1
private const val D = 2
private const val R = 3
private val STAGE_NAMES = listOf("wake", "light", "deep", "rem")

/** PSG stage (0 wake, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM, -1 unscored) -> our four classes. */
fun psgToStage(psg: Int): Int? = when (psg) {
    0 -> W
    1, 2 -> L
    3, 4 -> D
    5 -> R
    else -> null
}

class WalchSubject(val id: String, val input: ReplayInput, val labels: Map<Int, Int>)

fun loadWalch(dir: File): List<WalchSubject> {
    val json = Json(Wire.json) { ignoreUnknownKeys = true }
    return dir.listFiles { f -> f.name.endsWith(".labels.csv") }!!.sortedBy { it.name }.map { lf ->
        val id = lf.name.removeSuffix(".labels.csv")
        val labels = lf.readLines().drop(1).filter { it.isNotBlank() }.associate { line ->
            val (e, s) = line.split(",")
            e.toInt() to s.toInt()
        }
        WalchSubject(id, json.decodeFromString(ReplayInput.serializer(), File(dir, "$id.json").readText()), labels)
    }
}

/** Everything one model produced for one night, lined up with the PSG labels. */
class NightEval(val subject: WalchSubject, val out: ReplayOutput) {
    val truth: List<Int?> = out.epochs.map { e -> subject.labels[e.features.index]?.let(::psgToStage) }
    val predicted: List<Int> = out.epochs.map { e -> e.prediction.probabilities.indices.maxBy { e.prediction.probabilities[it] } }
    val pRem: List<Float> = out.epochs.map { it.prediction.pRem }
}

@Serializable
data class Metrics(
    val epochs: Int,
    val accuracy: Double,
    val kappa: Double,
    val confusion: List<List<Int>>, // [truth][predicted], order wake, light, deep, rem
    val sensitivity: Map<String, Double>,
    val precision: Map<String, Double>,
    val remF1: Double,
    /** Epochs inside >= 5 min runs of p(REM) >= 0.7 (what the cue policy waits for). */
    val sustainedRemEpochs: Int,
    val sustainedRemPrecision: Double,
    val sustainedRemCoverage: Double,
    /** Cue-policy decisions with all safety gates on (baseline mode: logged, not sent). */
    val wouldCue: Int,
    val wouldCueInRem: Double,
    /** PSG stage at each of those decisions. */
    val wouldCueByStage: Map<String, Int>,
    /** Same policy without the "5 h after onset" gate, to get enough decisions to measure. */
    val wouldCueAllNight: Int,
    val wouldCueAllNightInRem: Double,
    /** The same, by whole hours since the estimated sleep onset. */
    val wouldCueAllNightByHour: List<CueHour>,
)

@Serializable
data class CueHour(val hoursSinceOnset: Int, val cues: Int, val inRem: Double)

private fun confusion(nights: List<NightEval>): Array<IntArray> {
    val m = Array(4) { IntArray(4) }
    for (n in nights) for (i in n.truth.indices) n.truth[i]?.let { t -> m[t][n.predicted[i]]++ }
    return m
}

private fun sustainedMask(p: List<Float>, threshold: Float = 0.7f, minEpochs: Int = 10): BooleanArray {
    val mask = BooleanArray(p.size)
    var start = -1
    for (i in 0..p.size) {
        val on = i < p.size && p[i] >= threshold
        if (on && start < 0) start = i
        if (!on && start >= 0) {
            if (i - start >= minEpochs) for (k in start until i) mask[k] = true
            start = -1
        }
    }
    return mask
}

fun metrics(nights: List<NightEval>, cueRuns: List<ReplayOutput>, allNightRuns: List<ReplayOutput>): Metrics {
    val m = confusion(nights)
    val total = m.sumOf { it.sum() }.toDouble()
    val diag = (0 until 4).sumOf { m[it][it] }.toDouble()
    val po = diag / total
    val pe = (0 until 4).sumOf { i -> m[i].sum() / total * (0 until 4).sumOf { m[it][i] } / total }
    val sens = (0 until 4).associate { STAGE_NAMES[it] to m[it][it] / m[it].sum().coerceAtLeast(1).toDouble() }
    val prec = (0 until 4).associate { STAGE_NAMES[it] to m[it][it] / (0 until 4).sumOf { r -> m[r][it] }.coerceAtLeast(1).toDouble() }
    val rs = sens.getValue("rem")
    val rp = prec.getValue("rem")

    var sustained = 0
    var sustainedHit = 0
    var remTotal = 0
    var remCovered = 0
    for (n in nights) {
        val mask = sustainedMask(n.pRem)
        for (i in mask.indices) {
            val t = n.truth[i] ?: continue
            if (mask[i]) {
                sustained++
                if (t == R) sustainedHit++
            }
            if (t == R) {
                remTotal++
                if (mask[i]) remCovered++
            }
        }
    }

    fun cueStages(runs: List<ReplayOutput>): IntArray {
        val byStage = IntArray(4)
        for ((run, night) in runs.zip(nights)) {
            for (d in run.decisions) if (d.action == CueAction.WOULD_DELIVER) {
                night.subject.labels[d.epochIndex]?.let(::psgToStage)?.let { byStage[it]++ }
            }
        }
        return byStage
    }
    val cueBy = cueStages(cueRuns)
    val cueAllBy = cueStages(allNightRuns)
    fun remShare(c: IntArray) = if (c.sum() == 0) Double.NaN else c[R].toDouble() / c.sum()
    val byHour = sortedMapOf<Int, IntArray>()
    for ((run, night) in allNightRuns.zip(nights)) {
        for (d in run.decisions) if (d.action == CueAction.WOULD_DELIVER) {
            val t = night.subject.labels[d.epochIndex]?.let(::psgToStage) ?: continue
            val bin = byHour.getOrPut(d.hoursSinceOnset?.toInt() ?: -1) { IntArray(2) }
            bin[0]++
            if (t == R) bin[1]++
        }
    }

    return Metrics(
        epochs = total.toInt(),
        accuracy = po,
        kappa = if (pe >= 1.0) 0.0 else (po - pe) / (1 - pe),
        confusion = m.map { it.toList() },
        sensitivity = sens,
        precision = prec,
        remF1 = if (rs + rp == 0.0) 0.0 else 2 * rs * rp / (rs + rp),
        sustainedRemEpochs = sustained,
        sustainedRemPrecision = if (sustained == 0) Double.NaN else sustainedHit.toDouble() / sustained,
        sustainedRemCoverage = if (remTotal == 0) Double.NaN else remCovered.toDouble() / remTotal,
        wouldCue = cueBy.sum(),
        wouldCueInRem = remShare(cueBy),
        wouldCueByStage = STAGE_NAMES.zip(cueBy.toList()).toMap(),
        wouldCueAllNight = cueAllBy.sum(),
        wouldCueAllNightInRem = remShare(cueAllBy),
        wouldCueAllNightByHour = byHour.map { (h, c) -> CueHour(h, c[0], c[1].toDouble() / c[0]) },
    )
}

/** Supervised fit: per-stage feature distributions and hour-banded transitions from PSG hypnograms. */
fun fitSpec(nights: List<NightEval>, base: HmmSpec, id: String, description: String): HmmSpec {
    val features = base.features.mapNotNull { spec ->
        val extract = FeatureValues.of(spec.name)
        val byStage = List(4) { ArrayList<Double>() }
        for (n in nights) for ((i, e) in n.out.epochs.withIndex()) {
            val t = n.truth[i] ?: continue
            if (e.features.quality < 0.5f) continue
            extract(e.features)?.let { byStage[t] += it }
        }
        if (byStage.any { it.size < 30 }) return@mapNotNull null // not observable in this dataset
        when (spec) {
            is FeatureSpec.Gaussian -> {
                val all = byStage.flatten()
                val overallSd = sd(all)
                spec.copy(
                    means = byStage.map { it.average() },
                    sds = byStage.map { maxOf(sd(it), 0.1 * overallSd, 1e-3) },
                )
            }
            is FeatureSpec.Categorical -> spec.copy(
                table = byStage.map { values ->
                    val counts = DoubleArray(spec.edges.size + 1) { 1.0 } // Laplace smoothing
                    for (v in values) counts[spec.edges.count { v >= it }] += 1.0
                    val sum = counts.sum()
                    counts.map { it / sum }
                },
            )
        }
    }

    val hourEdges = (1..7).map { it.toDouble() }
    val pre = Array(4) { DoubleArray(4) { 0.5 } }
    val byHour = Array(hourEdges.size + 1) { Array(4) { DoubleArray(4) { 0.5 } } }
    val initial = DoubleArray(4) { 1.0 }
    for (s in nights.map { it.subject }) {
        val keys = s.labels.keys.sorted()
        val stages = keys.associateWith { s.labels.getValue(it).let(::psgToStage) }
        keys.firstOrNull()?.let { k -> stages[k]?.let { initial[it] += 1.0 } }
        val onset = keys.firstOrNull { (stages[it] ?: W) != W } ?: continue
        for (k in keys) {
            val a = stages[k] ?: continue
            val b = stages[k + 1] ?: continue
            if (k < onset) {
                pre[a][b] += 1.0
            } else {
                val hours = (k - onset) * 30.0 / 3600.0
                byHour[hourEdges.count { hours >= it }][a][b] += 1.0
            }
        }
    }
    fun normalise(m: Array<DoubleArray>) = m.map { row -> row.sum().let { s -> row.map { it / s } } }
    return HmmSpec(
        id = id,
        description = description,
        initial = initial.sum().let { s -> initial.map { it / s } },
        features = features,
        transitions = TransitionSpec.Binned(normalise(pre), hourEdges, byHour.map(::normalise)),
        maxLogLrPerFeature = base.maxLogLrPerFeature,
    )
}

private fun sd(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = xs.average()
    return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
}

/** Mean ln p(PSG stage): a proper score, so it rewards calibrated probabilities, not just the top stage. */
fun logScore(nights: List<NightEval>): Double {
    var sum = 0.0
    var n = 0
    for (night in nights) for ((i, e) in night.out.epochs.withIndex()) {
        val t = night.truth[i] ?: continue
        sum += ln(maxOf(e.prediction.probabilities[t].toDouble(), 1e-4))
        n++
    }
    return sum / n
}

/** The same features with every Gaussian replaced by a histogram on decile edges (no shape assumption). */
fun histogramBase(train: List<NightEval>, base: HmmSpec): HmmSpec = base.copy(
    features = base.features.map { spec ->
        if (spec !is FeatureSpec.Gaussian) return@map spec
        val extract = FeatureValues.of(spec.name)
        val values = train.flatMap { n -> n.out.epochs.mapNotNull { extract(it.features) } }.sorted()
        if (values.isEmpty()) return@map spec
        val edges = (1..9).map { round(values[it * values.size / 10] * 100) / 100 }.distinct()
        FeatureSpec.Categorical(spec.name, spec.weight, edges, List(4) { List(edges.size + 1) { 1.0 / (edges.size + 1) } })
    },
)

private fun FeatureSpec.withWeight(w: Double): FeatureSpec = when (this) {
    is FeatureSpec.Gaussian -> copy(weight = w)
    is FeatureSpec.Categorical -> copy(weight = w)
}

/**
 * Coordinate ascent on the feature weights (how far each feature's evidence is trusted; they overlap,
 * so plain naive Bayes would be overconfident), maximising the log score on the training nights only.
 */
fun tuneWeights(train: List<NightEval>, spec: HmmSpec, run: (WalchSubject, HmmSpec) -> ReplayOutput): HmmSpec {
    fun score(s: HmmSpec) = logScore(train.parallelStream().map { NightEval(it.subject, run(it.subject, s)) }.toList())
    val grid = listOf(0.0, 0.1, 0.15, 0.2, 0.25, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0)
    var best = spec
    var bestScore = score(best)
    repeat(2) {
        for (i in best.features.indices) for (w in grid) {
            if (best.features[i].weight == w) continue
            val candidate = best.copy(features = best.features.mapIndexed { j, f -> if (j == i) f.withWeight(w) else f })
            val s = score(candidate)
            if (s > bestScore + 1e-4) {
                best = candidate
                bestScore = s
            }
        }
    }
    return best
}

/** One way of building a model from training nights. */
class Variant(val name: String, val fit: (train: List<NightEval>) -> HmmSpec)

@Serializable
data class SubjectResult(val id: String, val epochs: Int, val remEpochs: Int, val handSetKappa: Double, val chosenKappa: Double, val chosenRemF1: Double)

@Serializable
data class WalchReport(
    val dataset: String,
    val subjects: Int,
    val evaluation: String,
    val models: Map<String, Metrics>,
    val logScores: Map<String, Double>,
    val chosen: String,
    val perSubject: List<SubjectResult>,
)

fun runWalch(prepared: String, outDir: String) {
    val subjects = loadWalch(File(prepared))
    val out = File(outDir).apply { mkdirs() }
    println("${subjects.size} nights loaded")

    val cueConfig = EngineConfig().let { it.copy(cueing = it.cueing.copy(mode = CueMode.BASELINE)) }
    val cueAllNight = cueConfig.copy(cueing = cueConfig.cueing.copy(minMinutesAfterSleepOnset = 0f))
    fun fullReplay(s: WalchSubject, spec: HmmSpec, cfg: EngineConfig = cueConfig) =
        replay(s.input.copy(config = cfg), estimator = HmmStageEstimator(spec.toParams()))

    // Features do not depend on the model, so one full pass per night gives the features for fitting
    // and for re-running every candidate model quickly.
    val handSet = subjects.parallelStream().map { NightEval(it, fullReplay(it, HmmSpec.HandSet)) }.toList()
    val features = handSet.associate { it.subject.id to it.out }
    fun run(s: WalchSubject, spec: HmmSpec, cfg: EngineConfig = cueConfig) =
        replayFeatures(s.input.sessionStartMs, features.getValue(s.id), cfg, HmmStageEstimator(spec.toParams()))
    val runDefault: (WalchSubject, HmmSpec) -> ReplayOutput = { s, spec -> run(s, spec) }

    // The shortcut must reproduce the full pipeline exactly; check on every night with a fitted model.
    val checkSpec = fitSpec(handSet, HmmSpec.HandSet, "check", "")
    for (cfg in listOf(cueConfig, cueAllNight)) {
        val mismatched = subjects.parallelStream().filter { s ->
            val a = fullReplay(s, checkSpec, cfg)
            val b = run(s, checkSpec, cfg)
            fun cues(o: ReplayOutput) = o.decisions.map { it.copy(cueId = null) } // ids are random
            a.epochs.map { it.prediction } != b.epochs.map { it.prediction } || cues(a) != cues(b) || a.sleepOnsetMs != b.sleepOnsetMs
        }.map { it.id }.toList()
        check(mismatched.isEmpty()) { "fast replay differs from the full pipeline on $mismatched" }
    }
    println("fast replay matches the full pipeline on all ${subjects.size} nights")

    val variants = listOf(
        Variant("hand-set") { HmmSpec.HandSet },
        Variant("fitted") { fitSpec(it, HmmSpec.HandSet, "loso", "") },
        Variant("fitted, hand-set transitions") { fitSpec(it, HmmSpec.HandSet, "loso", "").copy(transitions = TransitionSpec.Heuristic) },
        Variant("fitted, histograms") { fitSpec(it, histogramBase(it, HmmSpec.HandSet), "loso", "") },
        Variant("fitted, tuned weights") { tuneWeights(it, fitSpec(it, HmmSpec.HandSet, "loso", ""), runDefault) },
        Variant("fitted, histograms, tuned weights") { tuneWeights(it, fitSpec(it, histogramBase(it, HmmSpec.HandSet), "loso", ""), runDefault) },
    )

    // Leave one subject out: fit on the other 30 nights, score the 31st.
    class Loso(val nights: List<NightEval>, val allNight: List<ReplayOutput>, val specs: List<HmmSpec>)
    val results = LinkedHashMap<String, Loso>()
    for (v in variants) {
        val started = System.nanoTime()
        val specs = subjects.map { s -> v.fit(handSet.filter { it.subject.id != s.id }) }
        val nights = subjects.indices.toList().parallelStream().map { NightEval(subjects[it], run(subjects[it], specs[it])) }.toList()
        val allNight = subjects.indices.toList().parallelStream().map { run(subjects[it], specs[it], cueAllNight) }.toList()
        results[v.name] = Loso(nights, allNight, specs)
        println("%-36s log score %.4f  (%.0f s)".format(v.name, logScore(nights), (System.nanoTime() - started) / 1e9))
    }
    val metricsBy = results.mapValues { (_, r) -> metrics(r.nights, r.nights.map { it.out }, r.allNight) }

    // The shipped model is the variant with the best held-out log score, refitted on all 31 nights.
    val chosen = results.keys.filter { it != "hand-set" }.maxBy { logScore(results.getValue(it).nights) }
    val final = variants.first { it.name == chosen }.fit(handSet).copy(
        id = "hmm-walch-v1",
        description = "HMM forward filter fitted on 31 PSG nights (Walch et al. 2019, Apple Watch HR + accelerometer; $chosen). " +
            "Not yet validated on Galaxy Watch data.",
    )
    val best = results.getValue(chosen).nights

    val report = WalchReport(
        dataset = "Walch et al. 2019, sleep-accel 1.0.0 (PhysioNet): Apple Watch heart rate (~0.2 Hz, interpolated to 1 Hz) and accelerometer, PSG labels",
        subjects = subjects.size,
        evaluation = "leave-one-subject-out; 4 classes (wake, light = N1+N2, deep = N3/N4, REM); unscored epochs excluded",
        models = metricsBy,
        logScores = results.mapValues { logScore(it.value.nights) },
        chosen = chosen,
        perSubject = subjects.indices.map { i ->
            val c = metrics(listOf(best[i]), emptyList(), emptyList())
            SubjectResult(
                subjects[i].id, best[i].truth.count { it != null }, best[i].truth.count { it == R },
                metrics(listOf(handSet[i]), emptyList(), emptyList()).kappa, c.kappa, c.remF1,
            )
        },
    )
    File(out, "hmm-walch-v1.json").writeText(HmmSpec.json.encodeToString(HmmSpec.serializer(), final))
    val prettyJson = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    File(out, "walch-report.json").writeText(prettyJson.encodeToString(WalchReport.serializer(), report))
    File(out, "walch-fold-weights.csv").printWriter().use { w ->
        for ((name, r) in results) if ("tuned" in name) {
            w.println("variant,left_out," + r.specs.first().features.joinToString(",") { it.name })
            r.specs.forEachIndexed { i, spec -> w.println("\"$name\",${subjects[i].id}," + spec.features.joinToString(",") { it.weight.toString() }) }
        }
    }

    // Per-epoch table for exploration: features plus held-out predictions.
    File(out, "walch-epochs.csv").printWriter().use { w ->
        w.println(
            "subject,epoch,hours_from_psg_start,psg,stage,hr_mean,hr_rel,hr_instability,hr_instability_rel,activity_mg,active_s,minutes_still," +
                "quality,p_rem_handset,stage_handset,p_wake,p_light,p_deep,p_rem,stage_model",
        )
        for (i in subjects.indices) {
            val h = handSet[i]
            val c = best[i]
            for ((k, e) in h.out.epochs.withIndex()) {
                val ft = e.features
                val psg = subjects[i].labels[ft.index]
                val p = c.out.epochs.getOrNull(k)?.prediction?.probabilities
                w.println(
                    listOf(
                        subjects[i].id, ft.index, "%.4f".format(ft.index * 30 / 3600.0), psg, psg?.let(::psgToStage)?.let(STAGE_NAMES::get),
                        ft.hrMean, ft.hrRel, ft.hrInstability, ft.hrInstabilityRel, ft.activityMg, ft.activeSeconds, ft.minutesSinceMovement,
                        ft.quality, h.pRem[k], STAGE_NAMES[h.predicted[k]], p?.get(0), p?.get(1), p?.get(2), p?.get(3),
                        c.predicted.getOrNull(k)?.let(STAGE_NAMES::get),
                    ).joinToString(",") { it?.toString() ?: "" },
                )
            }
        }
    }

    println("\n%-36s %6s %6s %6s %6s %6s %8s %8s %8s".format("model (leave-one-subject-out)", "acc", "kappa", "REMsens", "REMprec", "REMF1", "susPrec", "cue@REM", "cues"))
    for ((name, m) in metricsBy) {
        println(
            "%-36s %6.3f %6.3f %6.3f %6.3f %6.3f %8.3f %8.3f %8d".format(
                name, m.accuracy, m.kappa, m.sensitivity["rem"], m.precision["rem"], m.remF1, m.sustainedRemPrecision, m.wouldCueInRem, m.wouldCue,
            ),
        )
    }
    println("\nchosen: $chosen\nwrote ${out.path}/hmm-walch-v1.json, walch-report.json, walch-epochs.csv, walch-fold-weights.csv")
}
