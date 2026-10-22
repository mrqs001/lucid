package lucid.core.engine

import kotlinx.serialization.Serializable
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
     * True only for estimators validated against polysomnography. The bundled heuristic is not;
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
    override val id = "hmm-heuristic-v1"
    override val description = "HMM forward filter, hand-set priors (uncalibrated)"
    override val validatedAgainstPsg = false

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
            val w = f.weight * features.quality.toDouble() * (if (f.needsBaseline && !features.baselineReady) 0.5 else 1.0)
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

/** Parameters of [HmmStageEstimator]. Index order follows [SleepStage.ordinal]: W, L, D, R. */
class HmmParams(
    val initial: DoubleArray,
    val features: List<FeatureModel>,
    val maxLogLrPerFeature: Double = 4.0,
) {
    /** Per-epoch transition matrix [from][to] given hours since sleep onset (null = not yet asleep). */
    fun transitions(hours: Float?): Array<DoubleArray> {
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

    companion object {
        private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

        /**
         * Starting priors, set from published stage physiology:
         * - HR instability (SDNN, LF/HF) separates REM best: SDNN ~105 ms in REM vs ~54 ms in N3
         *   (Herzig 2018); mean HR in REM is only ~4 % above NREM.
         * - RMSSD findings conflict (Herzig 2018 finds REM higher than N2/N3), so it carries little weight.
         * - Movement: wake > N1 > N2 > REM > N3.
         * Magnitudes are guesses until fitted on PSG-labelled wrist data (see docs/RESEARCH.md).
         */
        val Default = HmmParams(
            initial = doubleArrayOf(0.90, 0.08, 0.01, 0.01),
            features = listOf(
                FeatureModel.Gaussian(
                    "hrRel", weight = 0.8, needsBaseline = true,
                    means = doubleArrayOf(10.0, 2.0, 0.5, 4.5), sds = doubleArrayOf(6.0, 3.0, 2.5, 3.5),
                ) { it.hrRel?.toDouble() },
                // ln-ratio to tonight's quiet median; Herzig 2018 SDNN gives REM ~ +0.4, N3 ~ -0.25 vs N2.
                FeatureModel.Gaussian(
                    "hrInstability", weight = 1.2, needsBaseline = true,
                    means = doubleArrayOf(0.7, 0.0, -0.3, 0.45), sds = doubleArrayOf(0.45, 0.3, 0.3, 0.3),
                ) { it.hrInstabilityRel?.toDouble() },
                FeatureModel.Gaussian(
                    "lnRmssdRel", weight = 0.4, needsBaseline = true,
                    means = doubleArrayOf(-0.3, 0.0, 0.1, 0.0), sds = doubleArrayOf(0.35, 0.3, 0.3, 0.35),
                ) { it.lnRmssdRel?.toDouble() },
                FeatureModel.Categorical(
                    "movement", weight = 1.0,
                    // P(activeSeconds = 0 | 1-2 | 3+) per stage
                    table = arrayOf(
                        doubleArrayOf(0.45, 0.25, 0.30),
                        doubleArrayOf(0.90, 0.07, 0.03),
                        doubleArrayOf(0.97, 0.025, 0.005),
                        doubleArrayOf(0.93, 0.05, 0.02),
                    ),
                ) { f -> if (f.motionCoverage < 0.3f) null else when (f.activeSeconds) { 0 -> 0; 1, 2 -> 1; else -> 2 } },
                FeatureModel.Categorical(
                    "stillness", weight = 0.5,
                    // P(minutes since movement < 2 | 2-10 | > 10) per stage
                    table = arrayOf(
                        doubleArrayOf(0.60, 0.30, 0.10),
                        doubleArrayOf(0.25, 0.40, 0.35),
                        doubleArrayOf(0.10, 0.30, 0.60),
                        doubleArrayOf(0.20, 0.45, 0.35),
                    ),
                ) { f -> f.minutesSinceMovement?.let { if (it < 2f) 0 else if (it < 10f) 1 else 2 } },
            ),
        )
    }
}

/** Likelihood model for one feature. */
sealed class FeatureModel(val name: String, val weight: Double, val needsBaseline: Boolean) {
    abstract fun observe(f: EpochFeatures): Double?
    abstract fun logLikelihood(x: Double, stage: Int): Double

    class Gaussian(
        name: String,
        weight: Double,
        needsBaseline: Boolean = false,
        private val means: DoubleArray,
        private val sds: DoubleArray,
        private val extract: (EpochFeatures) -> Double?,
    ) : FeatureModel(name, weight, needsBaseline) {
        override fun observe(f: EpochFeatures) = extract(f)
        override fun logLikelihood(x: Double, stage: Int): Double {
            val z = (x - means[stage]) / sds[stage]
            return -0.5 * z * z - ln(sds[stage])
        }
    }

    class Categorical(
        name: String,
        weight: Double,
        private val table: Array<DoubleArray>,
        private val extract: (EpochFeatures) -> Int?,
    ) : FeatureModel(name, weight, needsBaseline = false) {
        override fun observe(f: EpochFeatures) = extract(f)?.toDouble()
        override fun logLikelihood(x: Double, stage: Int) = ln(table[stage][x.toInt()])
    }
}
