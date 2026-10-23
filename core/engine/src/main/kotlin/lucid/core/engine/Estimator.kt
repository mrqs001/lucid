package lucid.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lucid.core.model.SleepStage
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Context the estimator may use besides the epoch's own features. */
data class NightContext(
    /** Hours since estimated sleep onset, or null before onset is detected. */
    val hoursSinceOnset: Float?,
)

/** One feature's push toward (positive) or away from (negative) REM, in weighted log-likelihood units. */
@Serializable
data class FeatureContribution(val feature: String, val value: Float?, val remLogLr: Float)

@Serializable
data class StagePrediction(
    val index: Int,
    val startMs: Long,
    /** Probabilities indexed by [SleepStage.ordinal]; sums to 1. */
    val probabilities: List<Float>,
    /** 0..1: how much data supported this estimate, scaled by how decisive it is. */
    val confidence: Float,
    val contributions: List<FeatureContribution>,
    val estimatorId: String,
) {
    fun p(stage: SleepStage): Float = probabilities[stage.ordinal]
    val pRem: Float get() = p(SleepStage.REM)
    val pWake: Float get() = p(SleepStage.WAKE)
    val mostLikely: SleepStage get() = SleepStage.entries[probabilities.indices.maxBy { probabilities[it] }]
}

/**
 * Pluggable sleep-stage estimator. Implementations must be causal (use only past and present
 * epochs) because cue decisions are made live.
 */
interface StageEstimator {
    val id: String
    val description: String

    /**
     * True only for estimators validated against polysomnography on the watch in use. The bundled
     * model was fitted and tested on lab nights from another watch, so it is not;
     * the UI must say so.
     */
    val validatedAgainstPsg: Boolean

    fun reset()

    fun update(features: EpochFeatures, context: NightContext): StagePrediction
}

/**
 * Hidden Markov model forward filter over {Wake, Light, Deep, REM} with 30 s epochs.
 *
 * - Transitions follow textbook sleep architecture and shift across the night (deep sleep early,
 *   longer and more frequent REM late).
 * - Emissions are naive-Bayes products of per-feature likelihoods. Each feature's influence is
 *   bounded and weighted by data quality; missing features simply drop out, so gaps make the
 *   estimate drift back toward the prior instead of inventing evidence.
 *
 * All parameters are literature-informed starting guesses, NOT fitted to data. This estimator has
 * not been validated against polysomnography; treat its REM probability as an experimental signal.
 */
class HmmStageEstimator(private val params: HmmParams = HmmParams.Default) : StageEstimator {
    override val id = params.spec.id
    override val description = params.spec.description
    override val validatedAgainstPsg = params.spec.validatedAgainstPsg

    private val k = SleepStage.entries.size
    private var alpha = params.initial.copyOf()

    override fun reset() {
        alpha = params.initial.copyOf()
    }

    override fun update(features: EpochFeatures, context: NightContext): StagePrediction {
        val trans = params.transitions(context.hoursSinceOnset)
        val prior = DoubleArray(k)
        for (from in 0 until k) for (to in 0 until k) prior[to] += alpha[from] * trans[from][to]

        val logEmission = DoubleArray(k)
        val contributions = ArrayList<FeatureContribution>()
        var supportUsed = 0.0
        var supportMax = 0.0
        for (f in params.features) {
            supportMax += f.weight
            val obs = f.observe(features) ?: continue
            // quality describes the heart-rate signal; movement stays informative when the pulse drops
            // out (it usually drops out *because* of movement), unless the watch is off the wrist.
            val reliability = if (f.fromHeart) features.quality.toDouble() else if (features.contactLost) 0.0 else 1.0
            val w = f.weight * reliability * (if (f.needsBaseline && !features.baselineReady) 0.5 else 1.0)
            if (w <= 0.0) continue
            val ll = DoubleArray(k) { s -> f.logLikelihood(obs, s) }
            val top = ll.max()
            for (s in 0 until k) ll[s] = max(ll[s] - top, -params.maxLogLrPerFeature)
            for (s in 0 until k) logEmission[s] += w * ll[s]
            supportUsed += w
            contributions += FeatureContribution(f.name, obs.toFloat(), (w * remLogLr(ll, prior)).toFloat())
        }

        val top = logEmission.max()
        val post = DoubleArray(k) { prior[it] * exp(logEmission[it] - top) }
        val z = post.sum()
        for (s in 0 until k) post[s] = if (z > 0) post[s] / z else params.initial[s]
        alpha = post

        val entropy = -post.sumOf { if (it > 0) it * ln(it) else 0.0 }
        val sharpness = 1.0 - entropy / ln(k.toDouble())
        val support = if (supportMax > 0) supportUsed / supportMax else 0.0
        val confidence = (support * (0.5 + 0.5 * sharpness)).coerceIn(0.0, 1.0)

        return StagePrediction(
            index = features.index,
            startMs = features.startMs,
            probabilities = post.map { it.toFloat() },
            confidence = confidence.toFloat(),
            contributions = contributions.sortedByDescending { kotlin.math.abs(it.remLogLr) },
            estimatorId = id,
        )
    }

    /** log P(obs | REM) - log P(obs | not REM), with "not REM" weighted by the current prior. */
    private fun remLogLr(ll: DoubleArray, prior: DoubleArray): Double {
        val r = SleepStage.REM.ordinal
        var num = 0.0
        var den = 0.0
        for (s in 0 until k) if (s != r) {
            num += prior[s] * exp(ll[s])
            den += prior[s]
        }
        if (den <= 0 || num <= 0) return 0.0
        return ll[r] - ln(num / den)
    }
}

/**
 * Serializable description of an [HmmStageEstimator], hand-set or fitted. Index order of every
 * per-stage list follows [SleepStage.ordinal]: wake, light, deep, REM.
 */
@Serializable
data class HmmSpec(
    val id: String,
    val description: String,
    val validatedAgainstPsg: Boolean = false,
    val initial: List<Double>,
    val features: List<FeatureSpec>,
    val transitions: TransitionSpec = TransitionSpec.Heuristic,
    val maxLogLrPerFeature: Double = 4.0,
) {
    fun toParams() = HmmParams(this)

    companion object {
        val json = Json {
            classDiscriminator = "kind"
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun parse(text: String): HmmSpec = json.decodeFromString(serializer(), text)

        /**
         * The default: fitted on 31 sleep-lab nights with wrist heart rate and motion (Walch et al. 2019),
         * tested leave-one-subject-out. See docs/MODEL.md; refit with `core/eval walch`.
         */
        val Walch: HmmSpec by lazy { bundled("hmm-walch-v1.json") }

        private fun bundled(name: String): HmmSpec =
            parse(HmmSpec::class.java.getResourceAsStream(name)!!.bufferedReader().use { it.readText() })

        /**
         * Starting priors, set from published stage physiology:
         * - HR instability (SDNN, LF/HF) separates REM best: SDNN ~105 ms in REM vs ~54 ms in N3
         *   (Herzig 2018); mean HR in REM is only ~4 % above NREM.
         * - RMSSD findings conflict (Herzig 2018 finds REM higher than N2/N3), so it carries little weight.
         * - Movement: wake > N1 > N2 > REM > N3.
         * Magnitudes are guesses (see docs/RESEARCH.md). Kept as the baseline the fitted model is
         * compared with, and as the template (features, bins) that `core/eval walch` fits.
         */
        val HandSet = HmmSpec(
            id = "hmm-heuristic-v1",
            description = "HMM forward filter, hand-set priors (uncalibrated)",
            initial = listOf(0.90, 0.08, 0.01, 0.01),
            features = listOf(
                FeatureSpec.Gaussian("hrRel", 0.8, needsBaseline = true, means = listOf(10.0, 2.0, 0.5, 4.5), sds = listOf(6.0, 3.0, 2.5, 3.5)),
                // ln-ratio to tonight's quiet median; Herzig 2018 SDNN gives REM ~ +0.4, N3 ~ -0.25 vs N2.
                FeatureSpec.Gaussian("hrInstability", 1.2, needsBaseline = true, means = listOf(0.7, 0.0, -0.3, 0.45), sds = listOf(0.45, 0.3, 0.3, 0.3)),
                FeatureSpec.Gaussian("lnRmssdRel", 0.4, needsBaseline = true, means = listOf(-0.3, 0.0, 0.1, 0.0), sds = listOf(0.35, 0.3, 0.3, 0.35)),
                // P(active seconds = 0 | 1-2 | 3+) per stage
                FeatureSpec.Categorical(
                    "movement", 1.0, edges = listOf(0.5, 2.5),
                    table = listOf(listOf(0.45, 0.25, 0.30), listOf(0.90, 0.07, 0.03), listOf(0.97, 0.025, 0.005), listOf(0.93, 0.05, 0.02)),
                ),
                // P(minutes since movement < 2 | 2-10 | > 10) per stage
                FeatureSpec.Categorical(
                    "stillness", 0.5, edges = listOf(2.0, 10.0),
                    table = listOf(listOf(0.60, 0.30, 0.10), listOf(0.25, 0.40, 0.35), listOf(0.10, 0.30, 0.60), listOf(0.20, 0.45, 0.35)),
                ),
            ),
        )
    }
}

@Serializable
sealed interface FeatureSpec {
    val name: String
    val weight: Double

    @Serializable
    @SerialName("gaussian")
    data class Gaussian(
        override val name: String,
        override val weight: Double,
        val needsBaseline: Boolean = false,
        val means: List<Double>,
        val sds: List<Double>,
    ) : FeatureSpec

    /** Value binned by [edges] (bin = number of edges <= value); one probability row per stage. */
    @Serializable
    @SerialName("categorical")
    data class Categorical(
        override val name: String,
        override val weight: Double,
        val edges: List<Double>,
        val table: List<List<Double>>,
    ) : FeatureSpec
}

@Serializable
sealed interface TransitionSpec {
    /** Hand-written functions of hours since sleep onset (deep early, longer REM late). */
    @Serializable
    @SerialName("heuristic")
    data object Heuristic : TransitionSpec

    /** Matrices counted from scored hypnograms: one before sleep onset, then one per hour band. */
    @Serializable
    @SerialName("binned")
    data class Binned(
        val preOnset: List<List<Double>>,
        val hourEdges: List<Double>,
        val byHour: List<List<List<Double>>>,
    ) : TransitionSpec
}

/** The epoch values a [FeatureSpec] can refer to, by name. */
object FeatureValues {
    private val extractors: Map<String, (EpochFeatures) -> Double?> = mapOf(
        "hrRel" to { f -> f.hrRel?.toDouble() },
        "hrInstability" to { f -> f.hrInstabilityRel?.toDouble() },
        "lnRmssdRel" to { f -> f.lnRmssdRel?.toDouble() },
        "movement" to { f -> if (f.motionCoverage < 0.3f) null else f.activeSeconds.toDouble() },
        "stillness" to { f -> f.minutesSinceMovement?.toDouble() },
    )

    val names: Set<String> get() = extractors.keys

    /** Features computed from the accelerometer; the rest come from the heart-rate sensor. */
    val fromMotion: Set<String> = setOf("movement", "stillness")

    fun of(name: String): (EpochFeatures) -> Double? = extractors[name] ?: error("unknown feature '$name'")
}

/** Ready-to-use parameters of [HmmStageEstimator], built from an [HmmSpec]. */
class HmmParams(val spec: HmmSpec) {
    val initial: DoubleArray = spec.initial.toDoubleArray()
    val features: List<FeatureModel> = spec.features.map(FeatureModel::from)
    val maxLogLrPerFeature: Double get() = spec.maxLogLrPerFeature

    private val binned = spec.transitions as? TransitionSpec.Binned
    private val preOnset = binned?.preOnset?.let(::matrix)
    private val byHour = binned?.byHour?.map(::matrix)

    /** Per-epoch transition matrix [from][to] given hours since sleep onset (null = not yet asleep). */
    fun transitions(hours: Float?): Array<DoubleArray> = when {
        binned == null -> heuristic(hours)
        hours == null -> preOnset!!
        else -> byHour!![binned.hourEdges.count { hours >= it }.coerceAtMost(byHour.lastIndex)]
    }

    companion object {
        val Default: HmmParams by lazy { HmmParams(HmmSpec.Walch) }

        private fun matrix(rows: List<List<Double>>) = Array(rows.size) { rows[it].toDoubleArray() }

        private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

        fun heuristic(hours: Float?): Array<DoubleArray> {
            val h = (hours ?: 0f).toDouble()
            val asleep = hours != null
            val w = SleepStage.WAKE.ordinal
            val l = SleepStage.LIGHT.ordinal
            val d = SleepStage.DEEP.ordinal
            val r = SleepStage.REM.ordinal
            val m = Array(4) { DoubleArray(4) }
            // Wake: long in-bed wake before sleep onset, brief awakenings afterwards.
            m[w][l] = if (asleep) 0.15 else 0.04
            m[w][r] = if (asleep) 0.01 else 0.0
            // Light: deep sleep likely early, REM increasingly likely after the first cycle.
            m[l][w] = 0.012
            m[l][d] = 0.035 * exp(-h / 2.0) + 0.002
            m[l][r] = 0.002 + 0.012 * sigmoid((h - 1.0) / 0.3)
            // Deep
            m[d][l] = 0.03
            m[d][w] = 0.004
            m[d][r] = 0.001
            // REM: short periods early (~10 min), long periods late (~25 min).
            m[r][l] = 0.045 * exp(-h / 2.5) + 0.008
            m[r][w] = 0.008
            for (i in 0 until 4) m[i][i] = 1.0 - m[i].sum()
            return m
        }
    }
}

/** Likelihood model for one feature. */
sealed class FeatureModel(val name: String, val weight: Double, val needsBaseline: Boolean) {
    val fromHeart: Boolean = name !in FeatureValues.fromMotion

    abstract fun observe(f: EpochFeatures): Double?
    abstract fun logLikelihood(x: Double, stage: Int): Double

    class Gaussian(private val spec: FeatureSpec.Gaussian) : FeatureModel(spec.name, spec.weight, spec.needsBaseline) {
        private val extract = FeatureValues.of(spec.name)
        private val means = spec.means.toDoubleArray()
        private val sds = spec.sds.toDoubleArray()
        override fun observe(f: EpochFeatures) = extract(f)
        override fun logLikelihood(x: Double, stage: Int): Double {
            val z = (x - means[stage]) / sds[stage]
            return -0.5 * z * z - ln(sds[stage])
        }
    }

    class Categorical(private val spec: FeatureSpec.Categorical) : FeatureModel(spec.name, spec.weight, needsBaseline = false) {
        private val extract = FeatureValues.of(spec.name)
        private val table = Array(spec.table.size) { spec.table[it].toDoubleArray() }
        override fun observe(f: EpochFeatures) = extract(f)?.let { v -> spec.edges.count { v >= it }.toDouble() }
        override fun logLikelihood(x: Double, stage: Int) = ln(table[stage][x.toInt()])
    }

    companion object {
        fun from(spec: FeatureSpec): FeatureModel = when (spec) {
            is FeatureSpec.Gaussian -> Gaussian(spec)
            is FeatureSpec.Categorical -> Categorical(spec)
        }
    }
}
